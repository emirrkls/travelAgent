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
 * FRIENDS is stored by the backend but is not friend-readable today — Social v2
 * discovery/feeds/scores use PUBLIC visits only. Copy must stay truthful.
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
        Visibility.PUBLIC -> "Share it with the Phokarta community"
        Visibility.FRIENDS -> "Keep this limited for now — not shown in community discovery"
        Visibility.PRIVATE -> "Keep this visit only for yourself"
    }

    fun reviewHelper(visibility: Visibility): String = when (visibility) {
        Visibility.PUBLIC -> "Shared with the community"
        Visibility.FRIENDS -> "Not shown in community reviews"
        Visibility.PRIVATE -> "Only you can see this review"
    }

    fun impactHint(visibility: Visibility): String = when (visibility) {
        Visibility.PUBLIC -> "This visit can contribute to community discovery."
        Visibility.FRIENDS -> "This won't affect community discovery."
        Visibility.PRIVATE -> "This won't affect community or friends scores."
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
