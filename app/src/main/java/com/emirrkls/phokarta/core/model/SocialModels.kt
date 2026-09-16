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
        get() = name.lowercase(java.util.Locale.ROOT)

    companion object {
        fun fromRoute(value: String): SocialListKind =
            entries.first { it.routeValue == value.lowercase(java.util.Locale.ROOT) }
    }
}

data class OwnerSocialCounts(
    val followerCount: Long,
    val followingCount: Long,
    val friendCount: Long,
)

enum class ProfileVisibilityV2 {
    PUBLIC,
    PRIVATE,
    UNKNOWN,
    ;

    companion object {
        fun fromWire(raw: String): ProfileVisibilityV2 =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

enum class RelationshipActionState {
    NONE,
    REQUEST_PENDING,
    FOLLOWING,
    FRIENDS,
    UNAVAILABLE,
    UNKNOWN,
    ;

    companion object {
        fun fromWire(raw: String): RelationshipActionState =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

data class RelationshipV2(
    val state: RelationshipActionState,
    val followsYou: Boolean,
    val canFollow: Boolean,
    val canCancelRequest: Boolean,
) {
    val isFriend: Boolean get() = state == RelationshipActionState.FRIENDS
    val isPending: Boolean get() = state == RelationshipActionState.REQUEST_PENDING

    fun invalidatedByBlock(): RelationshipV2 = RelationshipV2(
        state = RelationshipActionState.UNAVAILABLE,
        followsYou = false,
        canFollow = false,
        canCancelRequest = false,
    )
}

data class ProfileV2(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val bio: String,
    val visibility: ProfileVisibilityV2,
    val fullProfile: Boolean,
    val relationship: RelationshipV2?,
    val cityCount: Int?,
    val countryCount: Int?,
    val followerCount: Long?,
    val followingCount: Long?,
    val friendCount: Long?,
    val visibleExperienceCount: Long?,
) {
    val isIdentityOnly: Boolean get() = !fullProfile
}

data class FollowRequestV2(
    val id: String,
    val requester: UserSummary,
    val status: String,
    val createdAt: String,
    val resolvedAt: String?,
)

/** Ephemeral V2 social state; activation clears state across account switches. */
class PrivacySocialStateStore {
    var accountId: String? = null
        private set
    var profile: ProfileV2? = null
        private set
    var incomingRequests: List<FollowRequestV2> = emptyList()
        private set

    fun activate(accountId: String) {
        if (this.accountId == accountId) return
        clear()
        this.accountId = accountId
    }

    fun replace(profile: ProfileV2?, incomingRequests: List<FollowRequestV2>) {
        check(accountId != null) { "An account must be active" }
        this.profile = profile
        this.incomingRequests = incomingRequests
    }

    fun clear() {
        accountId = null
        profile = null
        incomingRequests = emptyList()
    }
}
