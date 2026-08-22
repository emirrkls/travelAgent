package com.emirrkls.phokarta.feature.map

import com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource
import com.emirrkls.phokarta.core.model.PlaceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLogicTest {
    private val places = MockPlaceCatalogDataSource.mockPlaces

    @Test
    fun filtersUseCatalogAndPersistedStateInputsTogether() {
        val result = filterMapPlaces(
            places = places,
            filters = MapFilters(
                category = PlaceCategory.RESTAURANT,
                highlyRatedOnly = true,
                trustedOnly = true,
                visitedOnly = true,
                wantToGoOnly = true,
            ),
            visitedPlaceIds = setOf("p2", "p4"),
            savedPlaceIds = setOf("p4"),
            viewport = null,
        )

        assertEquals(listOf("p4"), result.map { it.id })
    }

    @Test
    fun viewportSupportsNormalAndDateLineBounds() {
        val p1 = places.first()
        val nearby = MapViewport(38.0, 29.0, 36.0, 27.0, 37.0, 28.0, 10f)
        val dateLine = MapViewport(80.0, -170.0, -80.0, 170.0, 0.0, 180.0, 2f)

        assertTrue(nearby.contains(p1))
        assertFalse(dateLine.contains(p1))
    }

    @Test
    fun searchAreaRequiresMeaningfulPanOrZoom() {
        val applied = MapViewport(38.0, 29.0, 36.0, 27.0, 37.0, 28.0, 10f)
        val tinyPan = applied.copy(centerLatitude = 37.05, centerLongitude = 28.04)
        val usefulPan = applied.copy(centerLatitude = 37.5, centerLongitude = 28.5)
        val usefulZoom = applied.copy(zoom = 10.6f)

        assertFalse(viewportMovedEnough(applied, tinyPan))
        assertTrue(viewportMovedEnough(applied, usefulPan))
        assertTrue(viewportMovedEnough(applied, usefulZoom))
    }
}
