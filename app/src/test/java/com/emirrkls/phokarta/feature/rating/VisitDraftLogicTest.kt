package com.emirrkls.phokarta.feature.rating

import com.emirrkls.phokarta.core.model.RatingDimension
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitDraftLogicTest {
    private val today = LocalDate.of(2026, 8, 23)

    @Test
    fun scoreLabelsCoverAnchors() {
        assertEquals("Terrible", VisitDraftLogic.scoreLabel(0f))
        assertEquals("Okay", VisitDraftLogic.scoreLabel(5f))
        assertEquals("Good", VisitDraftLogic.scoreLabel(7f))
        assertEquals("Amazing", VisitDraftLogic.scoreLabel(9f))
        assertEquals("Exceptional", VisitDraftLogic.scoreLabel(10f))
    }

    @Test
    fun canPublishRequiresValidDateAndScore() {
        val valid = VisitDraft(overallScore = 8f, visitDate = today)
        assertTrue(VisitDraftLogic.canPublish(valid, today))

        val future = valid.copy(visitDate = today.plusDays(1))
        assertFalse(VisitDraftLogic.canPublish(future, today))
        assertEquals("Visit date can't be in the future.", VisitDraftLogic.validateDate(future.visitDate, today))
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
        )
        val visit = VisitDraftLogic.toVisit(draft, "place-1", "user-1", visitId = "visit-1")

        assertEquals("visit-1", visit.id)
        assertEquals("place-1", visit.placeId)
        assertEquals(8.2, visit.overallRating, 0.001)
        assertEquals(9.2, visit.ratingDimensions[RatingDimension.FOOD]!!, 0.001)
        assertEquals("Great cove", visit.review)
        assertEquals("secret note", visit.personalNote)
        assertEquals(LocalDate.of(2026, 7, 1), visit.visitedAt)
    }

    @Test
    fun historicalDateIsAllowed() {
        val draft = VisitDraft(visitDate = today.minusYears(1))
        assertNull(VisitDraftLogic.validateDate(draft.visitDate, today))
        assertTrue(VisitDraftLogic.canPublish(draft, today))
    }
}
