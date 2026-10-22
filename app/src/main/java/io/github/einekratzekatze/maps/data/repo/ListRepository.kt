package io.github.einekratzekatze.maps.data.repo

import io.github.einekratzekatze.maps.data.db.AppDatabase
import io.github.einekratzekatze.maps.data.db.ListType
import io.github.einekratzekatze.maps.data.db.ListWithCount
import io.github.einekratzekatze.maps.data.db.PlaceList
import io.github.einekratzekatze.maps.di.IoDispatcher
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Persists the id of the "Drives" list (DataStore key `drives_default_list_id` in production). */
interface DrivesListIdStore {
    suspend fun get(): Long?
    suspend fun set(id: Long)
}

@Singleton
class ListRepository @Inject constructor(
    private val db: AppDatabase,
    private val drivesListIdStore: DrivesListIdStore,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) {
    private val lists get() = db.placeListDao()
    private val drivesListLock = Mutex()

    fun observeAllWithCounts(): Flow<List<ListWithCount>> = lists.observeAllWithCounts()
    fun observeWritable(): Flow<List<PlaceList>> = lists.observeWritable()

    suspend fun get(id: Long): PlaceList? = withContext(io) { lists.get(id) }

    suspend fun create(name: String, color: Int, type: ListType = ListType.NORMAL, sortOrder: Int = 0): Long =
        withContext(io) {
            lists.insert(PlaceList(name = name, color = color, sortOrder = sortOrder, type = type, createdAt = clock.millis()))
        }

    suspend fun update(list: PlaceList) = withContext(io) { lists.update(list) }

    /** Cascades to the list's places and drives. */
    suspend fun delete(id: Long) = withContext(io) { lists.delete(id) }

    /**
     * The id of the "Drives" list: the stored id if that list still exists, else a new NORMAL list
     * named [defaultName] (names are localised, so the caller passes it) whose id is stored.
     */
    suspend fun getOrCreateDrivesList(defaultName: String, color: Int): Long = withContext(io) {
        drivesListLock.withLock {
        val stored = drivesListIdStore.get()
        if (stored != null && lists.get(stored)?.type == ListType.NORMAL) return@withLock stored
        val id = lists.insert(
            PlaceList(name = defaultName, color = color, sortOrder = 0, type = ListType.NORMAL, createdAt = clock.millis()),
        )
        drivesListIdStore.set(id)
        id
    }
    }
}
