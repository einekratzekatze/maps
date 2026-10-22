package io.github.einekratzekatze.maps.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val DrivesListIdKey = longPreferencesKey("drives_default_list_id")

/** Uses the shared app DataStore (see DatabaseModule); never creates a DataStore of its own. */
class DataStoreDrivesListIdStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : DrivesListIdStore {
    override suspend fun get(): Long? = dataStore.data.map { it[DrivesListIdKey] }.first()
    override suspend fun set(id: Long) {
        dataStore.edit { it[DrivesListIdKey] = id }
    }
}
