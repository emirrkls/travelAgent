package com.emirrkls.phokarta.core.model

import android.content.res.Resources
import com.emirrkls.phokarta.R

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

    fun repeatVisitCopy(count: Int): RepeatVisitCopy? = when {
        count <= 1 -> null
        count == 2 -> RepeatVisitCopy.Twice
        else -> RepeatVisitCopy.Times(count)
    }
}

sealed interface RepeatVisitCopy {
    data object Twice : RepeatVisitCopy
    data class Times(val count: Int) : RepeatVisitCopy
}

enum class MapMarkerBadge { NONE, SAVED, VISITED, BOTH }

data class MapMarkerFlags(
    val saved: Boolean,
    val visited: Boolean,
    val friendsVisited: Boolean,
)

object MapMarkerLogic {
    fun badge(saved: Boolean, visited: Boolean): MapMarkerBadge = when {
        saved && visited -> MapMarkerBadge.BOTH
        saved -> MapMarkerBadge.SAVED
        visited -> MapMarkerBadge.VISITED
        else -> MapMarkerBadge.NONE
    }

    fun flags(saved: Boolean, visited: Boolean, friendsVisited: Boolean) =
        MapMarkerFlags(saved, visited, friendsVisited)

    fun contentDescription(
        placeName: String,
        communityScore: String,
        flags: MapMarkerFlags,
        resources: Resources,
    ): String = buildString {
        append(resources.getString(R.string.a11y_map_marker_rated, placeName, communityScore))
        if (flags.saved) append(resources.getString(R.string.a11y_map_marker_saved))
        if (flags.visited) append(resources.getString(R.string.a11y_map_marker_visited))
        if (flags.friendsVisited) append(resources.getString(R.string.a11y_map_marker_friends_visited))
    }
}
