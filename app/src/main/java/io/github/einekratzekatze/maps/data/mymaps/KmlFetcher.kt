package io.github.einekratzekatze.maps.data.mymaps

import io.github.einekratzekatze.maps.di.IoDispatcher
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Downloads a KML document; an interface so the repository tests run without network. */
interface KmlFetcher {
    /** @throws IOException on network errors, non-2xx answers or oversized bodies. */
    suspend fun fetch(url: String): String
}

class HttpKmlFetcher @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) : KmlFetcher {
    companion object {
        private const val TIMEOUT_MS = 15_000
        /** Larger exports are refused rather than risking memory and the Room cursor window (schema.md §1.5). */
        const val MAX_BYTES = 8 * 1024 * 1024
    }

    override suspend fun fetch(url: String): String = withContext(io) {
        require(url.startsWith("https://")) { "KML must be fetched over HTTPS" }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw IOException("KML larger than $MAX_BYTES bytes")
                }
                out.toByteArray()
            }
            String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }
}
