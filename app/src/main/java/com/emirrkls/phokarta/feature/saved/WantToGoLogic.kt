package com.emirrkls.phokarta.feature.saved

import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.SavedFriendMetrics
import com.emirrkls.phokarta.feature.search.SearchLogic
import com.emirrkls.phokarta.feature.search.SearchSort

data class WantToGoItem(
    val place: Place,
    val friendAverageScore: Double?,
    val friendsVisitedCount: Int,
)

object WantToGoLogic {
    fun filterAndSort(
        savedPlaces: List<Place>,
        savedOrder: List<String>,
        friendMetrics: Map<String, SavedFriendMetrics>,
        query: String,
        category: PlaceCategory?,
        destination: String?,
        highlyRatedOnly: Boolean,
        friendsVisitedOnly: Boolean,
        sort: SearchSort,
    ): List<WantToGoItem> {
        var filtered = savedPlaces
        category?.let { cat -> filtered = filtered.filter { it.category == cat } }
        destination?.let { city ->
            filtered = filtered.filter { it.city.equals(city, ignoreCase = true) }
        }
        if (highlyRatedOnly) {
            filtered = filtered.filter {
                (it.communityScore ?: Double.NEGATIVE_INFINITY) >= SearchLogic.HIGHLY_RATED_MIN
            }
        }
        val needle = query.trim()
        if (needle.isNotEmpty()) {
            filtered = filtered.filter { SearchLogic.matchesQuery(it, needle) }
        }
        val items = filtered.map { place ->
            val metrics = friendMetrics[place.id]
            WantToGoItem(
                place = place,
                friendAverageScore = metrics?.averageScore,
                friendsVisitedCount = metrics?.friendsVisitedCount ?: 0,
            )
        }.let { mapped ->
            if (friendsVisitedOnly) mapped.filter { it.friendsVisitedCount > 0 } else mapped
        }
        val sortedPlaces = SearchLogic.sort(
            places = items.map { it.place },
            sort = sort,
            savedOrder = savedOrder,
            friendMetrics = friendMetrics,
        )
        val byId = items.associateBy { it.place.id }
        return sortedPlaces.mapNotNull { byId[it.id] }
    }

    fun friendsVisitedEmptyMessage(hasFriends: Boolean?): String = when (hasFriends) {
        false -> "When you become friends with people on Phokarta, their visits can help you choose from your list."
        else -> "Your friends haven't visited anything on this list yet."
    }
}
