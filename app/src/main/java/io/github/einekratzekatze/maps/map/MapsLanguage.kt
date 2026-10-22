package io.github.einekratzekatze.maps.map

import android.content.Context

/**
 * Remembers the GMaps UI language (`hl=`) the user picked and reuses it on the next start
 * (ported from upstream V4.9 "save language setting"). Stored in SharedPreferences, not in the
 * WebView, so the per-launch cookie wipe does not reset it.
 */
object MapsLanguage {
    private const val PREF_FILE = "maps_prefs"
    private const val KEY_LANGUAGE = "saved_language"
    private val HL = Regex("[?&]hl=([a-zA-Z]{2,}(?:-[a-zA-Z]{2})?)")

    /** The `hl` value of [url], lower-cased, or null. */
    fun extract(url: String?): String? = url?.let { HL.find(it) }?.groupValues?.get(1)?.lowercase()

    /** Appends `hl=<language>` unless [url] already has an `hl` parameter or [language] is null. */
    fun withLanguage(url: String, language: String?): String {
        if (language == null || url.contains("?hl=") || url.contains("&hl=")) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}hl=$language"
    }

    fun save(context: Context, url: String?) {
        val lang = extract(url) ?: return
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, lang).apply()
    }

    fun saved(context: Context): String? =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)
}
