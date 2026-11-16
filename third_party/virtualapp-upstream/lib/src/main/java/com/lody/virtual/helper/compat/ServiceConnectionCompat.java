package com.lody.virtual.helper.compat;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.os.BadParcelableException;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/** Binder compatibility for frameworks that add IBinderSession to connected(). */
public final class ServiceConnectionCompat {
    public static final String DESCRIPTOR = "android.app.IServiceConnection";
    public static final int TRANSACTION_CONNECTED = IBinder.FIRST_CALL_TRANSACTION;
    private static final boolean SESSION_AWARE = usesBinderSession(IServiceConnection.class);

    private ServiceConnectionCompat() {
    }

    public static boolean isSessionAware() {
        return SESSION_AWARE;
    }

    /** Check the actual framework ABI, including vendor backports, without loading a new type. */
    public static boolean usesBinderSession(Class<?> connectionInterface) {
        return findSessionConnectedMethod(connectionInterface) != null;
    }

    private static Method findSessionConnectedMethod(Class<?> connectionInterface) {
        for (Method method : connectionInterface.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if ("connected".equals(method.getName())
                    && method.getReturnType() == void.class
                    && parameters.length == 4
                    && parameters[0] == ComponentName.class
                    && parameters[1] == IBinder.class
                    && "android.app.IBinderSession".equals(parameters[2].getName())
                    && parameters[3] == boolean.class) {
                return method;
            }
        }
        return null;
    }

    public static SessionConnection readSessionConnected(Parcel data) {
        data.enforceInterface(DESCRIPTOR);
        ComponentName name = data.readInt() != 0 ? ComponentName.CREATOR.createFromParcel(data) : null;
        IBinder service = data.readStrongBinder();
        IBinder session = data.readStrongBinder();
        boolean dead = data.readInt() != 0;
        if (data.dataAvail() != 0) {
            throw new BadParcelableException("Unexpected IServiceConnection.connected payload");
        }
        return new SessionConnection(name, service, session, dead);
    }

    /** Forward the session as an opaque Binder; never discard it or recreate its identity. */
    public static void sendSessionConnected(IBinder target, ComponentName name, IBinder service,
                                            IBinder session, boolean dead) throws RemoteException {
        IInterface local = target.queryLocalInterface(DESCRIPTOR);
        Method connected = local == null ? null : findSessionConnectedMethod(local.getClass());
        if (connected != null) {
            // StubBinder is a Java IBinder decorator, not a native Binder. Parceling it
            // drops the service. LoadedApk's local connection must receive it directly.
            try {
                Object sessionInterface = null;
                if (session != null) {
                    Class<?> sessionType = connected.getParameterTypes()[2];
                    Class<?> stub = Class.forName(sessionType.getName() + "$Stub", false,
                            sessionType.getClassLoader());
                    Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
                    asInterface.setAccessible(true);
                    sessionInterface = asInterface.invoke(null, session);
                }
                connected.setAccessible(true);
                connected.invoke(local, name, service, sessionInterface, dead);
                return;
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof RemoteException) throw (RemoteException) cause;
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw localDeliveryError(cause);
            } catch (ReflectiveOperationException error) {
                // Never retry through Parcel: that would silently discard the decorator.
                throw localDeliveryError(error);
            }
        }
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (name != null) {
                data.writeInt(1);
                name.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            data.writeStrongBinder(service);
            data.writeStrongBinder(session);
            data.writeInt(dead ? 1 : 0);
            if (!target.transact(TRANSACTION_CONNECTED, data, null, IBinder.FLAG_ONEWAY)) {
                throw new RemoteException("IServiceConnection rejected connected callback");
            }
        } finally {
            data.recycle();
        }
    }

    private static RemoteException localDeliveryError(Throwable cause) {
        RemoteException error = new RemoteException("Unable to deliver local session-aware connection");
        error.initCause(cause);
        return error;
    }

    public static final class SessionConnection {
        public final ComponentName name;
        public final IBinder service;
        public final IBinder session;
        public final boolean dead;

        private SessionConnection(ComponentName name, IBinder service, IBinder session, boolean dead) {
            this.name = name;
            this.service = service;
            this.session = session;
            this.dead = dead;
        }
    }
}
