package com.emirrkls.phokarta

import android.app.Application
import com.emirrkls.phokarta.core.data.VisitDraftRepository
import dagger.hilt.android.HiltAndroidApp
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.emirrkls.phokarta.core.sync.OfflineMutationRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class PhokartaApplication : Application(), Configuration.Provider {
    @Inject lateinit var visitDraftRepository: VisitDraftRepository
    @Inject lateinit var mutationRepository: OfflineMutationRepository
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            visitDraftRepository.deleteExpiredDrafts()
            mutationRepository.scheduleSync()
        }
    }
}
