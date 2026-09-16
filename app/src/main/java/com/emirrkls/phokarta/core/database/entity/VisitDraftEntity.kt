package com.emirrkls.phokarta.core.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Local-only unfinished visit draft. Not a published [VisitEntity].
 * One active draft per authenticated user + place.
 */
@Entity(
    tableName = "visit_drafts",
    primaryKeys = ["userId", "placeId"],
    indices = [Index(value = ["updatedAtEpochMillis"])],
)
data class VisitDraftEntity(
    val userId: String,
    val placeId: String,
    val overallScore: Float,
    val publicReview: String,
    val privateMemory: String,
    val visitedAtEpochDay: Long,
    val visibility: String,
    val dimensionsExpanded: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    /** 1 = legacy Visit composer, 2 = native Experience composer. */
    val payloadVersion: Int = 2,
    val primaryExperienceCode: String? = null,
    val rawExperienceLabel: String? = null,
    val overallFeelingCode: String? = null,
    val companionCode: String? = null,
    val timeOfDayCode: String? = null,
    val vibeCodes: String = "",
    val practicalSignalCodes: String = "",
    val title: String? = null,
    val titleSource: String = "GENERATED",
    val story: String = "",
    val tip: String = "",
)
