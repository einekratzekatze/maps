package io.github.einekratzekatze.maps.navigation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StartPressTest {
    private val fixtures = javaClass.getResourceAsStream("/nav-fixtures.txt")!!.bufferedReader().readLines()
        .filter { !it.startsWith("#") && it.isNotBlank() }.map { JSONObject(it) }

    @Test fun capturedStartPressesAreDetectedOnBothNavigations() {
        val starts = fixtures.filter { it.getString("current").contains("/maps/dir/") }
        assertEquals(6, starts.size) // three Start presses x (https + intent)
        starts.forEach { assertTrue(it.getString("t"), StartPress.isStart(it.getString("url"), it.getString("current"))) }
    }

    @Test fun capturedDirectionsPressIsNotAStart() {
        val directions = fixtures.filter { it.getString("current").contains("/maps/place/") }
        assertEquals(2, directions.size)
        directions.forEach { assertFalse(StartPress.isStart(it.getString("url"), it.getString("current"))) }
    }

    @Test fun otherUrlsAndStatesAreNotStarts() {
        val route = "https://www.google.com/maps/dir/A/B/@52.5,13.4,13z/data=!3e0"
        assertFalse(StartPress.isStart("https://www.google.com/maps/dir/A/B/", route))
        assertFalse(StartPress.isStart("https://maps.app.goo.gl/?link=x", null))
        assertFalse(StartPress.isStart("https://maps.app.goo.gl/?link=x", "https://www.google.com/maps/dir//B/@52.5,13.4,13z"))
        assertFalse(StartPress.isStart("https://maps.app.goo.gl/?link=x", "https://www.google.com/maps/dir/A//@52.5,13.4,13z"))
        assertFalse(StartPress.isStart("https://maps.app.goo.gl/?link=x", "https://www.google.com/maps/@52.5,13.4,13z"))
        assertFalse(StartPress.isStart("https://maps.app.goo.gl/?link=x", "https://evil.example/maps/dir/A/B"))
        assertTrue(StartPress.isStart("https://maps.app.goo.gl/?link=x", route))
        assertTrue(StartPress.isStart("intent://maps.app.goo.gl/?link=x", route))
    }
}
