package io.github.einekratzekatze.maps.data.repo

import androidx.room.withTransaction
import io.github.einekratzekatze.maps.data.db.AppDatabase
import io.github.einekratzekatze.maps.data.db.ExternalList
import io.github.einekratzekatze.maps.data.db.ExternalSublist
import io.github.einekratzekatze.maps.data.db.ExternalSublistContent
import io.github.einekratzekatze.maps.data.db.ListType
import io.github.einekratzekatze.maps.data.db.Place
import io.github.einekratzekatze.maps.data.db.PlaceList
import io.github.einekratzekatze.maps.data.db.TextNormalizer
import io.github.einekratzekatze.maps.data.mymaps.KmlFetcher
import io.github.einekratzekatze.maps.data.mymaps.KmlParser
import io.github.einekratzekatze.maps.data.mymaps.MyMapsUrl
import io.github.einekratzekatze.maps.data.mymaps.ParsedMyMap
import io.github.einekratzekatze.maps.di.IoDispatcher
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Localised fallbacks for names the KML leaves blank (the columns are non-null). */
data class ExternalListLabels(val list: String, val sublist: String, val place: String)

sealed interface ImportResult {
    data class Imported(val listId: Long, val placeCount: Int) : ImportResult
    data class AlreadyImported(val listId: Long) : ImportResult
    data object NotAMyMapsUrl : ImportResult
}

/**
 * Google My Maps lists (TODO 5.1-5.3): import by URL, refresh, sublist visibility. Content is
 * read-only; a refresh replaces it atomically. Network and parse errors are thrown
 * ([java.io.IOException], [io.github.einekratzekatze.maps.data.mymaps.KmlParseException]) and
 * leave the database unchanged.
 */
class ExternalListRepository @Inject constructor(
    private val db: AppDatabase,
    private val fetcher: KmlFetcher,
    private val labels: ExternalListLabels,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) {
    private val dao get() = db.externalListDao()

    suspend fun import(pastedUrl: String, color: Int): ImportResult {
        val mapId = MyMapsUrl.extractMapId(pastedUrl) ?: return ImportResult.NotAMyMapsUrl
        withContext(io) { dao.getByMapId(mapId) }?.let { return ImportResult.AlreadyImported(it.listId) }
        val kml = fetcher.fetch(MyMapsUrl.kmlUrl(mapId))
        val parsed = KmlParser.parse(kml) // before any write: a bad file leaves nothing behind
        return withContext(io) {
            db.withTransaction {
                val now = clock.millis()
                val listId = db.placeListDao().insert(
                    PlaceList(name = parsed.name ?: labels.list, color = color, sortOrder = 0, type = ListType.EXTERNAL, createdAt = now),
                )
                val externalId = dao.insertExternal(ExternalList(listId = listId, mapId = mapId, sourceUrl = pastedUrl.trim()))
                dao.replaceContent(externalId, listId, content(parsed, now), kml, now)
                ImportResult.Imported(listId, parsed.placemarkCount)
            }
        }
    }

    /** Re-fetches one external list; returns the new place count. */
    suspend fun refresh(listId: Long): Int {
        val external = withContext(io) { dao.getByList(listId) } ?: throw NoSuchElementException("no external list $listId")
        val kml = fetcher.fetch(MyMapsUrl.kmlUrl(external.mapId))
        val parsed = KmlParser.parse(kml)
        withContext(io) {
            val now = clock.millis()
            dao.replaceContent(external.id, listId, content(parsed, now), kml, now)
        }
        return parsed.placemarkCount
    }

    suspend fun externalListIds(): List<Long> = withContext(io) { dao.getAll().map { it.listId } }

    suspend fun getByList(listId: Long): ExternalList? = withContext(io) { dao.getByList(listId) }

    fun observeSublists(externalListId: Long): Flow<List<ExternalSublist>> = dao.observeSublists(externalListId)

    suspend fun setSublistVisible(sublistId: Long, visible: Boolean) = withContext(io) { dao.setSublistVisible(sublistId, visible) }

    fun observeAllHidden(externalListId: Long): Flow<Boolean> = dao.observeAllHidden(externalListId)

    private fun content(parsed: ParsedMyMap, now: Long): List<ExternalSublistContent> =
        parsed.sublists.map { sub ->
            ExternalSublistContent(
                name = sub.name ?: labels.sublist,
                places = sub.placemarks.map { pm ->
                    val p = pm.position.normalizedForStorage()
                    val name = pm.name ?: labels.place
                    Place(
                        listId = 0, name = name, nameNormalized = TextNormalizer.normalize(name),
                        lat = p.lat, lon = p.lon, notes = pm.description, createdAt = now,
                    )
                },
            )
        }
}
