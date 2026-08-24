package com.emirrkls.phokarta.feature.search

import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.SavedFriendMetrics

enum class SearchSort {
    DEFAULT,
    RATING,
    RECENTLY_SAVED,
    FRIENDS_SCORE,
    MOST_FRIENDS_VISITED,
}

data class SearchFilters(
    val category: PlaceCategory? = null,
    val savedOnly: Boolean = false,
    val visitedOnly: Boolean = false,
    val highlyRatedOnly: Boolean = false,
    val sort: SearchSort = SearchSort.DEFAULT,
) {
    val hasActiveFilters: Boolean
        get() = category != null || savedOnly || visitedOnly || highlyRatedOnly || sort != SearchSort.DEFAULT

    fun clear() = SearchFilters()
}

enum class SearchEmptyReason {
    NO_RESULTS,
    NOTHING_SAVED,
    NOTHING_VISITED,
}

object SearchLogic {
    const val HIGHLY_RATED_MIN = 9.0

    fun usesLocalSource(filters: SearchFilters): Boolean =
        filters.savedOnly || filters.visitedOnly

    fun serverMinRating(filters: SearchFilters): Double? =
        if (filters.highlyRatedOnly) HIGHLY_RATED_MIN else null

    fun serverSort(sort: SearchSort): String = "averageScore,desc"

    fun filterAndSort(
        places: List<Place>,
        query: String,
        filters: SearchFilters,
        savedOrder: List<String>,
        visitedPlaceIds: Set<String>,
    ): List<Place> {
        var result = when {
            filters.savedOnly -> savedOrder.mapNotNull { id -> places.firstOrNull { it.id == id } }
            filters.visitedOnly -> places.filter { it.id in visitedPlaceIds }
            else -> places
        }
        if (filters.savedOnly && filters.visitedOnly) {
            result = result.filter { it.id in visitedPlaceIds }
        }
        filters.category?.let { category ->
            result = result.filter { it.category == category }
        }
        if (filters.highlyRatedOnly) {
            result = result.filter { (it.communityScore ?: Double.NEGATIVE_INFINITY) >= HIGHLY_RATED_MIN }
        }
        val needle = query.trim()
        if (needle.isNotEmpty()) {
            result = result.filter { matchesQuery(it, needle) }
        }
        return sort(result, filters.sort, savedOrder)
    }

    fun sort(
        places: List<Place>,
        sort: SearchSort,
        savedOrder: List<String>,
        friendMetrics: Map<String, SavedFriendMetrics> = emptyMap(),
    ): List<Place> = when (sort) {
        SearchSort.DEFAULT -> places
        SearchSort.RATING -> places.sortedByDescending { it.communityScore ?: Double.NEGATIVE_INFINITY }
        SearchSort.RECENTLY_SAVED -> {
            val rank = savedOrder.withIndex().associate { it.value to it.index }
            places.sortedWith(
                compareBy<Place> { rank[it.id] ?: Int.MAX_VALUE }
                    .thenBy { it.id },
            )
        }
        SearchSort.FRIENDS_SCORE -> places.sortedWith(
            compareByDescending<Place> { friendMetrics[it.id]?.averageScore ?: Double.NEGATIVE_INFINITY }
                .thenByDescending { friendMetrics[it.id]?.friendsVisitedCount ?: 0 }
                .thenBy { it.id },
        )
        SearchSort.MOST_FRIENDS_VISITED -> places.sortedWith(
            compareByDescending<Place> { friendMetrics[it.id]?.friendsVisitedCount ?: 0 }
                .thenByDescending { friendMetrics[it.id]?.averageScore ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.id },
        )
    }

    fun matchesQuery(place: Place, needle: String): Boolean =
        place.name.contains(needle, ignoreCase = true) ||
            place.city.contains(needle, ignoreCase = true) ||
            place.region.contains(needle, ignoreCase = true) ||
            place.category.label.contains(needle, ignoreCase = true)

    fun emptyReason(
        results: List<Place>,
        filters: SearchFilters,
        savedCount: Int,
        visitedCount: Int,
        isLoading: Boolean,
        hasError: Boolean,
    ): SearchEmptyReason? {
        if (isLoading || hasError || results.isNotEmpty()) return null
        return when {
            filters.savedOnly && savedCount == 0 -> SearchEmptyReason.NOTHING_SAVED
            filters.visitedOnly && visitedCount == 0 -> SearchEmptyReason.NOTHING_VISITED
            else -> SearchEmptyReason.NO_RESULTS
        }
    }
}
