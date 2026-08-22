package com.emirrkls.phokarta.core.database.mapper

import com.emirrkls.phokarta.core.database.entity.CollectionEntity
import com.emirrkls.phokarta.core.database.entity.VisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.VisitEntity
import com.emirrkls.phokarta.core.database.relation.CollectionWithPlaceIds
import com.emirrkls.phokarta.core.database.relation.VisitWithDimensions
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.VerificationStatus
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.model.Visibility
import java.time.LocalDate

fun Visit.toEntity(createdAtEpochMillis: Long): VisitEntity = VisitEntity(
    id = id,
    userId = userId,
    placeId = placeId,
    visitedAtEpochDay = visitedAt.toEpochDay(),
    overallRating = overallRating,
    publicReview = review,
    privateMemory = personalNote,
    visibility = visibility.name,
    verificationStatus = verificationStatus.name,
    createdAtEpochMillis = createdAtEpochMillis,
)

fun Visit.toDimensionEntities(): List<VisitDimensionScoreEntity> = ratingDimensions.map { (key, score) ->
    VisitDimensionScoreEntity(
        visitId = id,
        dimensionKey = key.apiKey,
        score = score,
    )
}

fun VisitWithDimensions.toDomain(): Visit = Visit(
    id = visit.id,
    userId = visit.userId,
    placeId = visit.placeId,
    visitedAt = LocalDate.ofEpochDay(visit.visitedAtEpochDay),
    overallRating = visit.overallRating,
    ratingDimensions = dimensions.mapNotNull { score ->
        RatingDimension.fromStoredKey(score.dimensionKey)?.let { it to score.score }
    }.toMap(),
    review = visit.publicReview,
    personalNote = visit.privateMemory,
    visibility = visit.visibility.toVisibility(),
    verificationStatus = visit.verificationStatus.toVerificationStatus(),
)

fun Collection.toEntity(
    createdAtEpochMillis: Long,
    updatedAtEpochMillis: Long,
): CollectionEntity = CollectionEntity(
    id = id,
    userId = userId,
    title = title,
    description = description,
    visibility = visibility.name,
    coverImage = coverImage,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

fun CollectionWithPlaceIds.toDomain(): Collection = Collection(
    id = collection.id,
    userId = collection.userId,
    title = collection.title,
    description = collection.description,
    placeIds = placeIds,
    visibility = collection.visibility.toVisibility(),
    coverImage = collection.coverImage,
)

private fun String.toVisibility(): Visibility = runCatching { Visibility.valueOf(this) }
    .getOrDefault(Visibility.PRIVATE)

private fun String.toVerificationStatus(): VerificationStatus = runCatching { VerificationStatus.valueOf(this) }
    .getOrDefault(VerificationStatus.UNVERIFIED)
