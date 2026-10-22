package io.github.einekratzekatze.maps.data.db

import java.text.Normalizer
import java.util.Locale

object TextNormalizer {
    private val marks = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")

    /** NFD, strip combining marks, lowercase (ROOT), sharp s to "ss", collapse whitespace, trim. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(marks, "")
            .lowercase(Locale.ROOT)
            .replace("ß", "ss")
            .replace(spaces, " ")
            .trim()

    /** Escapes \, % and _ with '\' for use with LIKE ... ESCAPE '\'. */
    fun escapeLike(s: String): String =
        buildString(s.length) {
            for (c in s) {
                if (c == '\\' || c == '%' || c == '_') append('\\')
                append(c)
            }
        }
}
