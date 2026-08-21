package com.emirrkls.travelagent.core.di

import com.emirrkls.travelagent.core.data.MockTravelRepository
import com.emirrkls.travelagent.core.data.TravelRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindTravelRepository(repository: MockTravelRepository): TravelRepository
}
