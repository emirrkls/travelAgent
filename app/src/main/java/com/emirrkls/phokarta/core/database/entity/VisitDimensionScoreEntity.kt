package com.emirrkls.phokarta.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "visit_dimension_scores",
    primaryKeys = ["visitId", "dimensionKey"],
    foreignKeys = [
        ForeignKey(
            entity = VisitEntity::class,
            parentColumns = ["id"],
            childColumns = ["visitId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class VisitDimensionScoreEntity(
    val visitId: String,
    val dimensionKey: String,
    val score: Double,
)
