package com.emirrkls.phokarta.core.di

import com.emirrkls.phokarta.core.time.EpochClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TimeModule {
    @Provides
    @Singleton
    fun provideEpochClock(): EpochClock = EpochClock { System.currentTimeMillis() }
}
