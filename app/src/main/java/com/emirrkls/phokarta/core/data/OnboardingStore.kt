package com.emirrkls.phokarta.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnboardingStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("travel_agent_preferences", Context.MODE_PRIVATE)
    fun isComplete(): Boolean = preferences.getBoolean("onboarding_complete", false)
    fun markComplete() = preferences.edit().putBoolean("onboarding_complete", true).apply()
}
