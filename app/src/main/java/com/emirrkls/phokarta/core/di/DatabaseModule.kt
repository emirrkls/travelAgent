package com.emirrkls.phokarta.core.di

import android.content.Context
import androidx.room.Room
import com.emirrkls.phokarta.core.database.MIGRATION_1_2
import com.emirrkls.phokarta.core.database.MIGRATION_2_3
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.database.dao.CachedPlaceDao
import com.emirrkls.phokarta.core.database.dao.CollectionDao
import com.emirrkls.phokarta.core.database.dao.SavedPlaceDao
import com.emirrkls.phokarta.core.database.dao.VisitDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideTravelDatabase(@ApplicationContext context: Context): TravelDatabase =
        Room.databaseBuilder(context, TravelDatabase::class.java, TravelDatabase.NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
