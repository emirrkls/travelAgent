package com.emirrkls.phokarta.core.di

import com.emirrkls.phokarta.core.data.DefaultTravelRepository
import com.emirrkls.phokarta.core.data.LocalUserStateDataSource
import com.emirrkls.phokarta.core.data.PlaceCacheDataSource
import com.emirrkls.phokarta.core.data.RoomLocalUserStateDataSource
import com.emirrkls.phokarta.core.data.RoomPlaceCacheDataSource
import com.emirrkls.phokarta.core.data.TravelRepository
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

    @Binds
    @Singleton
    abstract fun bindPlaceCacheDataSource(
        dataSource: RoomPlaceCacheDataSource,
    ): PlaceCacheDataSource
}
