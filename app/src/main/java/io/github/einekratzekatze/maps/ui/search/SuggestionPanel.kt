package io.github.einekratzekatze.maps.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.einekratzekatze.maps.R
import io.github.einekratzekatze.maps.search.SectionKind
import io.github.einekratzekatze.maps.search.Suggestion

/**
 * Suggestions under the top bar while editing (custom-search-field.md §3). A plain composable in
 * the MapScreen Box, not a Popup/DropdownMenu, so focus and the IME stay with the field.
 */
@Composable
fun SuggestionPanel(
    state: SearchUiState,
    onSuggestion: (Suggestion) -> Unit,
    onDeleteRecent: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.editing) return
    Surface(tonalElevation = 2.dp, shadowElevation = 4.dp, modifier = modifier.fillMaxWidth().testTag(SearchTestTags.Panel)) {
        LazyColumn {
            for (section in state.sections) {
                item(key = "header_${section.kind}") { SectionHeader(section.kind) }
                items(section.items, key = { keyOf(it) }) { s -> SuggestionRow(s, onSuggestion, onDeleteRecent) }
            }
            if (state.autocompleteError) {
                item(key = "google_error") {
                    Text(
                        stringResource(R.string.search_google_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp).testTag(SearchTestTags.GoogleError),
                    )
                }
            }
        }
    }
}

private fun keyOf(s: Suggestion): String = when (s) {
    is Suggestion.SavedPlace -> "s${s.id}"
    is Suggestion.Recent -> "r${s.id}"
    is Suggestion.Prediction -> "p${s.placeId}"
}

@Composable
private fun SectionHeader(kind: SectionKind) {
    val text = when (kind) {
        SectionKind.Saved -> stringResource(R.string.search_section_saved)
        SectionKind.Recent -> stringResource(R.string.search_section_recent)
        SectionKind.Google -> stringResource(R.string.search_section_google)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun SuggestionRow(s: Suggestion, onSuggestion: (Suggestion) -> Unit, onDeleteRecent: (Long) -> Unit) {
    val tag = when (s) {
        is Suggestion.SavedPlace -> SearchTestTags.saved(s.id)
        is Suggestion.Recent -> SearchTestTags.recent(s.id)
        is Suggestion.Prediction -> SearchTestTags.prediction(s.placeId)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(tag).clickable { onSuggestion(s) }.padding(horizontal = 16.dp),
        ) {
            if (s is Suggestion.SavedPlace) {
                Box(Modifier.size(10.dp).background(Color(s.listColor), CircleShape))
            }
            Column(Modifier.padding(start = if (s is Suggestion.SavedPlace) 12.dp else 0.dp)) {
                val (primary, secondary) = when (s) {
                    is Suggestion.SavedPlace -> s.name to s.address
                    is Suggestion.Recent -> s.query to null
                    is Suggestion.Prediction -> s.primary to s.secondary
                }
                Text(primary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (secondary != null) Text(secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (s is Suggestion.Recent) {
            IconButton(onClick = { onDeleteRecent(s.id) }, modifier = Modifier.testTag(SearchTestTags.recentDelete(s.id))) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_delete_recent, s.query))
            }
        }
    }
}
