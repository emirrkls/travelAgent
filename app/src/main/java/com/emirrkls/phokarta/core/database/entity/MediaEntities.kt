package com.emirrkls.phokarta.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

object MediaUploadState {
    const val LOCAL_ONLY = "LOCAL_ONLY"
    const val INTENT_CREATED = "INTENT_CREATED"
    const val READY_REMOTE = "READY_REMOTE"
}

object MediaFailureCategory {
    const val LEGACY_MEDIA_RESELECT_REQUIRED = "LEGACY_MEDIA_RESELECT_REQUIRED"
    const val UNSUPPORTED_TYPE = "UNSUPPORTED_TYPE"
    const val TOO_LARGE = "TOO_LARGE"
    const val MISSING_FILE = "MISSING_FILE"
    const val OWNERSHIP = "OWNERSHIP"
    const val INVALID_STATE = "INVALID_STATE"
    const val UPLOAD_REJECTED = "UPLOAD_REJECTED"
}

@Entity(
    tableName = "visit_draft_photos",
    primaryKeys = ["ownerUserId", "placeId", "position"],
    foreignKeys = [ForeignKey(
        entity = VisitDraftEntity::class,
        parentColumns = ["userId", "placeId"],
        childColumns = ["ownerUserId", "placeId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["ownerUserId", "placeId"]),
        Index(value = ["clientMediaId"], unique = true),
    ],
)
data class VisitDraftPhotoEntity(
    val ownerUserId: String,
    val placeId: String,
    val position: Int,
    val clientMediaId: String,
    val localRelativePath: String,
    val contentType: String,
    val byteSize: Long,
    val width: Int?,
    val height: Int?,
    val remoteMediaId: String?,
    val uploadState: String,
    val failureCategory: String?,
    val legacyUrl: String? = null,
)

@Entity(
    tableName = "visit_media",
    primaryKeys = ["ownerUserId", "visitId", "position"],
    foreignKeys = [ForeignKey(
        entity = VisitEntity::class,
        parentColumns = ["id"],
        childColumns = ["visitId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("visitId"),
        Index(value = ["ownerUserId", "mediaId"]),
    ],
)
data class VisitMediaEntity(
    val ownerUserId: String,
    val visitId: String,
    val position: Int,
    val mediaId: String,
    val accessUrl: String?,
    val accessUrlExpiresAtEpochMillis: Long?,
)
