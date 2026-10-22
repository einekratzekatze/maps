package io.github.einekratzekatze.maps.data.mymaps

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.einekratzekatze.maps.data.repo.ExternalListRepository
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Refreshes one external list ([KEY_LIST_ID]) or all of them (TODO 5.1, 5.3). Network errors
 * retry with backoff (at most [MAX_ATTEMPTS] runs); a bad file or a deleted list fails for good.
 */
@HiltWorker
class ExternalListRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: ExternalListRepository,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ExternalListRefresh"
        const val KEY_LIST_ID = "listId"
        const val MAX_ATTEMPTS = 3
        private const val PERIODIC_NAME = "external-lists-periodic"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** One refresh of [listId] (import follow-up or the manual refresh button); replaces a queued one. */
        fun enqueue(workManager: WorkManager, listId: Long) {
            val request = OneTimeWorkRequestBuilder<ExternalListRefreshWorker>()
                .setConstraints(network)
                .setInputData(workDataOf(KEY_LIST_ID to listId))
                .build()
            workManager.enqueueUniqueWork("external-list-$listId", ExistingWorkPolicy.REPLACE, request)
        }

        /** Settings "refresh interval" (Phase 7): null switches periodic refresh off. */
        fun schedulePeriodic(workManager: WorkManager, intervalDays: Long?) {
            if (intervalDays == null) {
                workManager.cancelUniqueWork(PERIODIC_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<ExternalListRefreshWorker>(intervalDays, TimeUnit.DAYS)
                .setConstraints(network)
                .build()
            workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    override suspend fun doWork(): Result {
        val listId = inputData.getLong(KEY_LIST_ID, -1)
        return try {
            val ids = if (listId >= 0) listOf(listId) else repository.externalListIds()
            for (id in ids) repository.refresh(id)
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "Refresh failed (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } catch (e: KmlParseException) {
            Log.w(TAG, "Refresh got an unreadable KML", e)
            Result.failure()
        } catch (e: NoSuchElementException) {
            Log.w(TAG, "List $listId no longer exists", e)
            Result.failure()
        }
    }
}
