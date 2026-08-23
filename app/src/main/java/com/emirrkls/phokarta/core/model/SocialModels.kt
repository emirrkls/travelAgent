package com.emirrkls.phokarta.core.model

data class RelationshipState(
    val isFollowing: Boolean,
    val followsYou: Boolean,
    val isFriend: Boolean = isFollowing && followsYou,
)

data class UserSummary(
    val id: String,
    val displayName: String,
    val username: String,
    val avatarUrl: String,
    val relationship: RelationshipState? = null,
)

data class PublicUserProfile(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val bio: String,
    val cityCount: Int,
    val countryCount: Int,
    val followerCount: Long,
    val followingCount: Long,
    val friendCount: Long,
    val relationship: RelationshipState? = null,
)

data class UserPage(
    val items: List<UserSummary>,
    val page: Int,
    val totalPages: Int,
    val totalElements: Long,
    val hasNext: Boolean,
)

enum class SocialListKind {
    FOLLOWERS,
    FOLLOWING,
    FRIENDS,
    ;

    val routeValue: String
        get() = name.lowercase()

    companion object {
        fun fromRoute(value: String): SocialListKind =
            entries.first { it.routeValue == value.lowercase() }
    }
}

data class OwnerSocialCounts(
    val followerCount: Long,
    val followingCount: Long,
    val friendCount: Long,
)
