package com.emirrkls.phokarta

import android.app.Application
import com.emirrkls.phokarta.core.auth.AuthState
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.data.VisitDraftRepository
import dagger.hilt.android.HiltAndroidApp
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.emirrkls.phokarta.core.media.MediaFileReconciler
import com.emirrkls.phokarta.core.media.MediaReconciliationWorker
import com.emirrkls.phokarta.core.sync.OfflineMutationRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class PhokartaApplication : Application(), Configuration.Provider {
    @Inject lateinit var visitDraftRepository: VisitDraftRepository
    @Inject lateinit var mutationRepository: OfflineMutationRepository
    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var mediaFileReconciler: MediaFileReconciler
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            visitDraftRepository.deleteExpiredDrafts()
            mediaFileReconciler.reconcile()
            mutationRepository.scheduleSync()
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            MEDIA_RECONCILIATION_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MediaReconciliationWorker>(12, TimeUnit.HOURS).build(),
        )
        applicationScope.launch {
            sessionManager.state
                .filterIsInstance<AuthState.Authenticated>()
                .map { it.user.id }
                .distinctUntilChanged()
                .collect { mutationRepository.scheduleSync() }
        }
    }

    private companion object {
        const val MEDIA_RECONCILIATION_WORK = "visit-media-reconciliation"
    }
}
