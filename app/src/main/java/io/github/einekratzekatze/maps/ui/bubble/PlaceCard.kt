package io.github.einekratzekatze.maps.ui.bubble

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.einekratzekatze.maps.R
import io.github.einekratzekatze.maps.domain.Distance

/** The expanded card focused on one place, opened by a marker tap (§5.8). [state] null = loading. */
@Composable
fun PlaceCard(state: PlaceCardState?, actions: PlacesBubbleActions, modifier: Modifier = Modifier) {
    val title = state?.name ?: stringResource(R.string.nearby_loading)
    ElevatedCard(
        shape = CardShape,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
        modifier = modifier.testTag(BubbleTestTags.PlaceCard).semantics { paneTitle = title; isTraversalGroup = true },
    ) {
        Column {
            CardHeader(
                title = title,
                subtitle = null,
                onClose = actions::onClose,
                leading = {
                    IconButton(onClick = actions::onShowAllNearby, modifier = Modifier.testTag(BubbleTestTags.PlaceCardBack)) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.show_all_nearby))
                    }
                },
            )
            if (state != null) PlaceDetails(state, actions)
        }
    }
}

@Composable
private fun PlaceDetails(state: PlaceCardState, actions: PlacesBubbleActions) {
    val locale = currentLocale()
    var notesExpanded by rememberSaveable(state.id) { mutableStateOf(false) }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ListDot(state.listColor)
            Text(state.listName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
            state.distanceMeters?.let {
                Text(" · " + Distance.format(it, locale), style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.address?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) } }
        state.notes?.let { notes ->
            Text(notes, style = MaterialTheme.typography.bodySmall, maxLines = if (notesExpanded) Int.MAX_VALUE else 6)
            if (!notesExpanded && notes.lines().size > 6 || !notesExpanded && notes.length > 300) {
                TextButton(onClick = { notesExpanded = true }) { Text(stringResource(R.string.more)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { actions.onNavigateClick(state.id) }) { Text(stringResource(R.string.navigate)) }
            OutlinedButton(onClick = { actions.onPlaceClick(state.id) }) { Text(stringResource(R.string.show_on_map)) }
        }
    }
}
