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

class RecentSearchRepository @Inject constructor(
    private val db: AppDatabase,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) {
    private val dao get() = db.recentSearchDao()

    /** Stores a submitted query (trimmed; blank is ignored). A repeat moves to the top; at most [keep] rows remain. */
    suspend fun record(query: String, keep: Int = MAX_RECENT) = withContext(io) {
        val trimmed = query.trim()
        val normalized = TextNormalizer.normalize(trimmed)
        if (normalized.isEmpty()) return@withContext
        dao.record(RecentSearch(query = trimmed, queryNormalized = normalized, timestamp = clock.millis()), keep)
    }

    fun observeRecent(limit: Int = MAX_RECENT): Flow<List<RecentSearch>> = dao.observeRecent(limit)

    suspend fun search(query: String, limit: Int = MAX_RECENT): List<RecentSearch> = withContext(io) {
        dao.search("%${TextNormalizer.escapeLike(TextNormalizer.normalize(query))}%", limit)
    }

    suspend fun delete(id: Long) = withContext(io) { dao.delete(id) }

    companion object { const val MAX_RECENT = 20 }
}
