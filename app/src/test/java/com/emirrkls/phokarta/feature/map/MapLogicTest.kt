package com.emirrkls.phokarta.feature.map

import com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.SavedFriendMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLogicTest {
    private val places = MockPlaceCatalogDataSource.mockPlaces
    private val p1 = places.first { it.id == "p1" }
    private val p2 = places.first { it.id == "p2" }
    private val p4 = places.first { it.id == "p4" }

    @Test
    fun friendsVisitedMeansCountGreaterThanZero_notScore() {
        val metrics = mapOf(
            "p1" to SavedFriendMetrics(averageScore = null, friendsVisitedCount = 2),
            "p2" to SavedFriendMetrics(averageScore = 9.1, friendsVisitedCount = 0),
        )
        val result = filterMapPlaces(
            places = listOf(p1, p2),
            filters = MapFilters(friendsVisitedOnly = true),
            visitedPlaceIds = emptySet(),
            savedPlaceIds = emptySet(),
            viewport = null,
            friendMetrics = metrics,
        )
        assertEquals(listOf("p1"), result.map { it.id })
    }

    @Test
    fun friendsVisitedCombinesWithCategory() {
        val metrics = mapOf(
            "p1" to SavedFriendMetrics(9.0, 1),
            "p2" to SavedFriendMetrics(8.0, 1),
        )
        val result = filterMapPlaces(
            places = listOf(p1, p2),
            filters = MapFilters(category = PlaceCategory.BEACH, friendsVisitedOnly = true),
            visitedPlaceIds = emptySet(),
            savedPlaceIds = emptySet(),
            viewport = null,
            friendMetrics = metrics,
        )
        assertEquals(listOf("p1"), result.map { it.id })
    }

    @Test
    fun friendsVisitedCombinesWithWantToGo() {
        val metrics = mapOf("p1" to SavedFriendMetrics(9.1, 1), "p2" to SavedFriendMetrics(8.0, 1))
        val result = filterMapPlaces(
            places = listOf(p1, p2),
            filters = MapFilters(friendsVisitedOnly = true, wantToGoOnly = true),
            visitedPlaceIds = emptySet(),
            savedPlaceIds = setOf("p1"),
            viewport = null,
            friendMetrics = metrics,
        )
        assertEquals(listOf("p1"), result.map { it.id })
    }

    @Test
    fun friendsVisitedCombinesWithVisited() {
        val metrics = mapOf("p1" to SavedFriendMetrics(9.1, 1), "p2" to SavedFriendMetrics(8.0, 1))
        val result = filterMapPlaces(
            places = listOf(p1, p2),
            filters = MapFilters(friendsVisitedOnly = true, visitedOnly = true),
            visitedPlaceIds = setOf("p2"),
            savedPlaceIds = emptySet(),
            viewport = null,
            friendMetrics = metrics,
        )
        assertEquals(listOf("p2"), result.map { it.id })
    }

    @Test
    fun friendsVisitedPlusNinePlusUsesCommunityScore() {
        val metrics = mapOf(
            "p1" to SavedFriendMetrics(6.0, 1),
            "p2" to SavedFriendMetrics(10.0, 1),
        )
        val result = filterMapPlaces(
            places = listOf(p1, p2),
            filters = MapFilters(friendsVisitedOnly = true, highlyRatedOnly = true),
            visitedPlaceIds = emptySet(),
            savedPlaceIds = emptySet(),
            viewport = null,
            friendMetrics = metrics,
        )
        assertEquals(listOf("p1"), result.map { it.id })
        assertTrue((p1.communityScore ?: 0.0) >= 9.0)
        assertTrue((p2.communityScore ?: 0.0) < 9.0)
    }

    @Test
    fun clearFiltersRemovesFriendsVisited() {
        val filtered = MapFilters(friendsVisitedOnly = true, visitedOnly = true, category = PlaceCategory.BEACH)
        assertEquals(3, filtered.activeCount)
        assertEquals(0, MapFilters().activeCount)
        assertFalse(MapFilters().friendsVisitedOnly)
    }

    @Test
    fun filtersUseCatalogAndPersistedStateInputsTogether() {
        val result = filterMapPlaces(
            places = places,
            filters = MapFilters(
                category = PlaceCategory.RESTAURANT,
                highlyRatedOnly = true,
                friendsVisitedOnly = true,
                visitedOnly = true,
                wantToGoOnly = true,
            ),
            visitedPlaceIds = setOf("p2", "p4"),
            savedPlaceIds = setOf("p4"),
            viewport = null,
            friendMetrics = mapOf("p4" to SavedFriendMetrics(8.0, 1)),
        )

        assertEquals(listOf("p4"), result.map { it.id })
    }

    @Test
    fun viewportSupportsNormalAndDateLineBounds() {
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

    @Test
    fun friendSignalShowsScoreAndCount() {
        val both = mapFriendSignal(SavedFriendMetrics(9.2, 3))
        assertTrue(both.showScore)
        assertTrue(both.hasSignal)
        assertEquals(9.2, both.friendAverageScore)
        assertEquals(3, both.friendsVisitedCount)

        val countOnly = mapFriendSignal(SavedFriendMetrics(null, 3))
        assertTrue(countOnly.hasSignal)
        assertFalse(countOnly.showScore)
        assertNull(countOnly.friendAverageScore)

        val none = mapFriendSignal(SavedFriendMetrics(9.9, 0))
        assertFalse(none.hasSignal)
        assertFalse(none.showScore)
        assertNull(mapFriendSignal(null).friendAverageScore)
    }
}
