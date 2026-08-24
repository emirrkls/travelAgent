package com.emirrkls.phokarta.feature.rating

import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.network.mapper.toCreateDto
import com.emirrkls.phokarta.ui.localization.ScoreBand
import com.emirrkls.phokarta.ui.localization.labelRes
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitDraftLogicTest {
    private val today = LocalDate.of(2026, 8, 23)

    @Test
    fun newDraftDefaultsVisibilityToPublic() {
        assertEquals(Visibility.PUBLIC, VisitDraft().visibility)
    }

    @Test
    fun scoreBandsCoverAnchors() {
        assertEquals(ScoreBand.TERRIBLE, VisitDraftLogic.scoreBand(0f))
        assertEquals(ScoreBand.OKAY, VisitDraftLogic.scoreBand(5f))
        assertEquals(ScoreBand.GOOD, VisitDraftLogic.scoreBand(7f))
        assertEquals(ScoreBand.AMAZING, VisitDraftLogic.scoreBand(9f))
        assertEquals(ScoreBand.EXCEPTIONAL, VisitDraftLogic.scoreBand(10f))
        assertEquals(R.string.score_terrible, VisitDraftLogic.scoreBand(0f).labelRes())
        assertEquals(R.string.score_okay, VisitDraftLogic.scoreBand(5f).labelRes())
        assertEquals(R.string.score_good, VisitDraftLogic.scoreBand(7f).labelRes())
        assertEquals(R.string.score_amazing, VisitDraftLogic.scoreBand(9f).labelRes())
        assertEquals(R.string.score_exceptional, VisitDraftLogic.scoreBand(10f).labelRes())
    }

    @Test
    fun canPublishRequiresValidDateAndScore() {
        val valid = VisitDraft(overallScore = 8f, visitDate = today)
        assertTrue(VisitDraftLogic.canPublish(valid, today))

        val future = valid.copy(visitDate = today.plusDays(1))
        assertFalse(VisitDraftLogic.canPublish(future, today))
        assertEquals(R.string.visit_date_future_error, VisitDraftLogic.validateDateRes(future.visitDate, today))
    }

    @Test
    fun optionalReviewAndMemoryAreNotRequired() {
        val draft = VisitDraft(
            overallScore = 7.5f,
            publicReview = "",
            privateMemory = "",
            visitDate = today,
        )
        assertTrue(VisitDraftLogic.canPublish(draft, today))
    }

    @Test
    fun toVisitMapsFieldsAndTrimsText() {
        val draft = VisitDraft(
            overallScore = 8.24f,
            dimensions = mapOf(RatingDimension.FOOD to 9.15f),
            publicReview = "  Great cove  ",
            privateMemory = "  secret note  ",
            visitDate = LocalDate.of(2026, 7, 1),
            visibility = Visibility.FRIENDS,
        )
        val visit = VisitDraftLogic.toVisit(draft, "place-1", "user-1", visitId = "visit-1")

        assertEquals("visit-1", visit.id)
        assertEquals("place-1", visit.placeId)
        assertEquals(8.2, visit.overallRating, 0.001)
        assertEquals(9.2, visit.ratingDimensions[RatingDimension.FOOD]!!, 0.001)
        assertEquals("Great cove", visit.review)
        assertEquals("secret note", visit.personalNote)
        assertEquals(LocalDate.of(2026, 7, 1), visit.visitedAt)
        assertEquals(Visibility.FRIENDS, visit.visibility)
    }

    @Test
    fun toVisitMapsEachVisibilityToCreateDtoExactValues() {
        listOf(
            Visibility.PUBLIC to "PUBLIC",
            Visibility.FRIENDS to "FRIENDS",
            Visibility.PRIVATE to "PRIVATE",
        ).forEach { (visibility, expected) ->
            val visit = VisitDraftLogic.toVisit(
                VisitDraft(visibility = visibility, visitDate = today),
                placeId = "20000000-0000-0000-0000-000000000001",
                userId = "11111111-1111-1111-1111-111111111111",
            )
            assertEquals(visibility, visit.visibility)
            assertEquals(expected, visit.toCreateDto().visibility.name)
        }
    }

    @Test
    fun historicalDateIsAllowed() {
        val draft = VisitDraft(visitDate = today.minusYears(1))
        assertNull(VisitDraftLogic.validateDateRes(draft.visitDate, today))
        assertTrue(VisitDraftLogic.canPublish(draft, today))
    }

    @Test
    fun reviewHelperCopyMatchesVisibilitySemantics() {
        assertEquals(R.string.visibility_public_review_helper, VisitVisibilityCopy.reviewHelperRes(Visibility.PUBLIC))
        assertEquals(R.string.visibility_friends_review_helper, VisitVisibilityCopy.reviewHelperRes(Visibility.FRIENDS))
        assertEquals(R.string.visibility_private_review_helper, VisitVisibilityCopy.reviewHelperRes(Visibility.PRIVATE))
    }

    @Test
    fun impactHintCopyMatchesVisibilitySemantics() {
        assertEquals(R.string.visibility_public_impact, VisitVisibilityCopy.impactHintRes(Visibility.PUBLIC))
        assertEquals(R.string.visibility_friends_impact, VisitVisibilityCopy.impactHintRes(Visibility.FRIENDS))
        assertEquals(R.string.visibility_private_impact, VisitVisibilityCopy.impactHintRes(Visibility.PRIVATE))
    }

    @Test
    fun sheetDescriptionCopyMatchesVisibilitySemantics() {
        assertEquals(R.string.visibility_public_sheet, VisitVisibilityCopy.sheetDescriptionRes(Visibility.PUBLIC))
        assertEquals(R.string.visibility_friends_sheet, VisitVisibilityCopy.sheetDescriptionRes(Visibility.FRIENDS))
        assertEquals(R.string.visibility_private_sheet, VisitVisibilityCopy.sheetDescriptionRes(Visibility.PRIVATE))
    }
}
