package com.emirrkls.phokarta

import android.content.Context
import androidx.room.Room
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.database.dao.CachedPlaceDao
import com.emirrkls.phokarta.core.database.dao.CollectionDao
import com.emirrkls.phokarta.core.database.dao.SavedPlaceDao
import com.emirrkls.phokarta.core.database.dao.VisitDao
import com.emirrkls.phokarta.core.di.DatabaseModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DatabaseModule::class],
)
object TestDatabaseModule {
    @Provides
    @Singleton
    fun provideTravelDatabase(@ApplicationContext context: Context): TravelDatabase =
        Room.inMemoryDatabaseBuilder(context, TravelDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    @Provides
    fun provideVisitDao(database: TravelDatabase): VisitDao = database.visitDao()

    @Provides
    fun provideSavedPlaceDao(database: TravelDatabase): SavedPlaceDao = database.savedPlaceDao()

    @Provides
    fun provideCollectionDao(database: TravelDatabase): CollectionDao = database.collectionDao()

    @Provides
    fun provideCachedPlaceDao(database: TravelDatabase): CachedPlaceDao = database.cachedPlaceDao()
}
