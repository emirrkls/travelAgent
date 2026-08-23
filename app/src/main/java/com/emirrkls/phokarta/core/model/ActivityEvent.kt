package com.emirrkls.phokarta.core.model

import java.time.LocalDate

data class ActivityAuthor(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
)

data class ActivityPlaceSummary(
    val id: String,
    val name: String,
    val category: PlaceCategory,
    val city: String,
    val coverImage: String,
)

/**
 * Public cross-place visit event for the Activity feed.
 * Structurally excludes privateMemory / personalNote.
 */
data class ActivityEvent(
    val visitId: String,
    val author: ActivityAuthor,
    val place: ActivityPlaceSummary,
    val overallScore: Double,
    val publicReview: String,
    val visitDate: LocalDate,
)

data class ActivityFeedPage(
    val items: List<ActivityEvent>,
    val page: Int,
    val totalPages: Int,
    val totalElements: Long,
    val hasNext: Boolean,
)
