package com.emirrkls.phokarta

import android.app.Application
import com.emirrkls.phokarta.core.data.VisitDraftRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class PhokartaApplication : Application() {
    @Inject lateinit var visitDraftRepository: VisitDraftRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            visitDraftRepository.deleteExpiredDrafts()
        }
    }
}
