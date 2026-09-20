package com.emirrkls.phokarta.core.di

import android.content.Context
import androidx.room.Room
import com.emirrkls.phokarta.core.database.MIGRATION_1_2
import com.emirrkls.phokarta.core.database.MIGRATION_2_3
import com.emirrkls.phokarta.core.database.MIGRATION_3_4
import com.emirrkls.phokarta.core.database.MIGRATION_4_5
import com.emirrkls.phokarta.core.database.MIGRATION_5_6
import com.emirrkls.phokarta.core.database.MIGRATION_6_7
import com.emirrkls.phokarta.core.database.MIGRATION_7_8
import com.emirrkls.phokarta.core.database.MIGRATION_8_9
import com.emirrkls.phokarta.core.database.MIGRATION_9_10
import com.emirrkls.phokarta.core.database.dao.ConversationDao
import com.emirrkls.phokarta.core.database.dao.PendingMutationDao
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.database.dao.CachedPlaceDao
import com.emirrkls.phokarta.core.database.dao.CollectionDao
import com.emirrkls.phokarta.core.database.dao.SavedPlaceDao
import com.emirrkls.phokarta.core.database.dao.VisitDao
import com.emirrkls.phokarta.core.database.dao.VisitDraftDao
import com.emirrkls.phokarta.core.database.dao.ExperienceMilestoneDao
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
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
            )
            .build()

    @Provides
    fun provideVisitDao(database: TravelDatabase): VisitDao = database.visitDao()

    @Provides
    fun provideVisitDraftDao(database: TravelDatabase): VisitDraftDao = database.visitDraftDao()

    @Provides
    fun provideSavedPlaceDao(database: TravelDatabase): SavedPlaceDao = database.savedPlaceDao()

    @Provides
    fun provideCollectionDao(database: TravelDatabase): CollectionDao = database.collectionDao()

    @Provides
    fun provideCachedPlaceDao(database: TravelDatabase): CachedPlaceDao = database.cachedPlaceDao()

    @Provides
    fun providePendingMutationDao(database: TravelDatabase): PendingMutationDao = database.pendingMutationDao()

    @Provides
    fun provideConversationDao(database: TravelDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideExperienceMilestoneDao(database: TravelDatabase): ExperienceMilestoneDao = database.experienceMilestoneDao()
}
