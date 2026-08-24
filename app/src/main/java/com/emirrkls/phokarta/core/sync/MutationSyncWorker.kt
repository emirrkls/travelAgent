package com.emirrkls.phokarta.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class MutationSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: MutationSyncEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val result = engine.drain()
        return if (result.retryableFailure) Result.retry() else Result.success()
    }
}
