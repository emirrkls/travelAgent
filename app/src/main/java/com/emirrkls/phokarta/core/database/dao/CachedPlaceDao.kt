package com.emirrkls.phokarta.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.emirrkls.phokarta.core.database.entity.CachedPlaceEntity

@Dao
interface CachedPlaceDao {
    @Query("SELECT * FROM cached_places ORDER BY name")
    suspend fun getAll(): List<CachedPlaceEntity>

    @Upsert
    suspend fun upsertAll(places: List<CachedPlaceEntity>)
}
