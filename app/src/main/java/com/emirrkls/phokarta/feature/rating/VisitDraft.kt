package com.emirrkls.phokarta.feature.rating

import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visit
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

data class VisitDraft(
    val overallScore: Float = 8f,
    val dimensions: Map<RatingDimension, Float> = emptyMap(),
    val publicReview: String = "",
    val privateMemory: String = "",
    val visitDate: LocalDate = LocalDate.now(),
    val dimensionsExpanded: Boolean = false,
)

object VisitDraftLogic {
    fun scoreLabel(score: Float): String = when {
        score >= 9.5f -> "Exceptional"
        score >= 9f -> "Amazing"
        score >= 7f -> "Good"
        score >= 5f -> "Okay"
        score >= 2f -> "Disappointing"
        else -> "Terrible"
    }

    fun validateDate(date: LocalDate, today: LocalDate = LocalDate.now()): String? =
        if (date.isAfter(today)) "Visit date can't be in the future." else null

    fun canPublish(draft: VisitDraft, today: LocalDate = LocalDate.now()): Boolean =
        validateDate(draft.visitDate, today) == null &&
            draft.overallScore in 0f..10f

    fun toVisit(
        draft: VisitDraft,
        placeId: String,
        userId: String,
        visitId: String = UUID.randomUUID().toString(),
    ): Visit = Visit(
        id = visitId,
        userId = userId,
        placeId = placeId,
        visitedAt = draft.visitDate,
        overallRating = draft.overallScore.roundToTenth().toDouble(),
        ratingDimensions = draft.dimensions.mapValues { it.value.roundToTenth().toDouble() },
        review = draft.publicReview.trim(),
        personalNote = draft.privateMemory.trim(),
    )
}

fun Float.roundToTenth(): Float = (this * 10).roundToInt() / 10f
