package io.github.einekratzekatze.maps.ui.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.einekratzekatze.maps.R
import io.github.einekratzekatze.maps.data.db.ImportantKind
import io.github.einekratzekatze.maps.domain.Distance
import java.util.Locale
import kotlin.math.roundToInt

val CardShape = RoundedCornerShape(20.dp)

/** The expanded card with the Nearby focus (§1, §3.3). */
@Composable
fun NearbyCard(state: BubbleUiState, actions: PlacesBubbleActions, modifier: Modifier = Modifier) {
    val pane = stringResource(R.string.nearby_pane)
    ElevatedCard(
        shape = CardShape,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
        modifier = modifier.testTag(BubbleTestTags.NearbyCard).semantics { paneTitle = pane; isTraversalGroup = true },
    ) {
        Column {
            CardHeader(
                title = stringResource(R.string.nearby_title),
                subtitle = subtitle(state.content),
                onClose = actions::onClose,
            )
            ImportantRow(state.important, actions::onImportantClick)
            HorizontalDivider()
            NearbyBody(state.content, actions, Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
internal fun CardHeader(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.padding(start = if (leading == null) 16.dp else 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag(BubbleTestTags.NearbyCardClose)) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
        }
    }
}

@Composable
private fun subtitle(content: NearbyContent): String? = when (content) {
    is NearbyContent.Places ->
        if (content.truncated) stringResource(R.string.nearby_subtitle_many)
        else pluralStringResource(R.plurals.nearby_subtitle, content.totalInView, content.totalInView)
    NearbyContent.NoneInView -> pluralStringResource(R.plurals.nearby_subtitle, 0, 0)
    else -> null
}

@Composable
fun ImportantRow(items: List<ImportantShortcut>, onClick: (Long) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { item ->
            AssistChip(
                onClick = { onClick(item.id) },
                label = { Text(importantLabel(item)) },
                leadingIcon = { Icon(painterResource(importantIcon(item)), contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag(BubbleTestTags.importantChip(item.id)),
            )
        }
    }
}

@Composable
private fun importantLabel(item: ImportantShortcut): String {
    val name = when (item.kind) {
        ImportantKind.HOME -> stringResource(R.string.important_home)
        ImportantKind.WORK -> stringResource(R.string.important_work)
        ImportantKind.CUSTOM -> item.label.orEmpty()
    }
    if (item.isSet) return name
    return when (item.kind) {
        ImportantKind.HOME -> stringResource(R.string.important_set_home)
        ImportantKind.WORK -> stringResource(R.string.important_set_work)
        ImportantKind.CUSTOM -> stringResource(R.string.important_set_custom, name)
    }
}

private fun importantIcon(item: ImportantShortcut): Int = when {
    !item.isSet -> R.drawable.ic_add_location
    item.kind == ImportantKind.HOME -> R.drawable.ic_home
    item.kind == ImportantKind.WORK -> R.drawable.ic_work
    else -> R.drawable.ic_star
}

@Composable
private fun NearbyBody(content: NearbyContent, actions: PlacesBubbleActions, modifier: Modifier) {
    when (content) {
        NearbyContent.Loading -> EmptyState(stringResource(R.string.nearby_loading))
        NearbyContent.ViewportUnknown -> EmptyState(stringResource(R.string.nearby_unknown))
        NearbyContent.NoSavedPlaces -> EmptyState(stringResource(R.string.nearby_no_places))
        NearbyContent.NoneInView -> EmptyState(stringResource(R.string.nearby_none_in_view))
        is NearbyContent.Places -> LazyColumn(modifier.testTag(BubbleTestTags.NearbyList)) {
            items(content.items, key = { it.id }) { place ->
                PlaceRow(place, onClick = { actions.onPlaceClick(place.id) }, onNavigate = { actions.onNavigateClick(place.id) })
            }
            if (content.truncated || content.totalInView > content.items.size) {
                item(key = "footer") {
                    val text = if (content.truncated) stringResource(R.string.nearby_footer_truncated, content.items.size)
                    else stringResource(R.string.nearby_footer, content.items.size, content.totalInView)
                    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp).testTag(BubbleTestTags.EmptyState),
    )
}

@Composable
fun PlaceRow(place: NearbyPlace, onClick: () -> Unit, onNavigate: () -> Unit) {
    val locale = currentLocale()
    val list = if (place.extraListCount > 0) stringResource(R.string.place_list_more, place.listName, place.extraListCount) else place.listName
    val distance = Distance.format(place.distanceMeters, locale)
    val spoken = "${place.name}, $list, ${spokenDistance(place.distanceMeters, locale)}"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 56.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .testTag(BubbleTestTags.placeRow(place.id))
                .clickable(onClick = onClick)
                .clearAndSetSemantics { contentDescription = spoken; onClick { onClick(); true } }
                .padding(start = 16.dp, end = 8.dp),
        ) {
            ListDot(place.listColor)
            Column(Modifier.padding(start = 12.dp)) {
                Text(place.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.place_secondary, list, distance), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onNavigate, modifier = Modifier.testTag(BubbleTestTags.placeRowNav(place.id))) {
            Icon(painterResource(R.drawable.ic_directions), contentDescription = stringResource(R.string.navigate_to, place.name))
        }
    }
}

@Composable
internal fun ListDot(color: Int) {
    Box(Modifier.size(12.dp).background(Color(color), CircleShape))
}

@Composable
@ReadOnlyComposable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** "350 metres" / "1.2 kilometres" for TalkBack; the visible text says "350 m" (§7). */
@Composable
internal fun spokenDistance(meters: Double, locale: Locale): String {
    val short = Distance.format(meters, locale)
    if (short.endsWith(" km")) return stringResource(R.string.distance_km_spoken, short.removeSuffix(" km"))
    val m = ((meters / 10).roundToInt() * 10).coerceAtLeast(0)
    return pluralStringResource(R.plurals.distance_meters_spoken, m, m)
}
