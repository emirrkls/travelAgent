package com.emirrkls.phokarta.core.database.entity

import androidx.room.Entity

@Entity(tableName = "saved_places", primaryKeys = ["ownerUserId", "placeId"])
data class SavedPlaceEntity(
    val ownerUserId: String,
    val placeId: String,
    val savedAtEpochMillis: Long,
)
