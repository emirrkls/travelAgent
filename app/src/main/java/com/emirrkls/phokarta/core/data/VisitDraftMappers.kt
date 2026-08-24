package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.database.entity.VisitDraftDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.VisitDraftEntity
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.feature.rating.VisitDraft
import java.time.LocalDate

internal fun VisitDraft.toDraftEntity(
    userId: String,
    placeId: String,
    createdAtEpochMillis: Long,
    updatedAtEpochMillis: Long,
): VisitDraftEntity = VisitDraftEntity(
    userId = userId,
    placeId = placeId,
    overallScore = overallScore,
    publicReview = publicReview,
    privateMemory = privateMemory,
    visitedAtEpochDay = visitDate.toEpochDay(),
    visibility = visibility.name,
    dimensionsExpanded = dimensionsExpanded,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun VisitDraft.toDraftDimensionEntities(
    userId: String,
    placeId: String,
): List<VisitDraftDimensionScoreEntity> = dimensions.map { (key, score) ->
    VisitDraftDimensionScoreEntity(
        userId = userId,
        placeId = placeId,
        dimensionKey = key.apiKey,
        score = score,
    )
}

internal fun VisitDraftEntity.toDomain(
    dimensionScores: List<VisitDraftDimensionScoreEntity>,
): VisitDraft = VisitDraft(
    overallScore = overallScore,
    dimensions = dimensionScores.mapNotNull { score ->
        RatingDimension.fromStoredKey(score.dimensionKey)?.let { it to score.score }
    }.toMap(),
    publicReview = publicReview,
    privateMemory = privateMemory,
    visitDate = LocalDate.ofEpochDay(visitedAtEpochDay),
    visibility = runCatching { Visibility.valueOf(visibility) }.getOrDefault(Visibility.PRIVATE),
    dimensionsExpanded = dimensionsExpanded,
)
