package io.github.einekratzekatze.maps.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The user's own Google Places API key (custom-search-field.md §4). Entered in Settings (S10). */
interface SearchPrefs {
    val placesApiKey: Flow<String?>
    /** Blank clears the key. */
    suspend fun setPlacesApiKey(key: String?)
}

private val ApiKeyKey = stringPreferencesKey("places_api_key")

class DataStoreSearchPrefs @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SearchPrefs {
    override val placesApiKey: Flow<String?> = dataStore.data.map { it[ApiKeyKey] }.distinctUntilChanged()

    override suspend fun setPlacesApiKey(key: String?) {
        dataStore.edit {
            val trimmed = key?.trim()
            if (trimmed.isNullOrEmpty()) it.remove(ApiKeyKey) else it[ApiKeyKey] = trimmed
        }
    }
}
