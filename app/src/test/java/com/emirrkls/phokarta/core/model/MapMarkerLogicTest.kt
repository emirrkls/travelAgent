package com.emirrkls.phokarta.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MapMarkerLogicTest {
    @Test
    fun badgeReflectsSavedVisitedCombinations() {
        assertEquals(MapMarkerBadge.NONE, MapMarkerLogic.badge(saved = false, visited = false))
        assertEquals(MapMarkerBadge.SAVED, MapMarkerLogic.badge(saved = true, visited = false))
        assertEquals(MapMarkerBadge.VISITED, MapMarkerLogic.badge(saved = false, visited = true))
        assertEquals(MapMarkerBadge.BOTH, MapMarkerLogic.badge(saved = true, visited = true))
    }
}
