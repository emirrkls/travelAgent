package com.emirrkls.phokarta.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface MutationSyncScheduler { fun schedule() }

@Singleton
class WorkManagerMutationSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : MutationSyncScheduler {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<MutationSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        // If an enqueue races an active drain, retain one successor so a newer Saved generation
        // cannot be stranded after the active worker's eligibility snapshot.
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    companion object { const val WORK_NAME = "phokarta_mutation_sync" }
}
