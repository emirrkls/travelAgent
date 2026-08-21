package com.emirrkls.travelagent.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val description: String,
    val visibility: String,
    val coverImage: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
