package io.github.einekratzekatze.maps.data.exchange

import java.util.Locale

/**
 * Hand-rolled streaming XML output onto an [Appendable]. Chosen over `XmlSerializer` because it
 * runs on the plain JVM (no Robolectric needed for writer tests), streams without buffering and
 * gives exact control over escaping.
 */
internal class XmlOut(private val out: Appendable) {
    fun raw(s: String) { out.append(s) }

    fun text(s: String) {
        var start = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val repl: String? = when {
                c == '&' -> "&amp;"
                c == '<' -> "&lt;"
                c == '>' -> "&gt;"
                c == '"' -> "&quot;"
                c == '\'' -> "&apos;"
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    i += 2
                    continue
                }
                isValidXmlChar(c) -> null
                else -> "" // invalid in XML 1.0 (control chars, lone surrogates, U+FFFE/FFFF): drop
            }
            if (repl != null) {
                out.append(s, start, i)
                out.append(repl)
                start = i + 1
            }
            i++
        }
        out.append(s, start, s.length)
    }

    fun element(tag: String, value: String?) {
        if (value == null) return
        out.append('<').append(tag).append('>')
        text(value)
        out.append("</").append(tag).append(">\n")
    }

    private fun isValidXmlChar(c: Char): Boolean =
        c == '\t' || c == '\n' || c == '\r' ||
            (c in ' '..'퟿') || (c in ''..'�')
}

/** Locale-independent decimal (never uses a comma or exponent), at most 7 fractional digits. */
internal fun formatCoord(v: Double): String {
    require(v.isFinite()) { "Non-finite coordinate: $v" }
    val s = String.format(Locale.ROOT, "%.7f", v).trimEnd('0').trimEnd('.')
    return if (s == "-0" || s.isEmpty()) "0" else s
}
