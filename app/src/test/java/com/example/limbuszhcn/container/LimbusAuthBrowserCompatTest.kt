package com.example.limbuszhcn.container

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import com.lody.virtual.helper.compat.LimbusAuthBrowserCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LimbusAuthBrowserCompatTest {
    private val game = "com.ProjectMoon.LimbusCompany"
    private val authUrl = "https://project.firebaseapp.com/__/auth/handler?providerId=apple.com&eventId=test"
    private val pm get() = RuntimeEnvironment.getApplication().packageManager

    @Test
    fun recognizesFirebaseBareViewPreflightWithoutAllowingItsLaunch() {
        val preflight = Intent(Intent.ACTION_VIEW)
        assertTrue(LimbusAuthBrowserCompat.isBrowserLookup(game, preflight))
        assertFalse(LimbusAuthBrowserCompat.isAuthBrowserIntent(game, preflight))
        assertNull(LimbusAuthBrowserCompat.buildBrowserLaunch(game, pm, preflight))
        assertFalse(LimbusAuthBrowserCompat.isBrowserLookup("other.app", preflight))
    }

    @Test
    fun rejectsOtherAppsUrlsAndSelectors() {
        for (url in listOf(
            "http://project.firebaseapp.com/__/auth/handler",
            "https://project.firebaseapp.com.evil.test/__/auth/handler",
            "https://evil.test/__/auth/handler",
            "https://project.firebaseapp.com/other",
            "https://project.firebaseapp.com/%5f_/auth/handler",
            "https://user@project.firebaseapp.com/__/auth/handler",
            "https://project.firebaseapp.com:8443/__/auth/handler",
            "https://project.firebaseapp.com/__/auth/handler#fragment",
            "file:///__/auth/handler",
            "genericidp://firebase.auth/"
        )) {
            assertFalse(url, LimbusAuthBrowserCompat.isBrowserLookup(game, Intent(Intent.ACTION_VIEW, Uri.parse(url))))
        }
        val valid = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
        assertTrue(LimbusAuthBrowserCompat.isAuthBrowserIntent(game, valid))
        assertFalse(LimbusAuthBrowserCompat.isAuthBrowserIntent("other.app", valid))
        assertFalse(LimbusAuthBrowserCompat.isAuthBrowserIntent(game, Intent(valid).setAction(Intent.ACTION_SEND)))
        assertFalse(LimbusAuthBrowserCompat.isAuthBrowserIntent(game, Intent(valid).setDataAndType(valid.data, "text/html")))
        assertFalse(LimbusAuthBrowserCompat.isAuthBrowserIntent(game,
            Intent(valid).apply { selector = Intent(Intent.ACTION_MAIN) }))
    }

    @Test
    fun preflightResolvesOnlyActualExportedBrowsers() {
        val browser = browser("example.browser")
        val deepLink = browser("example.deep.link").apply {
            filter = IntentFilter(Intent.ACTION_VIEW).apply { addDataAuthority("project.firebaseapp.com", null) }
        }
        val hidden = browser("example.private").apply { activityInfo.exported = false }
        val disabled = browser("example.disabled").apply { activityInfo.applicationInfo.enabled = false }
        val protected = browser("example.protected").apply { activityInfo.permission = "private.permission" }
        register(listOf(deepLink, hidden, disabled, protected, browser))
        val preflight = Intent(Intent.ACTION_VIEW)
        assertEquals(listOf("example.browser"),
            LimbusAuthBrowserCompat.queryBrowsers(pm, preflight).map { it.activityInfo.packageName })
        assertEquals("example.browser", LimbusAuthBrowserCompat.resolveBrowser(pm, preflight)?.activityInfo?.packageName)
    }

    @Test
    fun launchPreservesAuthUrlButDropsExtrasGrantsAndClipData() {
        register(listOf(browser("example.browser")))
        val source = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
            putExtra("untrusted", "do not forward")
            clipData = ClipData.newPlainText("private", "do not forward")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        val launch = requireNotNull(LimbusAuthBrowserCompat.buildBrowserLaunch(game, pm, source))
        assertEquals(source.data, launch.data)
        assertEquals(Intent.ACTION_VIEW, launch.action)
        assertEquals(ComponentName("example.browser", "example.browser.Main"), launch.component)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), launch.categories)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, launch.flags)
        assertNull(launch.extras)
        assertNull(launch.clipData)
        assertNull(launch.selector)
        assertNull(launch.type)
    }

    @Test
    fun explicitNonBrowserAndMissingBrowserCannotBypassIsolation() {
        val source = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
        assertNull(LimbusAuthBrowserCompat.buildBrowserLaunch(game, pm, source))
        register(listOf(browser("example.browser")))
        source.component = ComponentName("example.browser", "example.browser.Private")
        assertNull(LimbusAuthBrowserCompat.buildBrowserLaunch(game, pm, source))
    }

    private fun browser(pkg: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.Main"
            enabled = true
            exported = true
            applicationInfo = ApplicationInfo().apply { packageName = pkg; enabled = true }
        }
    }

    private fun register(browsers: List<ResolveInfo>) {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE)
        shadowOf(pm).addResolveInfoForIntent(probe, browsers)
    }
}
