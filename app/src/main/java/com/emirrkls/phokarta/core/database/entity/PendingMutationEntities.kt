package com.emirrkls.phokarta.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

object MutationTypeValue {
    const val PUBLISH_VISIT = "PUBLISH_VISIT"
    const val SET_SAVED_STATE = "SET_SAVED_STATE"
}

object MutationStateValue {
    const val PENDING = "PENDING"
    const val SYNCING = "SYNCING"
    const val FAILED_RETRYABLE = "FAILED_RETRYABLE"
    const val FAILED_PERMANENT = "FAILED_PERMANENT"
}

@Entity(
    tableName = "pending_mutations",
    primaryKeys = ["mutationId"],
    indices = [
        Index(value = ["userId", "state", "createdAtEpochMillis"]),
        Index(value = ["userId", "type", "resourceKey"], unique = true),
    ],
)
data class PendingMutationEntity(
    val mutationId: String,
    val userId: String,
    val type: String,
    val resourceKey: String,
    val state: String,
    val generation: Long,
    val desiredSaved: Boolean?,
    val attemptCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lastErrorCategory: String?,
)

@Entity(
    tableName = "pending_visit_payloads",
    foreignKeys = [ForeignKey(
        entity = PendingMutationEntity::class,
        parentColumns = ["mutationId"],
        childColumns = ["mutationId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class PendingVisitPayloadEntity(
    @androidx.room.PrimaryKey val mutationId: String,
    val placeId: String,
    val visitedAtEpochDay: Long,
    val overallRating: Double,
    val publicReview: String,
    val privateMemory: String,
    val visibility: String,
)

@Entity(
    tableName = "pending_visit_dimension_scores",
    primaryKeys = ["mutationId", "dimensionKey"],
    foreignKeys = [ForeignKey(
        entity = PendingMutationEntity::class,
        parentColumns = ["mutationId"],
        childColumns = ["mutationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("mutationId")],
)
data class PendingVisitDimensionScoreEntity(
    val mutationId: String,
    val dimensionKey: String,
    val score: Double,
)

@Entity(
    tableName = "pending_visit_photos",
    primaryKeys = ["mutationId", "position"],
    foreignKeys = [ForeignKey(
        entity = PendingMutationEntity::class,
        parentColumns = ["mutationId"],
        childColumns = ["mutationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("mutationId")],
)
data class PendingVisitPhotoEntity(
    val mutationId: String,
    val position: Int,
    val url: String,
)
