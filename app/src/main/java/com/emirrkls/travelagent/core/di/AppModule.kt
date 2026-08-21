package com.emirrkls.travelagent.core.di

import com.emirrkls.travelagent.core.data.DefaultTravelRepository
import com.emirrkls.travelagent.core.data.LocalUserStateDataSource
import com.emirrkls.travelagent.core.data.RoomLocalUserStateDataSource
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
    abstract fun bindTravelRepository(repository: DefaultTravelRepository): TravelRepository

    @Binds
    @Singleton
    abstract fun bindLocalUserStateDataSource(
        dataSource: RoomLocalUserStateDataSource,
    ): LocalUserStateDataSource
}
