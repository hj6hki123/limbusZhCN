package com.lody.virtual.helper.compat;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Narrow host-browser exception to Limbus package/activity isolation. */
public final class LimbusAuthBrowserCompat {
    private static final String LIMBUS_PACKAGE = "com.ProjectMoon.LimbusCompany";

    private LimbusAuthBrowserCompat() { }

    public static boolean isAuthBrowserIntent(String currentPackage, Intent intent) {
        if (!isViewIntent(currentPackage, intent) || intent.getType() != null) return false;
        Uri uri = intent.getData();
        if (uri == null || !"https".equals(uri.getScheme())
                || uri.getUserInfo() != null || uri.getPort() != -1
                || uri.getFragment() != null || !"/__/auth/handler".equals(uri.getEncodedPath())) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.endsWith(".firebaseapp.com") || host.endsWith(".web.app");
    }

    public static boolean isBrowserLookup(String currentPackage, Intent intent) {
        // Firebase Auth probes ACTION_VIEW without a URI before launching its auth URL.
        return isAuthBrowserIntent(currentPackage, intent)
                || (isViewIntent(currentPackage, intent) && intent.getData() == null
                && intent.getType() == null && intent.getPackage() == null
                && intent.getComponent() == null);
    }

    private static boolean isViewIntent(String currentPackage, Intent intent) {
        return LIMBUS_PACKAGE.equals(currentPackage) && intent != null
                && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getSelector() == null;
    }

    /** Query generic HTTPS handlers so app-specific deep links cannot impersonate a browser. */
    public static List<ResolveInfo> queryBrowsers(PackageManager hostPm, Intent source) {
        Intent probe = browserProbe(source);
        List<ResolveInfo> result = new ArrayList<>();
        List<ResolveInfo> candidates = hostPm.queryIntentActivities(probe,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_RESOLVED_FILTER);
        if (candidates == null) return result;
        for (ResolveInfo info : candidates) {
            ActivityInfo activity = info == null ? null : info.activityInfo;
            if (activity == null || !activity.enabled || !activity.exported
                    || activity.applicationInfo == null || !activity.applicationInfo.enabled
                    || activity.permission != null) continue;
            if (info.filter != null && (info.filter.countDataAuthorities() > 0
                    || info.filter.countDataPaths() > 0)) continue;
            ComponentName component = new ComponentName(activity.packageName, activity.name);
            if (source.getComponent() != null && !source.getComponent().equals(component)) continue;
            result.add(info);
        }
        return result;
    }

    private static Intent browserProbe(Intent source) {
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://"));
        probe.addCategory(Intent.CATEGORY_BROWSABLE);
        String pkg = source.getComponent() == null ? source.getPackage()
                : source.getComponent().getPackageName();
        probe.setPackage(pkg);
        return probe;
    }

    public static ResolveInfo resolveBrowser(PackageManager hostPm, Intent source) {
        List<ResolveInfo> browsers = queryBrowsers(hostPm, source);
        if (browsers.isEmpty()) return null;
        ResolveInfo preferred = hostPm.resolveActivity(browserProbe(source), PackageManager.MATCH_DEFAULT_ONLY);
        if (preferred != null && preferred.activityInfo != null) {
            for (ResolveInfo browser : browsers) {
                if (browser.activityInfo.packageName.equals(preferred.activityInfo.packageName)
                        && browser.activityInfo.name.equals(preferred.activityInfo.name)) return browser;
            }
        }
        // The system resolver may be returned when no default is set; use an actual browser.
        return browsers.get(0);
    }

    public static Intent buildBrowserLaunch(String currentPackage, PackageManager hostPm, Intent source) {
        if (!isAuthBrowserIntent(currentPackage, source)) return null;
        ResolveInfo browser = resolveBrowser(hostPm, source);
        if (browser == null) return null;
        Intent launch = new Intent(Intent.ACTION_VIEW, source.getData());
        launch.addCategory(Intent.CATEGORY_BROWSABLE);
        launch.setComponent(new ComponentName(browser.activityInfo.packageName, browser.activityInfo.name));
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return launch;
    }
}
