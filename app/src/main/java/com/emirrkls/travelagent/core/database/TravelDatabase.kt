package com.emirrkls.travelagent.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.emirrkls.travelagent.core.database.dao.CollectionDao
import com.emirrkls.travelagent.core.database.dao.SavedPlaceDao
import com.emirrkls.travelagent.core.database.dao.VisitDao
import com.emirrkls.travelagent.core.database.entity.CollectionEntity
import com.emirrkls.travelagent.core.database.entity.CollectionPlaceCrossRef
import com.emirrkls.travelagent.core.database.entity.SavedPlaceEntity
import com.emirrkls.travelagent.core.database.entity.VisitDimensionScoreEntity
import com.emirrkls.travelagent.core.database.entity.VisitEntity

@Database(
    entities = [
        VisitEntity::class,
        VisitDimensionScoreEntity::class,
        SavedPlaceEntity::class,
        CollectionEntity::class,
        CollectionPlaceCrossRef::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TravelDatabase : RoomDatabase() {
    abstract fun visitDao(): VisitDao
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun collectionDao(): CollectionDao

    companion object {
        const val NAME = "travel-agent.db"
    }
}
