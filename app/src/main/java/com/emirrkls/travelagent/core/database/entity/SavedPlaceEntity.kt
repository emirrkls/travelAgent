package com.emirrkls.travelagent.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_places")
data class SavedPlaceEntity(
    @PrimaryKey val placeId: String,
    val savedAtEpochMillis: Long,
)
