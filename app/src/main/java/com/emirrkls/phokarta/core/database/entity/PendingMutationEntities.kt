package com.emirrkls.phokarta.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

object MutationTypeValue {
    const val PUBLISH_VISIT = "PUBLISH_VISIT"
    const val PUBLISH_EXPERIENCE_V2 = "PUBLISH_EXPERIENCE_V2"
    const val SET_SAVED_STATE = "SET_SAVED_STATE"
    const val SET_PLANNED_EXPERIENCE_STATE = "SET_PLANNED_EXPERIENCE_STATE"
    const val ACKNOWLEDGE_EXPERIENCE = "ACKNOWLEDGE_EXPERIENCE"
    const val CREATE_CONVERSATION_ROOT = "CREATE_CONVERSATION_ROOT"
    const val CREATE_CONVERSATION_REPLY = "CREATE_CONVERSATION_REPLY"
    const val EDIT_CONVERSATION_ENTRY = "EDIT_CONVERSATION_ENTRY"
    const val DELETE_CONVERSATION_ENTRY = "DELETE_CONVERSATION_ENTRY"
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
    /** Explicit wire contract. Rows created before V2 migrate to version 1 unchanged. */
    val payloadVersion: Int = 1,
)

@Entity(
    tableName = "pending_experience_v2_payloads",
    foreignKeys = [ForeignKey(
        entity = PendingMutationEntity::class,
        parentColumns = ["mutationId"],
        childColumns = ["mutationId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class PendingExperienceV2PayloadEntity(
    @androidx.room.PrimaryKey val mutationId: String,
    val placeId: String,
    val visitedAtEpochDay: Long,
    val primaryExperienceCode: String,
    val rawExperienceLabel: String?,
    val overallFeelingCode: String,
    val companionCode: String?,
    val timeOfDayCode: String?,
    /** Sorted stable codes joined with a comma. Empty means no values. */
    val vibeCodes: String,
    /** Sorted stable codes joined with a comma. Empty means no values. */
    val practicalSignalCodes: String,
    val title: String?,
    val titleSource: String,
    val story: String,
    val tip: String,
    val privateMemory: String,
    val visibility: String,
    val originAcknowledgementId: String? = null,
)

@Entity(
    tableName = "pending_experience_v2_dimensions",
    primaryKeys = ["mutationId", "dimensionKey"],
    foreignKeys = [ForeignKey(
        entity = PendingMutationEntity::class,
        parentColumns = ["mutationId"],
        childColumns = ["mutationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("mutationId")],
)
data class PendingExperienceV2DimensionEntity(
    val mutationId: String,
    val dimensionKey: String,
    val semanticStateCode: String,
    val templateVersion: Int,
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
    val ownerUserId: String,
    val clientMediaId: String,
    val localRelativePath: String?,
    val contentType: String?,
    val byteSize: Long?,
    val width: Int?,
    val height: Int?,
    val remoteMediaId: String?,
    val uploadState: String,
    val failureCategory: String?,
    /** v6 compatibility only. New rows never store a URL here. */
    val legacyUrl: String?,
) {
    constructor(mutationId: String, position: Int, url: String) : this(
        mutationId = mutationId,
        position = position,
        ownerUserId = "",
        clientMediaId = "",
        localRelativePath = null,
        contentType = null,
        byteSize = null,
        width = null,
        height = null,
        remoteMediaId = null,
        uploadState = MediaUploadState.READY_REMOTE,
        failureCategory = null,
        legacyUrl = url,
    )
}
