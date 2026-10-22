package io.github.einekratzekatze.maps.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.einekratzekatze.maps.ui.bubble.BubbleAnchor
import io.github.einekratzekatze.maps.ui.bubble.EdgeSide
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Bubble position and on/off switch (floating-places-bubble.md §4.6). */
interface BubblePrefs {
    /** Missing keys map to [BubbleAnchor.Default]. */
    val anchor: Flow<BubbleAnchor>
    val enabled: Flow<Boolean>
    suspend fun setAnchor(anchor: BubbleAnchor)
    suspend fun setEnabled(enabled: Boolean)
}

private val SideKey = stringPreferencesKey("bubble_side")
private val YFractionKey = floatPreferencesKey("bubble_y_fraction")
private val EnabledKey = booleanPreferencesKey("bubble_enabled")

/** Uses the shared app DataStore (see DatabaseModule). */
class DataStoreBubblePrefs @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : BubblePrefs {
    override val anchor: Flow<BubbleAnchor> = dataStore.data.map { p ->
        val side = EdgeSide.entries.firstOrNull { it.name == p[SideKey] } ?: BubbleAnchor.Default.side
        val y = p[YFractionKey]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: BubbleAnchor.Default.yFraction
        BubbleAnchor(side, y)
    }.distinctUntilChanged()

    override val enabled: Flow<Boolean> = dataStore.data.map { it[EnabledKey] ?: true }.distinctUntilChanged()

    override suspend fun setAnchor(anchor: BubbleAnchor) {
        dataStore.edit {
            it[SideKey] = anchor.side.name
            it[YFractionKey] = anchor.yFraction
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[EnabledKey] = enabled }
    }
}
