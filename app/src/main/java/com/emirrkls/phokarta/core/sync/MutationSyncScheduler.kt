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
        // A new trigger (especially a fresh login) must supersede an old worker in exponential
        // backoff. Interrupted SYNCING rows are recovered at the start of the replacement drain.
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object { const val WORK_NAME = "phokarta_mutation_sync" }
}
