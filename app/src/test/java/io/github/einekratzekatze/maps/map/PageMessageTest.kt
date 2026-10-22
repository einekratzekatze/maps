package io.github.einekratzekatze.maps.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric for org.json. */
@RunWith(RobolectricTestRunner::class)
class PageMessageTest {
    @Test fun typedObjectIsAMessage() {
        val json = """{"type":"markerTap","id":7}"""
        assertEquals(PageMessage("markerTap", json), parsePageMessage(json))
    }

    @Test fun everythingElseIsDropped() {
        listOf(null, "", "not json", "[1,2]", """{"id":7}""", """{"type":""}""").forEach {
            assertNull(it, parsePageMessage(it))
        }
    }
}
