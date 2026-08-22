package com.emirrkls.phokarta.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_places")
data class CachedPlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val coverImage: String,
    val city: String,
    val region: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val priceLevel: Int,
    val averageScore: Double?,
    val ratingCount: Int,
    val updatedAtEpochMillis: Long,
)
