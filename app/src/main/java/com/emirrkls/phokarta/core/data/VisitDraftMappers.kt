package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.database.entity.VisitDraftDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.VisitDraftEntity
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.model.CompanionCode
import com.emirrkls.phokarta.core.model.DimensionStateCode
import com.emirrkls.phokarta.core.model.ExperienceTitleSource
import com.emirrkls.phokarta.core.model.OverallFeelingCode
import com.emirrkls.phokarta.core.model.PracticalSignalCode
import com.emirrkls.phokarta.core.model.PrimaryExperienceCode
import com.emirrkls.phokarta.core.model.TimeOfDayCode
import com.emirrkls.phokarta.core.model.VibeCode
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
    payloadVersion = payloadVersion,
    primaryExperienceCode = primaryExperience?.name,
    rawExperienceLabel = rawExperienceLabel,
    overallFeelingCode = overallFeeling?.name,
    companionCode = companion?.name,
    timeOfDayCode = timeOfDay?.name,
    vibeCodes = vibes.map { it.name }.sorted().joinToString(","),
    practicalSignalCodes = practicalSignals.map { it.name }.sorted().joinToString(","),
    title = title,
    titleSource = titleSource.name,
    story = story,
    tip = tip,
    originAcknowledgementId = originAcknowledgementId,
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
} + semanticDimensions.mapNotNull { (key, state) ->
    state.compatibilityScore?.let { score ->
        VisitDraftDimensionScoreEntity(
            userId = userId,
            placeId = placeId,
            dimensionKey = key,
            score = score.toFloat(),
            semanticStateCode = state.name,
            templateVersion = 1,
        )
    }
}

internal fun VisitDraftEntity.toDomain(
    dimensionScores: List<VisitDraftDimensionScoreEntity>,
): VisitDraft = VisitDraft(
    overallScore = overallScore,
    dimensions = dimensionScores.filter { it.semanticStateCode == null }.mapNotNull { score ->
        RatingDimension.fromStoredKey(score.dimensionKey)?.let { it to score.score }
    }.toMap(),
    publicReview = publicReview,
    privateMemory = privateMemory,
    visitDate = LocalDate.ofEpochDay(visitedAtEpochDay),
    visibility = runCatching { Visibility.valueOf(visibility) }.getOrDefault(Visibility.PRIVATE),
    dimensionsExpanded = dimensionsExpanded,
    payloadVersion = payloadVersion,
    primaryExperience = primaryExperienceCode.enumOrNull<PrimaryExperienceCode>(),
    rawExperienceLabel = rawExperienceLabel,
    overallFeeling = overallFeelingCode.enumOrNull<OverallFeelingCode>(),
    semanticDimensions = dimensionScores.mapNotNull { score ->
        val state = score.semanticStateCode.enumOrNull<DimensionStateCode>()
        if (state == null || state == DimensionStateCode.UNKNOWN) null else score.dimensionKey to state
    }.toMap(),
    companion = companionCode.enumOrNull<CompanionCode>(),
    timeOfDay = timeOfDayCode.enumOrNull<TimeOfDayCode>(),
    vibes = vibeCodes.decodeEnums<VibeCode>(),
    practicalSignals = practicalSignalCodes.decodeEnums<PracticalSignalCode>(),
    title = title,
    titleSource = titleSource.enumOrNull<ExperienceTitleSource>() ?: ExperienceTitleSource.GENERATED,
    story = story,
    tip = tip,
    originAcknowledgementId = originAcknowledgementId,
)

private inline fun <reified T : Enum<T>> String?.enumOrNull(): T? =
    this?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } }

private inline fun <reified T : Enum<T>> String.decodeEnums(): Set<T> =
    split(',').filter(String::isNotBlank).mapNotNull { raw ->
        enumValues<T>().firstOrNull { it.name == raw }
    }.toSet()
