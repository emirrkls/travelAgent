package com.emirrkls.phokarta.core.di

import com.emirrkls.phokarta.core.data.DefaultTravelRepository
import com.emirrkls.phokarta.core.data.LocalUserStateDataSource
import com.emirrkls.phokarta.core.data.PlaceCacheDataSource
import com.emirrkls.phokarta.core.data.RoomLocalUserStateDataSource
import com.emirrkls.phokarta.core.data.RoomPlaceCacheDataSource
import com.emirrkls.phokarta.core.data.RoomVisitDraftRepository
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.data.VisitDraftRepository
import com.emirrkls.phokarta.core.auth.LocalAccountPurger
import com.emirrkls.phokarta.core.auth.RoomLocalAccountPurger
import com.emirrkls.phokarta.core.sync.MutationSyncScheduler
import com.emirrkls.phokarta.core.sync.OfflineMutationRepository
import com.emirrkls.phokarta.core.sync.RoomOfflineMutationRepository
import com.emirrkls.phokarta.core.sync.WorkManagerMutationSyncScheduler
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

    @Binds
    @Singleton
    abstract fun bindVisitDraftRepository(
        repository: RoomVisitDraftRepository,
    ): VisitDraftRepository

    @Binds @Singleton
    abstract fun bindOfflineMutationRepository(repository: RoomOfflineMutationRepository): OfflineMutationRepository

    @Binds @Singleton
    abstract fun bindMutationSyncScheduler(scheduler: WorkManagerMutationSyncScheduler): MutationSyncScheduler

    @Binds @Singleton
    abstract fun bindLocalAccountPurger(purger: RoomLocalAccountPurger): LocalAccountPurger
}
