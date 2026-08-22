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
    @Query("SELECT placeId FROM saved_places ORDER BY savedAtEpochMillis DESC")
    fun observeSavedPlaceIds(): Flow<List<String>>

    @Query("SELECT * FROM saved_places WHERE placeId = :placeId")
    suspend fun getSavedPlace(placeId: String): SavedPlaceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSavedPlace(savedPlace: SavedPlaceEntity)

    @Query("DELETE FROM saved_places WHERE placeId = :placeId")
    suspend fun deleteSavedPlace(placeId: String)

    @Query("DELETE FROM saved_places")
    suspend fun deleteAllSavedPlaces()

    @Transaction
    suspend fun setSaved(placeId: String, saved: Boolean, nowEpochMillis: Long) {
        if (saved) {
            insertSavedPlace(SavedPlaceEntity(placeId, nowEpochMillis))
        } else {
            deleteSavedPlace(placeId)
        }
    }

    @Transaction
    suspend fun replaceSavedPlaceIds(placeIds: Set<String>, nowEpochMillis: Long) {
        deleteAllSavedPlaces()
        placeIds.forEach { insertSavedPlace(SavedPlaceEntity(it, nowEpochMillis)) }
    }
}
