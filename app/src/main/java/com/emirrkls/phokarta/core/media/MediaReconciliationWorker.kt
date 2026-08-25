package com.emirrkls.phokarta.core.media

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class MediaReconciliationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reconciler: MediaFileReconciler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        reconciler.reconcile()
        Result.success()
    }.getOrElse { Result.retry() }
}
