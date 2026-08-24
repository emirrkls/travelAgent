package com.emirrkls.phokarta.core.network.mapper

import com.emirrkls.phokarta.core.model.ActivityEvent
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.network.model.PlaceCategoryDto
import com.emirrkls.phokarta.core.network.model.PublicActivityAuthorDto
import com.emirrkls.phokarta.core.network.model.PublicActivityDto
import com.emirrkls.phokarta.core.network.model.PublicActivityPlaceDto
import com.emirrkls.phokarta.feature.rating.VisitDraftLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ActivityMappingTest {
    private val dto = PublicActivityDto(
        visitId = "30000000-0000-0000-0000-000000000001",
        author = PublicActivityAuthorDto(
            id = "11111111-1111-1111-1111-111111111111",
            username = "emir_demo",
            displayName = "Emir Kaya",
            avatarUrl = null,
        ),
        place = PublicActivityPlaceDto(
            id = "20000000-0000-0000-0000-000000000003",
            name = "Sarnıç Cove",
            category = PlaceCategoryDto.BEACH,
            city = "Bodrum",
            coverImage = "https://example.test/cover.jpg",
        ),
        overallScore = 9.2,
        publicReview = "Great atmosphere and very good service.",
        visitedAt = "2026-08-12",
    )

    @Test
    fun `maps public activity dto to domain without private fields`() {
        val event: ActivityEvent = dto.toActivityEvent()

        assertEquals(dto.visitId.lowercase(), event.visitId)
        assertEquals("Emir Kaya", event.author.displayName)
        assertEquals("Sarnıç Cove", event.place.name)
        assertEquals(PlaceCategory.BEACH, event.place.category)
        assertEquals("Bodrum", event.place.city)
        assertEquals(9.2, event.overallScore, 0.001)
        assertEquals("Great atmosphere and very good service.", event.publicReview)
        assertEquals(LocalDate.of(2026, 8, 12), event.visitDate)
        assertEquals(com.emirrkls.phokarta.ui.localization.ScoreBand.AMAZING, VisitDraftLogic.scoreBand(event.overallScore.toFloat()))
    }

    @Test
    fun `activity event model has no private memory fields`() {
        val fields = ActivityEvent::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(fields.contains("privateMemory"))
        assertFalse(fields.contains("personalNote"))
        assertFalse(fields.contains("email"))
    }

    @Test
    fun `blank display name falls back to username`() {
        val mapped = dto.copy(
            author = dto.author.copy(displayName = "", username = "traveler_one"),
        ).toActivityEvent()
        assertEquals("traveler_one", mapped.author.displayName)
    }

    @Test
    fun `blank public review is preserved for rating-only events`() {
        assertTrue(dto.copy(publicReview = "").toActivityEvent().publicReview.isEmpty())
    }
}
