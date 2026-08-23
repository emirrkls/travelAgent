package com.emirrkls.phokarta.core.model

import java.time.LocalDate

data class FriendPlaceUser(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val latestScore: Double,
    val latestVisitedAt: LocalDate,
)

data class FriendPlaceSummary(
    val averageScore: Double?,
    val friendsVisitedCount: Int,
    val friends: List<FriendPlaceUser>,
)
