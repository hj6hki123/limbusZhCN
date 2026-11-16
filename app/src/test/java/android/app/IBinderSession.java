package android.app;

import android.os.IInterface;
import android.os.IBinder;

/** Test-only stand-in for the hidden session interface, absent from the public SDK. */
public interface IBinderSession extends IInterface {
    /** Mirrors the framework factory without shipping a hidden API class in production. */
    final class Stub {
        public static IBinderSession asInterface(IBinder binder) {
            return binder == null ? null : () -> binder;
        }
    }
}
