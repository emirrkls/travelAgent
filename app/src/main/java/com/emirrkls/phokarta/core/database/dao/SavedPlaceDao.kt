package com.emirrkls.phokarta.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.emirrkls.phokarta.core.database.entity.SavedPlaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPlaceDao {
    @Query(
        """
        SELECT placeId FROM saved_places
        WHERE ownerUserId = :ownerUserId
        ORDER BY savedAtEpochMillis DESC
        """,
    )
    fun observeSavedPlaceIds(ownerUserId: String): Flow<List<String>>

    @Query("SELECT * FROM saved_places WHERE ownerUserId = :ownerUserId AND placeId = :placeId")
    suspend fun getSavedPlace(ownerUserId: String, placeId: String): SavedPlaceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSavedPlace(savedPlace: SavedPlaceEntity)

    @Query("DELETE FROM saved_places WHERE ownerUserId = :ownerUserId AND placeId = :placeId")
    suspend fun deleteSavedPlace(ownerUserId: String, placeId: String)

    @Query("DELETE FROM saved_places WHERE ownerUserId = :ownerUserId")
    suspend fun deleteSavedPlacesForOwner(ownerUserId: String)

    @Transaction
    suspend fun setSaved(
        ownerUserId: String,
        placeId: String,
        saved: Boolean,
        nowEpochMillis: Long,
    ) {
        if (saved) {
            insertSavedPlace(SavedPlaceEntity(ownerUserId, placeId, nowEpochMillis))
        } else {
            deleteSavedPlace(ownerUserId, placeId)
        }
    }

    @Transaction
    suspend fun replaceSavedPlaceIds(
        ownerUserId: String,
        placeIds: Set<String>,
        nowEpochMillis: Long,
    ) {
        deleteSavedPlacesForOwner(ownerUserId)
        placeIds.forEach {
            insertSavedPlace(SavedPlaceEntity(ownerUserId, it, nowEpochMillis))
        }
    }
}
