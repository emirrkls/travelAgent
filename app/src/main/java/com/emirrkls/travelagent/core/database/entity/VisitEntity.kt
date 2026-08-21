package com.emirrkls.travelagent.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "visits",
    indices = [Index("placeId"), Index("visitedAtEpochDay")],
)
data class VisitEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val placeId: String,
    val visitedAtEpochDay: Long,
    val overallRating: Double,
    val publicReview: String,
    val privateMemory: String,
    val visibility: String,
    val verificationStatus: String,
    val createdAtEpochMillis: Long,
)
