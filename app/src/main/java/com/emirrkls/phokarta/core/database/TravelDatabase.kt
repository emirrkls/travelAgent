package com.emirrkls.phokarta.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.emirrkls.phokarta.core.database.dao.CachedPlaceDao
import com.emirrkls.phokarta.core.database.dao.CollectionDao
import com.emirrkls.phokarta.core.database.dao.SavedPlaceDao
import com.emirrkls.phokarta.core.database.dao.VisitDao
import com.emirrkls.phokarta.core.database.dao.VisitDraftDao
import com.emirrkls.phokarta.core.database.dao.PendingMutationDao
import com.emirrkls.phokarta.core.database.entity.CachedPlaceEntity
import com.emirrkls.phokarta.core.database.entity.CollectionEntity
import com.emirrkls.phokarta.core.database.entity.CollectionPlaceCrossRef
import com.emirrkls.phokarta.core.database.entity.SavedPlaceEntity
import com.emirrkls.phokarta.core.database.entity.VisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.VisitDraftDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.VisitDraftEntity
import com.emirrkls.phokarta.core.database.entity.VisitEntity
import com.emirrkls.phokarta.core.database.entity.PendingMutationEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitPayloadEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitPhotoEntity

@Database(
    entities = [
        VisitEntity::class,
        VisitDimensionScoreEntity::class,
        VisitDraftEntity::class,
        VisitDraftDimensionScoreEntity::class,
        SavedPlaceEntity::class,
        CollectionEntity::class,
        CollectionPlaceCrossRef::class,
        CachedPlaceEntity::class,
        PendingMutationEntity::class,
        PendingVisitPayloadEntity::class,
        PendingVisitDimensionScoreEntity::class,
        PendingVisitPhotoEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class TravelDatabase : RoomDatabase() {
    abstract fun visitDao(): VisitDao
    abstract fun visitDraftDao(): VisitDraftDao
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun collectionDao(): CollectionDao
    abstract fun cachedPlaceDao(): CachedPlaceDao
    abstract fun pendingMutationDao(): PendingMutationDao

    companion object {
        const val NAME = "travel-agent.db"
    }
}
