package com.emirrkls.travelagent.core

import com.emirrkls.travelagent.core.model.PlaceCategory
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRulesTest {
    @Test
    fun `rating dimensions depend on place category`() {
        assertTrue("Sea" in PlaceCategory.BEACH.ratingDimensions)
        assertTrue("Food" in PlaceCategory.RESTAURANT.ratingDimensions)
        assertTrue("Room" in PlaceCategory.HOTEL.ratingDimensions)
        assertNotEquals(PlaceCategory.BEACH.ratingDimensions, PlaceCategory.HOTEL.ratingDimensions)
    }
}
