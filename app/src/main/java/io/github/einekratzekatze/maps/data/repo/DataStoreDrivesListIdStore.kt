package io.github.einekratzekatze.maps.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.drivesDataStore by preferencesDataStore(name = "drives")
private val DrivesListIdKey = longPreferencesKey("drives_default_list_id")

class DataStoreDrivesListIdStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : DrivesListIdStore {
    override suspend fun get(): Long? = context.drivesDataStore.data.map { it[DrivesListIdKey] }.first()
    override suspend fun set(id: Long) {
        context.drivesDataStore.edit { it[DrivesListIdKey] = id }
    }
}
