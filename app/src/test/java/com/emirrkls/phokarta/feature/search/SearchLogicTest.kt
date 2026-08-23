package com.emirrkls.phokarta.feature.search

import com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource
import com.emirrkls.phokarta.core.model.PlaceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchLogicTest {
    private val places = MockPlaceCatalogDataSource.mockPlaces

    @Test
    fun savedAndCategoryCombine() {
        val beach = places.filter { it.category == PlaceCategory.BEACH }
        val savedOrder = beach.map { it.id } + places.first { it.category != PlaceCategory.BEACH }.id
        val result = SearchLogic.filterAndSort(
            places = places,
            query = "",
            filters = SearchFilters(category = PlaceCategory.BEACH, savedOnly = true),
            savedOrder = savedOrder,
            visitedPlaceIds = emptySet(),
        )
        assertTrue(result.all { it.category == PlaceCategory.BEACH })
        assertEquals(beach.map { it.id }, result.map { it.id })
    }

    @Test
    fun highlyRatedNeverTreatsNullAsZero() {
        val unrated = places.first().copy(communityScore = null, id = "unrated")
        val high = places.first().copy(communityScore = 9.2, id = "high")
        val result = SearchLogic.filterAndSort(
            places = listOf(unrated, high),
            query = "",
            filters = SearchFilters(highlyRatedOnly = true),
            savedOrder = emptyList(),
            visitedPlaceIds = emptySet(),
        )
        assertEquals(listOf("high"), result.map { it.id })
    }

    @Test
    fun visitedAndSavedRemainIndependent() {
        val placeId = places.first().id
        val visitedOnly = SearchLogic.filterAndSort(
            places = places,
            query = "",
            filters = SearchFilters(visitedOnly = true),
            savedOrder = emptyList(),
            visitedPlaceIds = setOf(placeId),
        )
        assertEquals(listOf(placeId), visitedOnly.map { it.id })

        val savedOnly = SearchLogic.filterAndSort(
            places = places,
            query = "",
            filters = SearchFilters(savedOnly = true),
            savedOrder = listOf(placeId),
            visitedPlaceIds = emptySet(),
        )
        assertEquals(listOf(placeId), savedOnly.map { it.id })
    }

    @Test
    fun recentlySavedUsesExplicitOrder() {
        val a = places[0]
        val b = places[1]
        val sorted = SearchLogic.sort(listOf(a, b), SearchSort.RECENTLY_SAVED, listOf(b.id, a.id))
        assertEquals(listOf(b.id, a.id), sorted.map { it.id })
    }

    @Test
    fun emptyReasons() {
        assertEquals(
            SearchEmptyReason.NOTHING_SAVED,
            SearchLogic.emptyReason(emptyList(), SearchFilters(savedOnly = true), 0, 0, false, false),
        )
        assertEquals(
            SearchEmptyReason.NOTHING_VISITED,
            SearchLogic.emptyReason(emptyList(), SearchFilters(visitedOnly = true), 1, 0, false, false),
        )
        assertEquals(
            SearchEmptyReason.NO_RESULTS,
            SearchLogic.emptyReason(emptyList(), SearchFilters(savedOnly = true), 2, 0, false, false),
        )
        assertNull(SearchLogic.emptyReason(places.take(1), SearchFilters(), 0, 0, false, false))
    }

    @Test
    fun clearFiltersResetsState() {
        val filters = SearchFilters(
            category = PlaceCategory.CAFE,
            savedOnly = true,
            visitedOnly = true,
            highlyRatedOnly = true,
            sort = SearchSort.RATING,
        )
        assertEquals(SearchFilters(), filters.clear())
    }
}
