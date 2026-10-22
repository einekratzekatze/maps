package io.github.einekratzekatze.maps.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MapsLanguageTest {
    @Test fun extract() {
        assertEquals("de", MapsLanguage.extract("https://www.google.com/maps/@52.5,13.4,12z?hl=de"))
        assertEquals("en-us", MapsLanguage.extract("https://www.google.com/maps?entry=x&HL=en-US".replace("HL", "hl")))
        assertNull(MapsLanguage.extract("https://www.google.com/maps?shl=de"))
        assertNull(MapsLanguage.extract(null))
    }

    @Test fun withLanguage() {
        assertEquals("https://www.google.com/maps?hl=de", MapsLanguage.withLanguage("https://www.google.com/maps", "de"))
        assertEquals("https://x/maps?q=a&hl=de", MapsLanguage.withLanguage("https://x/maps?q=a", "de"))
        assertEquals("https://x/maps?hl=fr", MapsLanguage.withLanguage("https://x/maps?hl=fr", "de"))
        assertEquals("https://x/maps", MapsLanguage.withLanguage("https://x/maps", null))
    }

    @Test fun saveAndReadBack() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertNull(MapsLanguage.saved(ctx))
        MapsLanguage.save(ctx, "https://www.google.com/maps")
        assertNull(MapsLanguage.saved(ctx))
        MapsLanguage.save(ctx, "https://www.google.com/maps?hl=de")
        assertEquals("de", MapsLanguage.saved(ctx))
    }
}
