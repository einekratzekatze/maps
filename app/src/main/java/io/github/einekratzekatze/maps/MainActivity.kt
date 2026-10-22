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
package io.github.einekratzekatze.maps

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ComponentName
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationListener
import android.location.LocationManager
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Window
import android.view.WindowManager
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
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import io.github.einekratzekatze.maps.navigation.NavLauncher
import io.github.einekratzekatze.maps.navigation.StartDeduper
import io.github.einekratzekatze.maps.navigation.StartOrigin
import io.github.einekratzekatze.maps.navigation.StartPress
import io.github.einekratzekatze.maps.navigation.StartTargetParser
import dagger.hilt.android.AndroidEntryPoint
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var mapsWebView: WebView? = null
    private val startDeduper = StartDeduper(now = SystemClock::elapsedRealtime)
    private var mapsWebSettings: WebSettings? = null
    private var mapsCookieManager: CookieManager? = null
    private var locationManager: LocationManager? = null
    private var locationListenerGPS: LocationListener? = null
    private var locationRequestCount = 0

    companion object {
        private const val TAG = "GMapsWV"
        private const val SPIKE_TAG = "SPIKE"

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
    }

    override fun onPause() {
        super.onPause()
        if (locationListenerGPS != null) removeLocationListener()
    }

    override fun onResume() {
        super.onResume()
        locationListenerGPS = LocationListener { }
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager!!.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locationListenerGPS!!)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING) && Build.VERSION.SDK_INT >= 29) {
            val nightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(mapsWebSettings!!, nightMode)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        var urlToLoad = "https://www.google.com/maps"
        try {
            val data = intent.data!!
            urlToLoad = when {
                data.toString().startsWith("https://") -> data.toString()
                data.toString().startsWith("geo:") -> "https://www.google.com/maps/place/" + data.toString().substring(4)
                else -> data.toString()
            }
        } catch (e: Exception) {
            Log.d(TAG, "No or Invalid URL passed (${urlToLoad}). Opening homepage instead.")
        }

        mapsWebView = findViewById(R.id.mapsWebView)
        mapsCookieManager = CookieManager.getInstance()
        resetWebView(false)
        mapsCookieManager!!.setAcceptCookie(true)
        mapsCookieManager!!.setAcceptThirdPartyCookies(mapsWebView, false)
        mapsCookieManager!!.setCookie(".google.com", "SOCS=CAI;")

        initShareLinkListener()

        // Back navigation — goes back in WebView history before closing the app
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = mapsWebView
                if (webView != null && webView.canGoBack() && webView.url != "about:blank") {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        mapsWebView!!.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    if (locationRequestCount < 2) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.title_location_permission)
                            .setMessage(R.string.text_location_permission)
                            .setNegativeButton(R.string.button_no_thanks) { _, _ -> locationRequestCount = 100 }
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                requestPermissions(arrayOf(
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                ), 100)
                            }
                            .create()
                            .show()
                    }
                    locationRequestCount++
                } else {
                    if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == false) {
                        Toast.makeText(this@MainActivity, R.string.error_no_gps, Toast.LENGTH_LONG).show()
                    }
                }
                if (origin.contains("google.com")) {
                    callback.invoke(origin, true, false)
                }
            }
        }

        mapsWebView!!.webViewClient = object : WebViewClient() {
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
                        NavLauncher.launch(this@MainActivity, handoff.target, ComponentName(this@MainActivity, MainActivity::class.java), StartOrigin.parse(view.url), handoff.mode)
                    }
                    Log.d(TAG, "[shouldOverrideUrlLoading][START] Handled (parsed=${handoff != null}), keeping the route page")
                    return true
                }
                if (url == "about:blank") return false
                if (url.startsWith("tel:")) {
                    startActivity(Intent(Intent.ACTION_DIAL, request.url))
                    return true
                }
                if (!url.startsWith("https://")) {
                    Log.d(TAG, "[shouldOverrideUrlLoading][NON-HTTPS] Blocked $url")
                    if (url.startsWith("intent://maps.app.goo.gl/?link=")) {
                        // Firebase Dynamic Link — extract the inner Maps URL cleanly
                        url.toUri().getQueryParameter("link")?.let { mapsWebView?.loadUrl(it) }
                    } else if (url.startsWith("http://")) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.title_open_link)
                            .setIcon(R.drawable.ic_warning)
                            .setMessage("${getString(R.string.text_warning_link)}\n\n${getString(R.string.text_open_link, url)}")
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok) { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
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
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.title_open_link)
                        .setMessage(getString(R.string.text_open_link, url))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
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

        if (BuildConfig.DEBUG) startSpikeInstrumentation()

        mapsWebSettings = mapsWebView!!.settings
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING) && Build.VERSION.SDK_INT >= 29) {
            val nightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(mapsWebSettings!!, nightMode)
        }
        @SuppressLint("SetJavaScriptEnabled")
        mapsWebSettings!!.apply {
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
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
        }

        mapsWebView!!.loadUrl(urlToLoad)
    }

    override fun onDestroy() {
        resetWebView(true)
        super.onDestroy()
    }

    private fun block() = WebResourceResponse("text/javascript", "UTF-8", null)

    private fun resetWebView(exit: Boolean) {
        mapsWebView?.apply {
            clearFormData()
            clearHistory()
            clearMatches()
            clearSslPreferences()
            clearCache(true)
        }
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
        if (exit) {
            mapsWebView?.apply {
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            mapsWebSettings?.javaScriptEnabled = false
            mapsWebView = null
        }
    }

    private fun initShareLinkListener() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val listenerRef = AtomicReference<ClipboardManager.OnPrimaryClipChangedListener>()
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (!clipboard.hasPrimaryClip()) return@OnPrimaryClipChangedListener
            val description = clipboard.primaryClipDescription ?: return@OnPrimaryClipChangedListener
            if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) return@OnPrimaryClipChangedListener
            try {
                val copiedText = clipboard.primaryClip?.getItemAt(0)?.text ?: return@OnPrimaryClipChangedListener
                val m = Pattern.compile("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)").matcher(copiedText)
                if (m.find()) {
                    val latlon = "${m.group(1)},${m.group(2)}"
                    clipboard.removePrimaryClipChangedListener(listenerRef.get())
                    clipboard.setPrimaryClip(ClipData.newPlainText("Geo URI", "geo:$latlon?q=$latlon"))
                    Handler(Looper.getMainLooper()).postDelayed({
                        clipboard.addPrimaryClipChangedListener(listenerRef.get())
                    }, 500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing clipboard", e)
            }
        }
        listenerRef.set(listener)
        clipboard.addPrimaryClipChangedListener(listener)
    }

    private fun removeLocationListener() {
        locationManager?.removeUpdates(locationListenerGPS!!)
        locationListenerGPS = null
    }

    // Debug-only instrumentation for the device spikes B0/N0/M0/S0 (see TODO.md "Device spikes").
    private fun startSpikeInstrumentation() {
        WebView.setWebContentsDebuggingEnabled(true)
        val handler = Handler(Looper.getMainLooper())
        var last: String? = null
        handler.post(object : Runnable {
            override fun run() {
                val now = mapsWebView?.url
                if (now != last) {
                    Log.d(SPIKE_TAG, "poll t=${SystemClock.elapsedRealtime()} url=$now")
                    last = now
                }
                handler.postDelayed(this, 250)
            }
        })
    }
}
