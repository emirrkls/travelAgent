package com.emirrkls.phokarta.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.emirrkls.phokarta.core.database.dao.CachedPlaceDao
import com.emirrkls.phokarta.core.database.dao.CollectionDao
import com.emirrkls.phokarta.core.database.dao.SavedPlaceDao
import com.emirrkls.phokarta.core.database.dao.VisitDao
import com.emirrkls.phokarta.core.database.entity.CachedPlaceEntity
import com.emirrkls.phokarta.core.database.entity.CollectionEntity
import com.emirrkls.phokarta.core.database.entity.CollectionPlaceCrossRef
import com.emirrkls.phokarta.core.database.entity.SavedPlaceEntity
import com.emirrkls.phokarta.core.database.entity.VisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.VisitEntity

@Database(
    entities = [
        VisitEntity::class,
        VisitDimensionScoreEntity::class,
        SavedPlaceEntity::class,
        CollectionEntity::class,
        CollectionPlaceCrossRef::class,
        CachedPlaceEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class TravelDatabase : RoomDatabase() {
    abstract fun visitDao(): VisitDao
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun collectionDao(): CollectionDao
    abstract fun cachedPlaceDao(): CachedPlaceDao

    companion object {
        const val NAME = "travel-agent.db"
    }
}
