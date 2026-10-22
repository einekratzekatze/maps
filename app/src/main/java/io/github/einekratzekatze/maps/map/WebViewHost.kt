/*
Copyright (c) 2017-2019 Divested Computing Group

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
package io.github.einekratzekatze.maps.map

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.github.einekratzekatze.maps.BuildConfig
import io.github.einekratzekatze.maps.MainActivity
import io.github.einekratzekatze.maps.R
import io.github.einekratzekatze.maps.navigation.NavLauncher
import io.github.einekratzekatze.maps.navigation.StartDeduper
import io.github.einekratzekatze.maps.navigation.StartOrigin
import io.github.einekratzekatze.maps.navigation.StartPress
import io.github.einekratzekatze.maps.navigation.StartTargetParser

/**
 * The single owner of the WebView (compose-host.md §3). Activity-scoped: created in `onCreate`,
 * destroyed in `onDestroy`. Not injected into ViewModels. Only this class touches the WebView,
 * its settings and its clients.
 */
class WebViewHost(
    private val activity: ComponentActivity,
    freshLaunch: Boolean,
    private val onUrl: (String) -> Unit,
) {
    val webView: WebView = WebView(activity)
    private val settings: WebSettings = webView.settings
    private val startDeduper = StartDeduper(now = SystemClock::elapsedRealtime)
    private var locationRequestCount = 0

    companion object {
        private const val TAG = "GMapsWV"
        private const val SPIKE_TAG = "SPIKE"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

        private val allowedDomains = setOf(
            "apis.google.com",
            "consent.google.com",
            "fonts.gstatic.com",
            "google.com",
            "khms0.google.com",
            "khms1.google.com",
            "khms2.google.com",
            "khms3.google.com",
            "maps.app.goo.gl",
            "maps.google.com",
            "maps.gstatic.com",
            "ssl.gstatic.com",
            "streetviewpixels-pa.googleapis.com",
            "www.google.com",
            "www.gstatic.com",
        )
        private val allowedDomainsStart = setOf("consent.google.") // TODO: better cctld handling
        private val allowedDomainsEnd = setOf(".googleusercontent.com")
        private val blockedURLs = setOf(
            "analytics.google.com",
            "clientmetrics-pa.googleapis.com",
            "doubleclick.com",
            "doubleclick.net",
            "googleadservices.com",
            "google-analytics.com",
            "googlesyndication.com",
            "tpc.googlesyndication.com",
            "pagead.l.google.com",
            "partnerad.l.google.com",
            "video-stats.video.google.com",
            "wintricksbanner.googlepages.com",
            "www-google-analytics.l.google.com",
            "gstaticadssl.l.google.com",
            "csp.withgoogle.com",
            "google.com/maps/preview/log204",
            "google.com/gen_204",
            "play.google.com/log",
            "/gen_204?",
            "/log204?",
        )

        private fun wipeSessionData() {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            WebStorage.getInstance().deleteAllData()
        }
    }

    init {
        // Upstream's per-launch wipe; [freshLaunch] is false on a configuration-change recreation (§2.3).
        if (freshLaunch) {
            webView.clearCache(true)
            wipeSessionData()
        }
        // Was android:keepScreenOn on the root layout of the deleted activity_main.xml.
        webView.keepScreenOn = true
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
            setCookie(".google.com", "SOCS=CAI;")
        }
        webView.webChromeClient = ChromeClient()
        webView.webViewClient = Client()
        updateDarkening(activity.resources.configuration)
        @SuppressLint("SetJavaScriptEnabled")
        settings.apply {
            javaScriptEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setGeolocationEnabled(true)
            allowContentAccess = false
            allowFileAccess = false
            builtInZoomControls = false
            displayZoomControls = false
            domStorageEnabled = false
            @Suppress("DEPRECATION")
            saveFormData = false
            userAgentString = USER_AGENT
        }
    }

    fun loadUrl(url: String) = webView.loadUrl(url)

    /** History-back, or false when there is nothing to go back to (the caller then finishes). */
    fun goBackInHistory(): Boolean {
        if (webView.canGoBack() && webView.url != "about:blank") {
            webView.goBack()
            return true
        }
        return false
    }

    fun updateDarkening(config: Configuration) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING) && Build.VERSION.SDK_INT >= 29) {
            val nightMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            try {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, nightMode)
            } catch (e: UnsupportedOperationException) {
                // The feature check and the installed WebView APK can disagree (also under Robolectric).
                Log.w(TAG, "Algorithmic darkening not supported", e)
            }
        }
    }

    /**
     * [wipe] = true: upstream's exit wipe (cache, history, cookies, web storage). [wipe] = false
     * (configuration-change recreation): only destroy the view, keep cookies and storage (§2.3).
     */
    fun destroy(wipe: Boolean) {
        webView.apply {
            if (wipe) {
                clearFormData()
                clearHistory()
                clearMatches()
                clearSslPreferences()
                clearCache(true)
            }
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }
        settings.javaScriptEnabled = false
        if (wipe) wipeSessionData()
    }

    private fun block() = WebResourceResponse("text/javascript", "UTF-8", null)

    private inner class ChromeClient : WebChromeClient() {
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                if (locationRequestCount < 2) {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.title_location_permission)
                        .setMessage(R.string.text_location_permission)
                        .setNegativeButton(R.string.button_no_thanks) { _, _ -> locationRequestCount = 100 }
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            activity.requestPermissions(arrayOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ), 100)
                        }
                        .create()
                        .show()
                }
                locationRequestCount++
            } else {
                val locationManager = activity.getSystemService(LocationManager::class.java)
                if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    Toast.makeText(activity, R.string.error_no_gps, Toast.LENGTH_LONG).show()
                }
            }
            if (origin.contains("google.com")) {
                callback.invoke(origin, true, false)
            }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            val host = request.url.host ?: return block()
            if (url == "about:blank") return null
            if (!url.startsWith("https://")) {
                Log.d(TAG, "[shouldInterceptRequest][NON-HTTPS] Blocked $url")
                return block()
            }
            val allowed = host in allowedDomains ||
                    allowedDomainsStart.any { host.startsWith(it) } ||
                    allowedDomainsEnd.any { host.endsWith(it) } ||
                    (host == "gstatic.com" && request.url.path?.startsWith("/local/placeinfo/") == true)
            if (!allowed) {
                Log.d(TAG, "[shouldInterceptRequest][NOT ON ALLOWLIST] Blocked $host")
                return block()
            }
            if (blockedURLs.any { url.contains(it) }) {
                Log.d(TAG, "[shouldInterceptRequest][ON DENYLIST] Blocked $url")
                return block()
            }
            return null
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            if (BuildConfig.DEBUG) Log.d(SPIKE_TAG, "history reload=$isReload url=$url")
            onUrl(url)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (BuildConfig.DEBUG) Log.d(SPIKE_TAG, "pageStarted url=$url")
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val host = request.url.host
            if (BuildConfig.DEBUG) Log.d(SPIKE_TAG, "override gesture=${request.hasGesture()} url=$url current=${view.url}")
            if (StartPress.isStart(url, view.url)) {
                // Start only hands off the destination; loading its link would drop the origin (spike N0).
                // The press fires two navigations (https, intent://); the deduper launches once.
                // Unparseable links are ignored, never loaded.
                val handoff = StartTargetParser.parse(url)
                if (handoff != null && startDeduper.shouldLaunch(handoff.target)) {
                    // TODO.md 2.2: preferred app setting, pending-drive prompt, OsmAnd deep links (later tasks)
                    NavLauncher.launch(activity, handoff.target, ComponentName(activity, MainActivity::class.java), StartOrigin.parse(view.url), handoff.mode)
                }
                Log.d(TAG, "[shouldOverrideUrlLoading][START] Handled (parsed=${handoff != null}), keeping the route page")
                return true
            }
            if (url == "about:blank") return false
            if (url.startsWith("tel:")) {
                activity.startActivity(Intent(Intent.ACTION_DIAL, request.url))
                return true
            }
            if (!url.startsWith("https://")) {
                Log.d(TAG, "[shouldOverrideUrlLoading][NON-HTTPS] Blocked $url")
                if (url.startsWith("intent://maps.app.goo.gl/?link=")) {
                    // Firebase Dynamic Link — extract the inner Maps URL cleanly
                    url.toUri().getQueryParameter("link")?.let { view.loadUrl(it) }
                } else if (url.startsWith("http://")) {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.title_open_link)
                        .setIcon(R.drawable.ic_warning)
                        .setMessage("${activity.getString(R.string.text_warning_link)}\n\n${activity.getString(R.string.text_open_link, url)}")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ -> activity.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        .create()
                        .show()
                }
                return true
            }
            if (host == null) return true
            val allowed = host in allowedDomains ||
                    allowedDomainsStart.any { host.startsWith(it) } ||
                    allowedDomainsEnd.any { host.endsWith(it) }
            if (!allowed) {
                Log.d(TAG, "[shouldOverrideUrlLoading][NOT ON ALLOWLIST] Blocked $host")
                AlertDialog.Builder(activity)
                    .setTitle(R.string.title_open_link)
                    .setMessage(activity.getString(R.string.text_open_link, url))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> activity.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    .create()
                    .show()
                return true
            }
            if (blockedURLs.any { url.contains(it) }) {
                Log.d(TAG, "[shouldOverrideUrlLoading][ON DENYLIST] Blocked $url")
                return true
            }
            return false
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (BuildConfig.DEBUG) Log.d(SPIKE_TAG, "pageFinished url=$url")
            onUrl(url)
            view.evaluateJavascript("""
                var head = document.getElementsByTagName('head');
                if (head.length > 0) {
                    var style = document.createElement('style');
                    style.setAttribute('type', 'text/css');
                    style.textContent = '.ml-persistent-promo-banner { display: none !important; } #app { top: 0 !important }';
                    head[0].appendChild(style);
                }
            """.trimIndent(), null)
        }
    }
}
