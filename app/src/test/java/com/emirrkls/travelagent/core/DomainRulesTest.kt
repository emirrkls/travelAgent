package com.emirrkls.travelagent.core

import com.emirrkls.travelagent.core.data.MockTravelRepository
import com.emirrkls.travelagent.core.model.PlaceCategory
import com.emirrkls.travelagent.core.model.Visit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DomainRulesTest {
    @Test
    fun `rating dimensions depend on place category`() {
        assertTrue("Sea" in PlaceCategory.BEACH.ratingDimensions)
        assertTrue("Food" in PlaceCategory.RESTAURANT.ratingDimensions)
        assertTrue("Room" in PlaceCategory.HOTEL.ratingDimensions)
        assertNotEquals(PlaceCategory.BEACH.ratingDimensions, PlaceCategory.HOTEL.ratingDimensions)
    }

    @Test
    fun `publishing a visit preserves historical visits`() = runBlocking {
        val repository = MockTravelRepository()
        val before = repository.observeVisits().first()
        val newVisit = Visit(
            id = "new-visit",
            userId = MockTravelRepository.CURRENT_USER_ID,
            placeId = "p1",
            visitedAt = LocalDate.of(2026, 8, 21),
            overallRating = 9.3,
            ratingDimensions = mapOf("Sea" to 9.6),
            review = "A clear-water morning.",
            personalNote = "Return in September.",
        )

        repository.publishVisit(newVisit)

        val after = repository.observeVisits().first()
        assertEquals(before.size + 1, after.size)
        assertEquals(newVisit, after.first())
        assertTrue(after.containsAll(before))
    }
}
