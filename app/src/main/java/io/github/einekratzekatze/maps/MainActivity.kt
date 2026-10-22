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
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.einekratzekatze.maps.data.DebugSeeder
import io.github.einekratzekatze.maps.map.MapCommands
import io.github.einekratzekatze.maps.map.MapStateRepository
import io.github.einekratzekatze.maps.map.MapsLanguage
import io.github.einekratzekatze.maps.map.PageScript
import io.github.einekratzekatze.maps.map.WebSessionViewModel
import io.github.einekratzekatze.maps.map.WebViewHost
import io.github.einekratzekatze.maps.ui.AppRoot
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Lifecycle glue only; the WebView lives in [WebViewHost] (compose-host.md §1, §2). */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var mapState: MapStateRepository
    @Inject lateinit var commands: MapCommands
    @Inject lateinit var pageScripts: Set<@JvmSuppressWildcards PageScript>
    @Inject lateinit var debugSeeder: DebugSeeder
    private lateinit var host: WebViewHost
    private val session: WebSessionViewModel by viewModels()
    private var locationManager: LocationManager? = null
    private var locationListenerGPS: LocationListener? = null
    private val spikeHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "GMapsWV"
        private const val SPIKE_TAG = "SPIKE"
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
        host.updateDarkening(resources.configuration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // A configuration-change recreation keeps cookies and reloads the last page (§2.3).
        val recreated = session.started
        session.started = true
        host = WebViewHost(this, freshLaunch = !recreated, mapState, pageScripts)

        initShareLinkListener()

        // Back navigation — goes back in WebView history before closing the app.
        // Registered before setContent so any enabled Compose BackHandler wins over it (§7.4).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!host.goBackInHistory()) finish()
            }
        })

        if (BuildConfig.DEBUG) startSpikeInstrumentation()
        if (BuildConfig.DEBUG && !recreated && intent.getBooleanExtra(DebugSeeder.EXTRA_SEED, false)) {
            val at = DebugSeeder.parseAt(intent.getStringExtra(DebugSeeder.EXTRA_AT))
            lifecycleScope.launch { debugSeeder.seed(at) }
        }

        setContent { AppRoot(host, mapState, commands) }

        // mapState.url is null when the recreation happened mid-load; the start URL is the fallback.
        host.loadUrl(mapState.url.value.takeIf { recreated } ?: urlFromIntent())
    }

    override fun onDestroy() {
        spikeHandler.removeCallbacksAndMessages(null)
        host.destroy(wipe = !isChangingConfigurations)
        super.onDestroy()
    }

    private fun urlFromIntent(): String {
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
        return MapsLanguage.withLanguage(urlToLoad, MapsLanguage.saved(this))
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
        var last: String? = null
        spikeHandler.post(object : Runnable {
            override fun run() {
                val now = host.webView.url
                if (now != last) {
                    Log.d(SPIKE_TAG, "poll t=${SystemClock.elapsedRealtime()} url=$now")
                    last = now
                }
                spikeHandler.postDelayed(this, 250)
            }
        })
    }
}
