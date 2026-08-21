package com.emirrkls.travelagent.core.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.emirrkls.travelagent.core.data.DemoUserState
import com.emirrkls.travelagent.core.database.mapper.toDimensionEntities
import com.emirrkls.travelagent.core.database.mapper.toEntity

class DemoDataCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        val seedTime = 1_777_000_000_000L

        DemoUserState.visits.forEachIndexed { index, visit ->
            val entity = visit.toEntity(seedTime - index)
            db.execSQL(
                """INSERT OR IGNORE INTO visits
                    (id, userId, placeId, visitedAtEpochDay, overallRating, publicReview, privateMemory, visibility, verificationStatus, createdAtEpochMillis)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                arrayOf<Any>(
                    entity.id,
                    entity.userId,
                    entity.placeId,
                    entity.visitedAtEpochDay,
                    entity.overallRating,
                    entity.publicReview,
                    entity.privateMemory,
                    entity.visibility,
                    entity.verificationStatus,
                    entity.createdAtEpochMillis,
                ),
            )
            visit.toDimensionEntities().forEach { score ->
                db.execSQL(
                    "INSERT OR IGNORE INTO visit_dimension_scores (visitId, dimensionKey, score) VALUES (?, ?, ?)",
                    arrayOf<Any>(score.visitId, score.dimensionKey, score.score),
                )
            }
        }

        DemoUserState.savedPlaceIds.forEachIndexed { index, placeId ->
            db.execSQL(
                "INSERT OR IGNORE INTO saved_places (placeId, savedAtEpochMillis) VALUES (?, ?)",
                arrayOf<Any>(placeId, seedTime - index),
            )
        }

        DemoUserState.collections.forEachIndexed { index, collection ->
            val entity = collection.toEntity(seedTime - index, seedTime - index)
            db.execSQL(
                """INSERT OR IGNORE INTO collections
                    (id, userId, title, description, visibility, coverImage, createdAtEpochMillis, updatedAtEpochMillis)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                arrayOf<Any>(
                    entity.id,
                    entity.userId,
                    entity.title,
                    entity.description,
                    entity.visibility,
                    entity.coverImage,
                    entity.createdAtEpochMillis,
                    entity.updatedAtEpochMillis,
                ),
            )
            collection.placeIds.distinct().forEach { placeId ->
                db.execSQL(
                    "INSERT OR IGNORE INTO collection_places (collectionId, placeId) VALUES (?, ?)",
                    arrayOf<Any>(collection.id, placeId),
                )
            }
        }
    }
}
