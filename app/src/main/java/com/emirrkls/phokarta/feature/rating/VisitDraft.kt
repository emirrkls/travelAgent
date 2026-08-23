package com.emirrkls.phokarta.feature.rating

import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
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
    val visibility: Visibility = Visibility.PUBLIC,
    val dimensionsExpanded: Boolean = false,
)

/**
 * User-facing copy for visit visibility.
 *
 * PUBLIC — community + mutual friends. FRIENDS — mutual friends only.
 * PRIVATE — owner only. Backend is authoritative for audience.
 */
object VisitVisibilityCopy {
    val selectionOrder: List<Visibility> = listOf(
        Visibility.PUBLIC,
        Visibility.FRIENDS,
        Visibility.PRIVATE,
    )

    fun label(visibility: Visibility): String = when (visibility) {
        Visibility.PUBLIC -> "Public"
        Visibility.FRIENDS -> "Friends"
        Visibility.PRIVATE -> "Private"
    }

    fun sheetDescription(visibility: Visibility): String = when (visibility) {
        Visibility.PUBLIC -> "Visible to the Phokarta community"
        Visibility.FRIENDS -> "Visible to your friends"
        Visibility.PRIVATE -> "Only you can see this"
    }

    fun reviewHelper(visibility: Visibility): String = when (visibility) {
        Visibility.PUBLIC -> "Shared with the community"
        Visibility.FRIENDS -> "Shared with your friends"
        Visibility.PRIVATE -> "Only visible to you"
    }

    fun impactHint(visibility: Visibility): String = when (visibility) {
        Visibility.PUBLIC -> "This can contribute to Community and Friends discovery."
        Visibility.FRIENDS -> "This won't affect the Community score."
        Visibility.PRIVATE -> "This won't affect Community or Friends scores."
    }

    fun contentDescription(visibility: Visibility): String =
        "Visibility, ${label(visibility)}"
}

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
        visibility = draft.visibility,
    )
}

fun Float.roundToTenth(): Float = (this * 10).roundToInt() / 10f
