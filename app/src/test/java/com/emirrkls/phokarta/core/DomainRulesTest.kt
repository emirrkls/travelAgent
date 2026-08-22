package com.emirrkls.phokarta.core

import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.RatingDimension
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRulesTest {
    @Test
    fun `rating dimensions depend on place category`() {
        assertTrue(RatingDimension.SEA in PlaceCategory.BEACH.ratingDimensions)
        assertTrue(RatingDimension.FOOD in PlaceCategory.RESTAURANT.ratingDimensions)
        assertTrue(RatingDimension.ROOM in PlaceCategory.HOTEL.ratingDimensions)
        assertNotEquals(PlaceCategory.BEACH.ratingDimensions, PlaceCategory.HOTEL.ratingDimensions)
    }
}
