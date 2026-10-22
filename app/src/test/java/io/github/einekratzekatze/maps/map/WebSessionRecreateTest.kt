package io.github.einekratzekatze.maps.map

import android.webkit.CookieManager
import androidx.test.core.app.ActivityScenario
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.github.einekratzekatze.maps.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** compose-host.md §2.3: the per-launch wipe must not run on a configuration-change recreation. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class WebSessionRecreateTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    private val url = "https://www.google.com"
    private fun hasTestCookie() = CookieManager.getInstance().getCookie(url)?.contains("TEST=1") == true

    @Test
    fun recreateKeepsCookies() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            CookieManager.getInstance().setCookie(url, "TEST=1")
            scenario.recreate()
            assertTrue(hasTestCookie())
        }
    }

    @Test
    fun closeAndRelaunchWipesCookies() {
        ActivityScenario.launch(MainActivity::class.java).use {
            CookieManager.getInstance().setCookie(url, "TEST=1")
            assertTrue(hasTestCookie())
        }
        assertFalse(hasTestCookie())
        CookieManager.getInstance().setCookie(url, "TEST=1")
        ActivityScenario.launch(MainActivity::class.java).use {
            assertFalse(hasTestCookie())
        }
    }
}
