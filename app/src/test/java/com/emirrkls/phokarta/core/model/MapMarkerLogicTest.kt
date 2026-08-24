package com.emirrkls.phokarta.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMarkerLogicTest {
    @Test
    fun badgeReflectsSavedVisitedCombinations() {
        assertEquals(MapMarkerBadge.NONE, MapMarkerLogic.badge(saved = false, visited = false))
        assertEquals(MapMarkerBadge.SAVED, MapMarkerLogic.badge(saved = true, visited = false))
        assertEquals(MapMarkerBadge.VISITED, MapMarkerLogic.badge(saved = false, visited = true))
        assertEquals(MapMarkerBadge.BOTH, MapMarkerLogic.badge(saved = true, visited = true))
    }

    @Test
    fun flagsKeepSavedVisitedFriendsIndependent() {
        val none = MapMarkerLogic.flags(saved = false, visited = false, friendsVisited = false)
        val saved = MapMarkerLogic.flags(true, false, false)
        val visited = MapMarkerLogic.flags(false, true, false)
        val friends = MapMarkerLogic.flags(false, false, true)
        val savedVisited = MapMarkerLogic.flags(true, true, false)
        val savedFriends = MapMarkerLogic.flags(true, false, true)
        val visitedFriends = MapMarkerLogic.flags(false, true, true)
        val all = MapMarkerLogic.flags(true, true, true)

        assertFalse(none.saved || none.visited || none.friendsVisited)
        assertTrue(saved.saved && !saved.visited && !saved.friendsVisited)
        assertTrue(visited.visited && !visited.saved && !visited.friendsVisited)
        assertTrue(friends.friendsVisited && !friends.saved && !friends.visited)
        assertTrue(savedVisited.saved && savedVisited.visited && !savedVisited.friendsVisited)
        assertTrue(savedFriends.saved && savedFriends.friendsVisited && !savedFriends.visited)
        assertTrue(visitedFriends.visited && visitedFriends.friendsVisited && !visitedFriends.saved)
        assertTrue(all.saved && all.visited && all.friendsVisited)
    }
}
