package com.emirrkls.phokarta.core.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitStateLogicTest {
    private val placeId = "p1"

    private fun visit(id: String, date: LocalDate, score: Double = 8.0) = Visit(
        id = id,
        userId = "u1",
        placeId = placeId,
        visitedAt = date,
        overallRating = score,
        ratingDimensions = emptyMap(),
        review = "",
        personalNote = "",
    )

    @Test
    fun visitedStateAndCounts() {
        assertFalse(VisitStateLogic.isVisited(emptyList(), placeId))
        assertEquals(0, VisitStateLogic.visitCount(emptyList(), placeId))

        val one = listOf(visit("v1", LocalDate.of(2026, 8, 1)))
        assertTrue(VisitStateLogic.isVisited(one, placeId))
        assertEquals(1, VisitStateLogic.visitCount(one, placeId))
        assertEquals(setOf(placeId), VisitStateLogic.visitedPlaceIds(one))

        val many = listOf(
            visit("v1", LocalDate.of(2026, 6, 1), 8.2),
            visit("v2", LocalDate.of(2026, 8, 23), 9.0),
            visit("v3", LocalDate.of(2026, 7, 15), 7.8),
        )
        assertEquals(3, VisitStateLogic.visitCount(many, placeId))
        assertEquals("v2", VisitStateLogic.newestVisit(many, placeId)?.id)
        assertEquals(listOf("v2", "v3", "v1"), VisitStateLogic.visitsForPlace(many, placeId).map { it.id })
    }

    @Test
    fun profileSummaryAndRepeatLabels() {
        val visits = listOf(
            visit("v1", LocalDate.of(2026, 8, 1), 8.0),
            visit("v2", LocalDate.of(2026, 8, 2), 9.0),
            visit("v3", LocalDate.of(2026, 8, 3), 7.0).copy(placeId = "p2"),
        )
        val summary = VisitStateLogic.profileSummary(visits)
        assertEquals(3, summary.totalVisits)
        assertEquals(2, summary.placesVisited)
        assertEquals(8.0, summary.averageGivenScore!!, 0.001)

        assertNull(VisitStateLogic.repeatVisitCopy(1))
        assertEquals(RepeatVisitCopy.Twice, VisitStateLogic.repeatVisitCopy(2))
        assertEquals(RepeatVisitCopy.Times(3), VisitStateLogic.repeatVisitCopy(3))
    }

    @Test
    fun sortedNewestFirstUsesVisitDateThenId() {
        val visits = listOf(
            visit("a", LocalDate.of(2026, 8, 1)),
            visit("b", LocalDate.of(2026, 8, 23)),
            visit("c", LocalDate.of(2026, 8, 23)),
        )
        assertEquals(listOf("c", "b", "a"), VisitStateLogic.sortedNewestFirst(visits).map { it.id })
    }
}
