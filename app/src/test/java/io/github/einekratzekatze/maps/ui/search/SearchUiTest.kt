package io.github.einekratzekatze.maps.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.einekratzekatze.maps.domain.MapMode
import io.github.einekratzekatze.maps.search.SectionKind
import io.github.einekratzekatze.maps.search.Suggestion
import io.github.einekratzekatze.maps.search.SuggestionSection
import io.github.einekratzekatze.maps.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class SearchUiTest {
    @get:Rule val rule = createComposeRule()
    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(SearchUiState(mode = MapMode.Map))

    private val actions = object : SearchActions {
        // Compose reports focused=false once on first composition; the VM ignores it when not editing.
        override fun onFocusChanged(focused: Boolean) {
            if (!focused) return
            calls += "focus true"
            state = state.copy(editing = true)
        }
        override fun onQueryChange(q: String) {
            calls += "query $q"
            state = state.copy(query = q)
        }
        override fun onSubmit() { calls += "submit" }
        override fun onMenuClick() { calls += "menu" }
        override fun onDirectionsBack() { calls += "dirBack" }
    }

    private fun show() = rule.setContent {
        AppTheme {
            Column {
                MapTopBar(state, actions)
                SuggestionPanel(state, onSuggestion = { calls += "pick $it" }, onDeleteRecent = { calls += "delete $it" })
            }
        }
    }

    @Test fun typeAndSubmitWithTheImeAction() {
        show()
        rule.onNodeWithTag(SearchTestTags.Field).performClick()
        rule.onNodeWithTag(SearchTestTags.Field).performTextInput("pizza")
        rule.onNodeWithTag(SearchTestTags.Field).performImeAction()
        assertEquals(listOf("focus true", "query pizza", "submit"), calls)
    }

    @Test fun clearButtonEmptiesTheQuery() {
        state = SearchUiState(mode = MapMode.Map, editing = true, query = "pizza")
        show()
        rule.onNodeWithTag(SearchTestTags.Clear).performClick()
        assertTrue("query " in calls)
    }

    @Test fun suggestionsAndDeleteRecent() {
        state = SearchUiState(
            mode = MapMode.Map, editing = true, query = "caf",
            sections = listOf(
                SuggestionSection(SectionKind.Saved, listOf(Suggestion.SavedPlace(1, "Café Einstein", null, 52.5, 13.4, 0))),
                SuggestionSection(SectionKind.Recent, listOf(Suggestion.Recent(7, "cafe"))),
                SuggestionSection(SectionKind.Google, listOf(Suggestion.Prediction("P", "Café Kranzler", "Berlin"))),
            ),
        )
        show()
        rule.onNodeWithText("Saved places").assertIsDisplayed()
        rule.onNodeWithText("Google").assertIsDisplayed()
        rule.onNodeWithTag(SearchTestTags.saved(1)).performClick()
        rule.onNodeWithTag(SearchTestTags.prediction("P")).performClick()
        rule.onNodeWithTag(SearchTestTags.recentDelete(7)).performClick()
        rule.onNodeWithTag(SearchTestTags.recent(7)).performClick()
        assertEquals(
            listOf(
                "pick ${Suggestion.SavedPlace(1, "Café Einstein", null, 52.5, 13.4, 0)}",
                "pick ${Suggestion.Prediction("P", "Café Kranzler", "Berlin")}",
                "delete 7",
                "pick ${Suggestion.Recent(7, "cafe")}",
            ),
            calls.filter { !it.startsWith("focus") },
        )
    }

    @Test fun googleErrorLine() {
        state = SearchUiState(mode = MapMode.Map, editing = true, autocompleteError = true)
        show()
        rule.onNodeWithTag(SearchTestTags.GoogleError).assertIsDisplayed()
    }

    @Test fun menuAndDirectionsTitle() {
        show()
        rule.onNodeWithTag(SearchTestTags.Menu).performClick()
        state = SearchUiState(mode = MapMode.Directions)
        rule.onNodeWithText("Directions").assertIsDisplayed()
        rule.onNodeWithTag(SearchTestTags.Field).assertDoesNotExist()
        rule.onNodeWithTag(SearchTestTags.DirectionsBack).performClick()
        state = SearchUiState(mode = MapMode.StreetView)
        rule.onNodeWithTag(SearchTestTags.Menu).assertDoesNotExist()
        assertEquals(listOf("menu", "dirBack"), calls)
    }

    @Test fun noPanelWhileIdle() {
        show()
        rule.onNodeWithTag(SearchTestTags.Panel).assertDoesNotExist()
    }
}
