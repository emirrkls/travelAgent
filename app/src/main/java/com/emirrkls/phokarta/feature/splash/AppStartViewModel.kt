package com.emirrkls.phokarta.feature.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.auth.AuthRepository
import com.emirrkls.phokarta.core.auth.AuthState
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.data.OnboardingStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppStartViewModel @Inject constructor(
    private val onboardingStore: OnboardingStore,
    private val authRepository: AuthRepository,
    sessionManager: SessionManager,
) : ViewModel() {
    val authState: StateFlow<AuthState> = sessionManager.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Loading)

    init {
        viewModelScope.launch {
            authRepository.restoreSession()
        }
    }

    fun isOnboardingComplete(): Boolean = onboardingStore.isComplete()
    fun completeOnboarding() = onboardingStore.markComplete()
}
