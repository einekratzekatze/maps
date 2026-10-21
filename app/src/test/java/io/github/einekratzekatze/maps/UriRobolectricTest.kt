package io.github.einekratzekatze.maps

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UriRobolectricTest {
    @Test
    fun parsesMapsUri() {
        val uri = Uri.parse("https://maps.google.com/maps?q=Marienplatz")
        assertEquals("maps.google.com", uri.host)
        assertEquals("Marienplatz", uri.getQueryParameter("q"))
    }
}
