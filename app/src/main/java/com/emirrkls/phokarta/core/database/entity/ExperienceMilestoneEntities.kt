package com.emirrkls.phokarta.core.database.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "planned_experiences",
    primaryKeys = ["ownerUserId", "experienceId"],
    indices = [Index(value = ["ownerUserId", "plannedAtEpochMillis"])],
)
data class PlannedExperienceEntity(
    val ownerUserId: String,
    val experienceId: String,
    val title: String,
    val placeId: String,
    val placeName: String,
    val primaryExperienceCode: String,
    val feelingCode: String,
    val authorName: String,
    val imageUrl: String?,
    val plannedAtEpochMillis: Long,
)

@Entity(
    tableName = "experience_acknowledgements",
    primaryKeys = ["ownerUserId", "id"],
    indices = [
        Index(value = ["ownerUserId", "acknowledgedAtEpochMillis"]),
        Index(value = ["ownerUserId", "sourceExperienceId"]),
    ],
)
data class ExperienceAcknowledgementEntity(
    val ownerUserId: String,
    val id: String,
    val sourceExperienceId: String?,
    val sourceAvailable: Boolean,
    val placeId: String,
    val placeName: String,
    val placeCity: String,
    val placeRegion: String,
    val placeCountry: String,
    val primaryExperienceCode: String,
    val rawExperienceLabel: String?,
    val acknowledgedAtEpochMillis: Long,
    val convertedExperienceId: String?,
)
