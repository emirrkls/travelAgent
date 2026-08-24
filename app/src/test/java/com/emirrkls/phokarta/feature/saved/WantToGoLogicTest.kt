package com.emirrkls.phokarta.feature.saved

import com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource
import com.emirrkls.phokarta.core.model.SavedFriendMetrics
import com.emirrkls.phokarta.feature.search.SearchSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WantToGoLogicTest {
    private val places = MockPlaceCatalogDataSource.mockPlaces.take(3)
    private val a = places[0]
    private val b = places[1]
    private val c = places[2]

    @Test
    fun friendsVisitedFilterKeepsOnlyOverlap() {
        val metrics = mapOf(
            a.id to SavedFriendMetrics(9.2, 3),
            c.id to SavedFriendMetrics(null, 0),
        )
        val result = WantToGoLogic.filterAndSort(
            savedPlaces = listOf(a, b, c),
            savedOrder = listOf(a.id, b.id, c.id),
            friendMetrics = metrics,
            query = "",
            category = null,
            destination = null,
            highlyRatedOnly = false,
            friendsVisitedOnly = true,
            sort = SearchSort.RECENTLY_SAVED,
        )
        assertEquals(listOf(a.id), result.map { it.place.id })
        assertEquals(9.2, result.single().friendAverageScore)
        assertEquals(3, result.single().friendsVisitedCount)
    }

    @Test
    fun friendsScoreSort_nullsLast() {
        val metrics = mapOf(
            a.id to SavedFriendMetrics(8.0, 1),
            b.id to SavedFriendMetrics(9.5, 2),
            c.id to SavedFriendMetrics(null, 0),
        )
        val result = WantToGoLogic.filterAndSort(
            savedPlaces = listOf(a, b, c),
            savedOrder = listOf(a.id, b.id, c.id),
            friendMetrics = metrics,
            query = "",
            category = null,
            destination = null,
            highlyRatedOnly = false,
            friendsVisitedOnly = false,
            sort = SearchSort.FRIENDS_SCORE,
        )
        assertEquals(listOf(b.id, a.id, c.id), result.map { it.place.id })
    }

    @Test
    fun mostFriendsVisitedSort_usesCountThenScore() {
        val metrics = mapOf(
            a.id to SavedFriendMetrics(9.0, 1),
            b.id to SavedFriendMetrics(7.0, 3),
            c.id to SavedFriendMetrics(8.0, 3),
        )
        val result = WantToGoLogic.filterAndSort(
            savedPlaces = listOf(a, b, c),
            savedOrder = listOf(a.id, b.id, c.id),
            friendMetrics = metrics,
            query = "",
            category = null,
            destination = null,
            highlyRatedOnly = false,
            friendsVisitedOnly = false,
            sort = SearchSort.MOST_FRIENDS_VISITED,
        )
        assertEquals(listOf(c.id, b.id, a.id), result.map { it.place.id })
    }

    @Test
    fun zeroFriendSignalOmittedFromItemDefaults() {
        val result = WantToGoLogic.filterAndSort(
            savedPlaces = listOf(a),
            savedOrder = listOf(a.id),
            friendMetrics = emptyMap(),
            query = "",
            category = null,
            destination = null,
            highlyRatedOnly = false,
            friendsVisitedOnly = false,
            sort = SearchSort.RECENTLY_SAVED,
        )
        assertEquals(0, result.single().friendsVisitedCount)
        assertEquals(null, result.single().friendAverageScore)
    }

    @Test
    fun emptyCopyDistinguishesNoFriends() {
        assertEquals(
            com.emirrkls.phokarta.R.string.want_to_go_friends_empty_no_friends,
            WantToGoLogic.friendsVisitedEmptyMessageRes(hasFriends = false),
        )
        assertEquals(
            com.emirrkls.phokarta.R.string.want_to_go_friends_empty_has_friends,
            WantToGoLogic.friendsVisitedEmptyMessageRes(hasFriends = true),
        )
    }
}
