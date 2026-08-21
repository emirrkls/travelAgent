package com.emirrkls.travelagent.core.di

import android.content.Context
import androidx.room.Room
import com.emirrkls.travelagent.core.database.DemoDataCallback
import com.emirrkls.travelagent.core.database.TravelDatabase
import com.emirrkls.travelagent.core.database.dao.CollectionDao
import com.emirrkls.travelagent.core.database.dao.SavedPlaceDao
import com.emirrkls.travelagent.core.database.dao.VisitDao
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
            .addCallback(DemoDataCallback())
            .build()

    @Provides
    fun provideVisitDao(database: TravelDatabase): VisitDao = database.visitDao()

    @Provides
    fun provideSavedPlaceDao(database: TravelDatabase): SavedPlaceDao = database.savedPlaceDao()

    @Provides
    fun provideCollectionDao(database: TravelDatabase): CollectionDao = database.collectionDao()
}
