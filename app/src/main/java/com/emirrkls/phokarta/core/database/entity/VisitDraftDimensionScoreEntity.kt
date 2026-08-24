package com.emirrkls.phokarta.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "visit_draft_dimension_scores",
    primaryKeys = ["userId", "placeId", "dimensionKey"],
    foreignKeys = [
        ForeignKey(
            entity = VisitDraftEntity::class,
            parentColumns = ["userId", "placeId"],
            childColumns = ["userId", "placeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["userId", "placeId"])],
)
data class VisitDraftDimensionScoreEntity(
    val userId: String,
    val placeId: String,
    val dimensionKey: String,
    val score: Float,
)
