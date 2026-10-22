package io.github.einekratzekatze.maps.search

import io.github.einekratzekatze.maps.domain.LatLon
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric for org.json. */
@RunWith(RobolectricTestRunner::class)
class PlacesAutocompleteTest {
    @Test fun requestWithBias() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val body = JSONObject(PlacesAutocomplete.requestBody("caf", LatLon(52.52, 13.405), 1234.5, "tok"))
            assertEquals("caf", body.getString("input"))
            assertEquals("tok", body.getString("sessionToken"))
            val circle = body.getJSONObject("locationBias").getJSONObject("circle")
            assertEquals(52.52, circle.getJSONObject("center").getDouble("latitude"), 0.0)
            assertEquals(13.405, circle.getJSONObject("center").getDouble("longitude"), 0.0)
            assertEquals(1234.5, circle.getDouble("radius"), 0.0)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun radiusIsCappedAndBiasOptional() {
        val capped = JSONObject(PlacesAutocomplete.requestBody("x", LatLon(0.0, 0.0), 1e9, "t"))
        assertEquals(50_000.0, capped.getJSONObject("locationBias").getJSONObject("circle").getDouble("radius"), 0.0)
        assertFalse(JSONObject(PlacesAutocomplete.requestBody("x", null, 10.0, "t")).has("locationBias"))
    }

    @Test fun parsesPlacePredictions() {
        val json = """
            {"suggestions": [
              {"placePrediction": {"placeId": "A", "text": {"text": "Café Einstein, Berlin"},
                "structuredFormat": {"mainText": {"text": "Café Einstein"}, "secondaryText": {"text": "Unter den Linden, Berlin"}}}},
              {"queryPrediction": {"text": {"text": "cafe near me"}}},
              {"placePrediction": {"placeId": "B", "text": {"text": "Cafe Kranzler"}}},
              {"placePrediction": {"text": {"text": "no id"}}}
            ]}
        """.trimIndent()
        assertEquals(
            listOf(
                Suggestion.Prediction("A", "Café Einstein", "Unter den Linden, Berlin"),
                Suggestion.Prediction("B", "Cafe Kranzler", null),
            ),
            PlacesAutocomplete.parse(json, limit = 5),
        )
        assertEquals(1, PlacesAutocomplete.parse(json, limit = 1).size)
    }

    @Test fun emptyAndMalformedResponses() {
        assertEquals(emptyList<Suggestion.Prediction>(), PlacesAutocomplete.parse("{}", 5))
        assertThrows(org.json.JSONException::class.java) { PlacesAutocomplete.parse("not json", 5) }
    }
}
