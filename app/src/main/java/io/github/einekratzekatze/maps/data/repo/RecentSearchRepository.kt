package io.github.einekratzekatze.maps.data.repo

import io.github.einekratzekatze.maps.data.db.AppDatabase
import io.github.einekratzekatze.maps.data.db.RecentSearch
import io.github.einekratzekatze.maps.data.db.TextNormalizer
import io.github.einekratzekatze.maps.di.IoDispatcher
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** What the search field needs from the recent searches; an interface so its tests can use a fake. */
interface RecentSearches {
    suspend fun record(query: String, keep: Int = RecentSearchRepository.MAX_RECENT)
    fun observeRecent(limit: Int = RecentSearchRepository.MAX_RECENT): Flow<List<RecentSearch>>
    suspend fun search(query: String, limit: Int = RecentSearchRepository.MAX_RECENT): List<RecentSearch>
    suspend fun delete(id: Long)
}

class RecentSearchRepository @Inject constructor(
    private val db: AppDatabase,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) : RecentSearches {
    private val dao get() = db.recentSearchDao()

    /** Stores a submitted query (trimmed; blank is ignored). A repeat moves to the top; at most [keep] rows remain. */
    override suspend fun record(query: String, keep: Int) = withContext(io) {
        require(keep > 0) { "keep must be positive" }
        val trimmed = query.trim()
        val normalized = TextNormalizer.normalize(trimmed)
        if (normalized.isEmpty()) return@withContext
        dao.record(RecentSearch(query = trimmed, queryNormalized = normalized, timestamp = clock.millis()), keep)
    }

    override fun observeRecent(limit: Int): Flow<List<RecentSearch>> {
        require(limit > 0) { "limit must be positive" }
        return dao.observeRecent(limit)
    }

    /** A blank query returns no entries, like [PlacesRepository.search]; use [observeRecent] for the empty field. */
    override suspend fun search(query: String, limit: Int): List<RecentSearch> = withContext(io) {
        require(limit > 0) { "limit must be positive" }
        if (TextNormalizer.normalize(query).isEmpty()) return@withContext emptyList()
        dao.search("%${TextNormalizer.escapeLike(TextNormalizer.normalize(query))}%", limit)
    }

    override suspend fun delete(id: Long) = withContext(io) { dao.delete(id) }

    companion object { const val MAX_RECENT = 20 }
}
