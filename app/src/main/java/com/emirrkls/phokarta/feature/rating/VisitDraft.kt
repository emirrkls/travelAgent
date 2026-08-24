package com.emirrkls.phokarta.feature.rating

import androidx.annotation.StringRes
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.ui.localization.ScoreBand
import com.emirrkls.phokarta.ui.localization.impactHintRes
import com.emirrkls.phokarta.ui.localization.labelRes
import com.emirrkls.phokarta.ui.localization.reviewHelperRes
import com.emirrkls.phokarta.ui.localization.scoreBandFor
import com.emirrkls.phokarta.ui.localization.sheetDescriptionRes
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
 * User-facing copy for visit visibility — resource IDs only.
 * PUBLIC — community + mutual friends. FRIENDS — mutual friends only.
 * PRIVATE — owner only. Backend is authoritative for audience.
 */
object VisitVisibilityCopy {
    val selectionOrder: List<Visibility> = listOf(
        Visibility.PUBLIC,
        Visibility.FRIENDS,
        Visibility.PRIVATE,
    )

    @StringRes fun labelRes(visibility: Visibility): Int = visibility.labelRes()
    @StringRes fun sheetDescriptionRes(visibility: Visibility): Int = visibility.sheetDescriptionRes()
    @StringRes fun reviewHelperRes(visibility: Visibility): Int = visibility.reviewHelperRes()
    @StringRes fun impactHintRes(visibility: Visibility): Int = visibility.impactHintRes()
}

object VisitDraftLogic {
    fun scoreBand(score: Float): ScoreBand = scoreBandFor(score)

    @StringRes
    fun validateDateRes(date: LocalDate, today: LocalDate = LocalDate.now()): Int? =
        if (date.isAfter(today)) R.string.visit_date_future_error else null

    fun canPublish(draft: VisitDraft, today: LocalDate = LocalDate.now()): Boolean =
        validateDateRes(draft.visitDate, today) == null &&
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
