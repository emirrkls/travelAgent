package com.emirrkls.phokarta.core.model

import java.time.LocalDate

object VisitStateLogic {
    fun visitedPlaceIds(visits: List<Visit>): Set<String> = visits.map { it.placeId }.toSet()

    fun visitsForPlace(visits: List<Visit>, placeId: String): List<Visit> =
        visits.filter { it.placeId == placeId }.sortedByDescending { it.visitedAt }

    fun visitCount(visits: List<Visit>, placeId: String): Int =
        visits.count { it.placeId == placeId }

    fun newestVisit(visits: List<Visit>, placeId: String): Visit? =
        visitsForPlace(visits, placeId).firstOrNull()

    fun isVisited(visits: List<Visit>, placeId: String): Boolean =
        visits.any { it.placeId == placeId }

    data class ProfileVisitSummary(
        val totalVisits: Int,
        val placesVisited: Int,
        val averageGivenScore: Double?,
    )

    fun profileSummary(visits: List<Visit>): ProfileVisitSummary {
        if (visits.isEmpty()) {
            return ProfileVisitSummary(totalVisits = 0, placesVisited = 0, averageGivenScore = null)
        }
        return ProfileVisitSummary(
            totalVisits = visits.size,
            placesVisited = visits.map { it.placeId }.distinct().size,
            averageGivenScore = visits.map { it.overallRating }.average(),
        )
    }

    fun sortedNewestFirst(visits: List<Visit>): List<Visit> =
        visits.sortedWith(compareByDescending<Visit> { it.visitedAt }.thenByDescending { it.id })

    fun repeatVisitLabel(count: Int): String? = when {
        count <= 1 -> null
        count == 2 -> "Visited twice"
        else -> "Visited $count times"
    }
}

enum class MapMarkerBadge { NONE, SAVED, VISITED, BOTH }

object MapMarkerLogic {
    fun badge(saved: Boolean, visited: Boolean): MapMarkerBadge = when {
        saved && visited -> MapMarkerBadge.BOTH
        saved -> MapMarkerBadge.SAVED
        visited -> MapMarkerBadge.VISITED
        else -> MapMarkerBadge.NONE
    }
}
