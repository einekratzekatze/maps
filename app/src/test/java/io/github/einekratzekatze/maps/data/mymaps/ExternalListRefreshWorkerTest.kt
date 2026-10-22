package io.github.einekratzekatze.maps.data.mymaps

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.einekratzekatze.maps.data.db.DbTestBase
import io.github.einekratzekatze.maps.data.repo.ExternalListLabels
import io.github.einekratzekatze.maps.data.repo.ExternalListRepository
import io.github.einekratzekatze.maps.data.repo.ImportResult
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalListRefreshWorkerTest : DbTestBase() {
    private var body = "<kml><Document><Placemark><name>a</name><Point><coordinates>1,2</coordinates></Point></Placemark></Document></kml>"
    private var error: IOException? = null
    private val fetcher = object : KmlFetcher {
        override suspend fun fetch(url: String): String { error?.let { throw it }; return body }
    }
    private lateinit var repo: ExternalListRepository
    private var listId = 0L

    @Before fun importOne() = runBlocking {
        repo = ExternalListRepository(db, fetcher, ExternalListLabels("L", "S", "P"), Dispatchers.Unconfined, Clock.systemUTC())
        listId = (repo.import("https://www.google.com/maps/d/viewer?mid=abc", 0) as ImportResult.Imported).listId
    }

    private fun run(attempt: Int = 0, id: Long? = listId): ListenableWorker.Result = runBlocking {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                ExternalListRefreshWorker(appContext, workerParameters, repo)
        }
        TestListenableWorkerBuilder<ExternalListRefreshWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(factory)
            .setRunAttemptCount(attempt)
            .setInputData(if (id == null) workDataOf() else workDataOf(ExternalListRefreshWorker.KEY_LIST_ID to id))
            .build()
            .doWork()
    }

    @Test fun successForOneAndForAll() {
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(ListenableWorker.Result.success(), run(id = null))
    }

    @Test fun networkErrorsRetryThenFail() {
        error = IOException("offline")
        assertEquals(ListenableWorker.Result.retry(), run(attempt = 0))
        assertEquals(ListenableWorker.Result.retry(), run(attempt = 1))
        assertEquals(ListenableWorker.Result.failure(), run(attempt = 2))
    }

    @Test fun badFileOrMissingListFails() {
        body = "garbage"
        assertEquals(ListenableWorker.Result.failure(), run())
        assertEquals(ListenableWorker.Result.failure(), run(id = 999))
    }
}
