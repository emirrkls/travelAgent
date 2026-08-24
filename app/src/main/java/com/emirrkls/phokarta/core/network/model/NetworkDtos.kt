package com.emirrkls.phokarta.core.network.model

import kotlinx.serialization.Serializable

@Serializable
enum class PlaceCategoryDto {
    BEACH, RESTAURANT, CAFE, HOTEL, BAR, NIGHTLIFE, ATTRACTION, ACTIVITY, NATURE,
}

@Serializable
enum class VisibilityDto {
    PRIVATE, FRIENDS, PUBLIC,
}

@Serializable
enum class VerificationStatusDto {
    UNVERIFIED, LOCATION_CONFIRMED,
}

@Serializable
enum class RatingDimensionDto {
    SEA, ATMOSPHERE, SERVICE, CLEANLINESS, VALUE, CROWD,
    FOOD, PRESENTATION, LOCATION, ROOM, BREAKFAST, DRINKS,
    MUSIC, EXPERIENCE, ACCESS, SAFETY, GUIDE, SCENERY, TRANQUILITY,
}

@Serializable
data class PlaceSummaryDto(
    val id: String,
    val name: String,
    val category: PlaceCategoryDto,
    val coverImage: String,
    val city: String,
    val region: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val priceLevel: Int,
    val averageScore: Double?,
    val ratingCount: Long,
)

@Serializable
data class PlaceDetailDto(
    val id: String,
    val name: String,
    val description: String,
    val category: PlaceCategoryDto,
    val subcategories: List<String>,
    val latitude: Double,
    val longitude: Double,
    val city: String,
    val region: String,
    val country: String,
    val address: String,
    val coverImage: String,
    val photos: List<String>,
    val priceLevel: Int,
    val averageScore: Double?,
    val ratingCount: Long,
    val dimensionScores: List<DimensionAggregateDto>,
    val recentPublicReviews: List<PublicVisitDto>,
)

@Serializable
data class DimensionAggregateDto(
    val key: RatingDimensionDto,
    val average: Double,
)

@Serializable
data class NearbyPlaceDto(
    val place: PlaceSummaryDto,
    val distanceMeters: Double,
)

@Serializable
data class PublicVisitDto(
    val id: String,
    val placeId: String,
    val placeName: String,
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val visitedAt: String,
    val overallRating: Double,
    val publicReview: String,
    val photos: List<String>,
    val verificationStatus: VerificationStatusDto,
)

@Serializable
data class PublicActivityDto(
    val visitId: String,
    val author: PublicActivityAuthorDto,
    val place: PublicActivityPlaceDto,
    val overallScore: Double,
    val publicReview: String,
    val visitedAt: String,
)

@Serializable
data class PublicActivityAuthorDto(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
)

@Serializable
data class PublicActivityPlaceDto(
    val id: String,
    val name: String,
    val category: PlaceCategoryDto,
    val city: String,
    val coverImage: String,
)

@Serializable
data class FriendPlaceSummaryDto(
    val averageScore: Double? = null,
    val friendsVisitedCount: Long = 0,
    val friends: List<FriendPlaceUserDto> = emptyList(),
)

@Serializable
data class FriendPlaceUserDto(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val latestScore: Double,
    val latestVisitedAt: String,
)

@Serializable
data class VisitOwnerDto(
    val id: String,
    val place: PlaceSummaryDto,
    val visitedAt: String,
    val overallRating: Double,
    val dimensions: List<DimensionScoreDto>,
    val publicReview: String,
    val privateMemory: String,
    val photos: List<String>,
    val visibility: VisibilityDto,
    val verificationStatus: VerificationStatusDto,
)

@Serializable
data class DimensionScoreDto(
    val key: RatingDimensionDto,
    val score: Double,
)

@Serializable
data class CreateVisitDto(
    val placeId: String,
    val visitedAt: String,
    val overallRating: Double,
    val dimensions: List<DimensionScoreDto>?,
    val publicReview: String?,
    val privateMemory: String?,
    val photos: List<String>?,
    val visibility: VisibilityDto,
)

@Serializable
data class SavedPlaceDto(
    val place: PlaceSummaryDto,
    val savedAt: String,
    val friendAverageScore: Double? = null,
    val friendsVisitedCount: Long = 0,
)

@Serializable
data class FriendMetricsRequestDto(
    val placeIds: List<String>,
)

@Serializable
data class FriendMetricsDto(
    val placeId: String,
    val friendAverageScore: Double? = null,
    val friendsVisitedCount: Long = 0,
)

@Serializable
data class CollectionSummaryDto(
    val id: String,
    val userId: String,
    val title: String,
    val description: String,
    val visibility: VisibilityDto,
    val coverImage: String,
    val placeCount: Long,
    val updatedAt: String,
)

@Serializable
data class CollectionDetailDto(
    val id: String,
    val userId: String,
    val title: String,
    val description: String,
    val visibility: VisibilityDto,
    val coverImage: String,
    val createdAt: String,
    val updatedAt: String,
    val places: List<CollectionPlaceDto>,
)

@Serializable
data class CollectionPlaceDto(
    val place: PlaceSummaryDto,
    val displayOrder: Int,
    val addedAt: String,
)

@Serializable
data class CreateCollectionDto(
    val title: String,
    val description: String?,
    val visibility: VisibilityDto,
    val coverImage: String,
)

@Serializable
data class PageResponseDto<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
)

@Serializable
data class ApiErrorDto(
    val timestamp: String,
    val status: Int,
    val code: String,
    val message: String,
    val path: String,
    val fieldErrors: Map<String, String>,
)

@Serializable
data class RegisterRequestDto(
    val email: String,
    val username: String,
    val displayName: String,
    val password: String,
)

@Serializable
data class LoginRequestDto(
    val identifier: String,
    val password: String,
)

@Serializable
data class RefreshRequestDto(
    val refreshToken: String,
)

@Serializable
data class LogoutRequestDto(
    val refreshToken: String,
)

@Serializable
data class UserProfileDto(
    val id: String,
    val email: String,
    val username: String,
    val displayName: String,
    val bio: String? = null,
    val avatarUrl: String? = null,
    val followerCount: Long = 0,
    val followingCount: Long = 0,
    val friendCount: Long = 0,
)

@Serializable
data class TokenPairDto(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long = 0L,
    val accessTokenExpiresAt: String? = null,
)

@Serializable
data class AuthSessionDto(
    val user: UserProfileDto,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long = 0L,
    val accessTokenExpiresAt: String? = null,
)

@Serializable
data class RelationshipStateDto(
    val isFollowing: Boolean,
    val followsYou: Boolean,
    val isFriend: Boolean,
)

@Serializable
data class PublicUserProfileDto(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val cityCount: Int = 0,
    val countryCount: Int = 0,
    val followerCount: Long = 0,
    val followingCount: Long = 0,
    val friendCount: Long = 0,
    val relationship: RelationshipStateDto? = null,
)

@Serializable
data class UserSummaryDto(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val relationship: RelationshipStateDto? = null,
)
