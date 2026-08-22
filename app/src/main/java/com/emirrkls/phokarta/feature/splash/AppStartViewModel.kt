package com.emirrkls.phokarta.feature.splash

import androidx.lifecycle.ViewModel
import com.emirrkls.phokarta.core.data.OnboardingStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AppStartViewModel @Inject constructor(private val onboardingStore: OnboardingStore) : ViewModel() {
    fun isOnboardingComplete(): Boolean = onboardingStore.isComplete()
    fun completeOnboarding() = onboardingStore.markComplete()
}
