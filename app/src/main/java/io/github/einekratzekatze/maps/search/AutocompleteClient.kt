package io.github.einekratzekatze.maps.search

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.einekratzekatze.maps.data.prefs.SearchPrefs
import io.github.einekratzekatze.maps.di.IoDispatcher
import io.github.einekratzekatze.maps.domain.LatLon
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Google place predictions for the search field (custom-search-field.md §4). */
interface AutocompleteClient {
    /** True when a Places API key is configured; without one no request is ever made. */
    val available: Flow<Boolean>
    suspend fun predictions(input: String, bias: LatLon?, radiusMeters: Double, sessionToken: String): Result<List<Suggestion.Prediction>>
}

object NoOpAutocompleteClient : AutocompleteClient {
    override val available: Flow<Boolean> = flowOf(false)
    override suspend fun predictions(input: String, bias: LatLon?, radiusMeters: Double, sessionToken: String) =
        Result.success(emptyList<Suggestion.Prediction>())
}

/** Pure request/response handling for `places:autocomplete` (Places API, new). */
object PlacesAutocomplete {
    const val ENDPOINT = "https://places.googleapis.com/v1/places:autocomplete"
    const val MAX_RADIUS_M = 50_000.0

    fun requestBody(input: String, bias: LatLon?, radiusMeters: Double, sessionToken: String): String {
        val body = JSONObject().put("input", input).put("sessionToken", sessionToken)
        if (bias != null) {
            val center = JSONObject().put("latitude", bias.lat).put("longitude", bias.lon)
            val circle = JSONObject().put("center", center).put("radius", radiusMeters.coerceIn(1.0, MAX_RADIUS_M))
            body.put("locationBias", JSONObject().put("circle", circle))
        }
        return body.toString()
    }

    /** Place predictions only (query predictions are skipped); at most [limit]. Malformed JSON throws [JSONException]. */
    fun parse(json: String, limit: Int): List<Suggestion.Prediction> {
        val suggestions = JSONObject(json).optJSONArray("suggestions") ?: JSONArray()
        val out = mutableListOf<Suggestion.Prediction>()
        for (i in 0 until suggestions.length()) {
            val p = suggestions.optJSONObject(i)?.optJSONObject("placePrediction") ?: continue
            val placeId = p.optString("placeId").takeIf { it.isNotEmpty() } ?: continue
            val structured = p.optJSONObject("structuredFormat")
            val primary = structured?.optJSONObject("mainText")?.optString("text")?.takeIf { it.isNotEmpty() }
                ?: p.optJSONObject("text")?.optString("text")?.takeIf { it.isNotEmpty() }
                ?: continue
            val secondary = structured?.optJSONObject("secondaryText")?.optString("text")?.takeIf { it.isNotEmpty() }
            out += Suggestion.Prediction(placeId, primary, secondary)
            if (out.size == limit) break
        }
        return out
    }
}

/**
 * Calls the Places API with the user's own key. The key is restricted to this app in the
 * Cloud console; over REST that restriction needs the package and signing-cert headers.
 * The key is never logged.
 */
class PlacesAutocompleteClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SearchPrefs,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AutocompleteClient {
    companion object {
        private const val TAG = "Autocomplete"
        private const val TIMEOUT_MS = 5_000
        const val LIMIT = 5
    }

    override val available: Flow<Boolean> = prefs.placesApiKey.map { !it.isNullOrBlank() }

    private val certSha1: String? by lazy { signingCertSha1() }

    override suspend fun predictions(input: String, bias: LatLon?, radiusMeters: Double, sessionToken: String): Result<List<Suggestion.Prediction>> {
        val key = prefs.placesApiKey.first()?.takeIf { it.isNotBlank() } ?: return Result.success(emptyList())
        return withContext(io) {
            try {
                val conn = URL(PlacesAutocomplete.ENDPOINT).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("X-Goog-Api-Key", key)
                    conn.setRequestProperty("X-Android-Package", context.packageName)
                    certSha1?.let { conn.setRequestProperty("X-Android-Cert", it) }
                    conn.outputStream.use { it.write(PlacesAutocomplete.requestBody(input, bias, radiusMeters, sessionToken).toByteArray()) }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        Log.w(TAG, "Places autocomplete failed with HTTP $code")
                        return@withContext Result.failure(IOException("HTTP $code"))
                    }
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    Result.success(PlacesAutocomplete.parse(text, LIMIT))
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                Log.w(TAG, "Places autocomplete failed", e)
                Result.failure(e)
            } catch (e: JSONException) {
                Log.w(TAG, "Places autocomplete returned malformed JSON", e)
                Result.failure(e)
            }
        }
    }

    /** SHA-1 of the signing certificate, upper-case hex without colons. */
    @Suppress("DEPRECATION")
    private fun signingCertSha1(): String? = try {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures?.firstOrNull()?.toByteArray()?.let { cert ->
            MessageDigest.getInstance("SHA-1").digest(cert).joinToString("") { "%02X".format(it) }
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
