package com.emirrkls.phokarta.core.network.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

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
    val media: List<VisitMediaDto> = emptyList(),
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
    /** Legacy response compatibility. */
    val photos: List<String> = emptyList(),
    val visibility: VisibilityDto,
    val verificationStatus: VerificationStatusDto,
    val media: List<VisitMediaDto> = emptyList(),
)

@Serializable
data class DimensionScoreDto(
    val key: RatingDimensionDto,
    val score: Double,
)

@Serializable
data class CreateVisitDto(
    val clientMutationId: String? = null,
    val placeId: String,
    val visitedAt: String,
    val overallRating: Double,
    val dimensions: List<DimensionScoreDto>?,
    val publicReview: String?,
    val privateMemory: String?,
    /** Legacy requests only; new mutations send [mediaIds]. */
    val photos: List<String>? = null,
    val mediaIds: List<String>? = null,
    val visibility: VisibilityDto,
)

@Serializable
data class VisitMediaDto(
    @SerialName("id")
    val mediaId: String,
    @SerialName("sortOrder")
    val order: Int,
    val accessUrl: String? = null,
    @SerialName("accessExpiresAt")
    val accessUrlExpiresAt: String? = null,
)

@Serializable
data class CreateExperienceV2Dto(
    val clientMutationId: String,
    val placeId: String,
    val visitDate: String,
    val primaryExperienceCode: String,
    val rawExperienceLabel: String? = null,
    val overallFeelingCode: String,
    val companionCode: String? = null,
    val timeOfDayCode: String? = null,
    val vibeCodes: List<String> = emptyList(),
    val practicalSignalCodes: List<String> = emptyList(),
    val dimensions: List<CreateExperienceV2DimensionDto> = emptyList(),
    val title: String? = null,
    val titleSource: String,
    val story: String? = null,
    val tip: String? = null,
    val privateMemory: String? = null,
    val visibility: String,
    val mediaIds: List<String> = emptyList(),
    val originAcknowledgementId: String? = null,
)

@Serializable
data class CreateExperienceV2DimensionDto(
    val key: String,
    val semanticStateCode: String,
    val templateVersion: Int,
)

@Serializable
data class ExperienceV2Dto(
    val id: String,
    val classification: String,
    val author: ExperienceAuthorDto,
    val place: ExperiencePlaceDto,
    val experiencedAt: String,
    val title: String,
    val titleSource: String? = null,
    val titlePersisted: Boolean,
    val story: String,
    val tip: String? = null,
    val feeling: ExperienceFeelingDto,
    val primaryExperience: ExperiencePrimaryDto,
    val companion: String? = null,
    val timeOfDay: String? = null,
    val vibes: List<String> = emptyList(),
    val practicalSignals: List<String> = emptyList(),
    val dimensions: List<ExperienceDimensionDto> = emptyList(),
    val media: List<ExperienceMediaDto> = emptyList(),
    val visibility: String,
    val taxonomyVersion: Int? = null,
    val plannedByViewer: Boolean = false,
    val acknowledgedByViewer: Boolean = false,
    val acknowledgementCount: Long = 0,
    val conversationCount: Long = 0,
)

@Serializable
data class ExperienceAuthorDto(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val relationship: RelationshipV2Dto? = null,
)

@Serializable
data class ExperiencePlaceDto(
    val id: String,
    val name: String,
    val category: String,
    val city: String,
    val region: String,
    val country: String,
    val coverImage: String,
    val distanceMeters: Double? = null,
)

@Serializable
data class CursorPageDto<T>(
    val items: List<T>,
    val nextCursor: String? = null,
    val hasMore: Boolean,
)

@Serializable
data class ExperienceSummaryV2Dto(
    val id: String,
    val classification: String,
    val author: ExperienceAuthorDto,
    val place: ExperiencePlaceDto,
    val experiencedAt: String,
    val title: String,
    val titleSource: String? = null,
    val primaryExperience: ExperiencePrimaryDto,
    val feeling: String,
    val storyPreview: String? = null,
    val tipPreview: String? = null,
    val companion: String? = null,
    val timeOfDay: String? = null,
    val vibes: List<String> = emptyList(),
    val practicalSignals: List<String> = emptyList(),
    val mediaPreview: ExperienceMediaPreviewDto? = null,
    val mediaCount: Int,
    val visibility: String,
    val plannedByViewer: Boolean = false,
    val acknowledgedByViewer: Boolean = false,
    val acknowledgementCount: Long = 0,
    val conversationCount: Long = 0,
)

@Serializable
data class CreateConversationEntryDto(
    val clientMutationId: String,
    val type: String,
    val body: String,
)

@Serializable
data class CreateConversationReplyDto(
    val clientMutationId: String,
    val body: String,
)

@Serializable
data class UpdateConversationEntryDto(val body: String)

@Serializable
data class ConversationAuthorDto(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

@Serializable
data class ConversationEntryDto(
    val id: String,
    val experienceId: String,
    val type: String,
    val body: String,
    val author: ConversationAuthorDto,
    val createdAt: String,
    val updatedAt: String,
    val edited: Boolean,
    val experienceAuthor: Boolean,
    val ownedByViewer: Boolean,
    val reportableByViewer: Boolean,
    val replies: List<ConversationEntryDto> = emptyList(),
)

@Serializable
data class PlannedExperienceV2Dto(val experience: ExperienceV2Dto, val plannedAt: String)

@Serializable
data class AcknowledgementAnchorPlaceDto(
    val id: String, val name: String, val city: String, val region: String, val country: String,
)

@Serializable
data class ExperienceAcknowledgementV2Dto(
    val id: String,
    val ownerUserId: String,
    val sourceExperienceId: String? = null,
    val sourceAvailable: Boolean,
    val sourceExperience: ExperienceV2Dto? = null,
    val place: AcknowledgementAnchorPlaceDto,
    val primaryExperienceCode: String,
    val rawExperienceLabel: String? = null,
    val acknowledgedAt: String,
    val status: String,
    val convertedExperienceId: String? = null,
)

@Serializable
data class ExperienceMediaPreviewDto(
    val kind: String,
    val id: String? = null,
    val url: String,
    val accessExpiresAt: String? = null,
)

@Serializable
data class ExperienceFeelingDto(val code: String, val source: String, val compatibilityNumericRating: Double)

@Serializable
data class ExperiencePrimaryDto(
    val code: String,
    val canonical: Boolean,
    val family: String? = null,
    val rawLabel: String? = null,
)

@Serializable
data class ExperienceDimensionDto(
    val key: String,
    val numericScore: Double,
    val semanticState: String? = null,
    val templateVersion: Int? = null,
)

@Serializable
data class ExperienceMediaDto(
    val kind: String,
    val position: Int,
    val id: String? = null,
    val url: String,
    val accessExpiresAt: String? = null,
)

@Serializable
data class MediaUploadIntentRequestDto(
    val clientMediaId: String,
    val contentType: String,
    val byteSize: Long,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class MediaUploadIntentResponseDto(
    val mediaId: String,
    val status: String,
    val uploadUrl: String? = null,
    val requiredHeaders: Map<String, String> = emptyMap(),
    val expiresAt: String? = null,
)

@Serializable
data class MediaStateDto(val mediaId: String, val status: String)

@Serializable
data class MediaAccessDto(val url: String, val expiresAt: String)

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
    val fieldErrors: Map<String, String> = emptyMap(),
    val requiredVersion: String? = null,
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
data class DeleteAccountRequestDto(
    val currentPassword: String? = null,
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
data class CollectionV2DetailDto(
    val id: String,
    val ownerUserId: String,
    val title: String,
    val description: String,
    val visibility: VisibilityDto,
    val coverImage: String,
    val createdAt: String,
    val updatedAt: String,
    val items: List<CollectionV2ItemDto>,
)

@Serializable
data class CollectionV2ItemDto(
    val type: String,
    val displayOrder: Int,
    val addedAt: String,
    val place: PlaceSummaryDto? = null,
    val experience: ExperienceV2Dto? = null,
)

@Serializable
data class RelationshipV2Dto(
    val state: String,
    val followsYou: Boolean,
    val canFollow: Boolean,
    val canCancelRequest: Boolean,
)

@Serializable
data class ProfileV2Dto(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val profileVisibility: String,
    val fullProfile: Boolean,
    val relationship: RelationshipV2Dto? = null,
    val cityCount: Int? = null,
    val countryCount: Int? = null,
    val followerCount: Long? = null,
    val followingCount: Long? = null,
    val friendCount: Long? = null,
    val visibleExperienceCount: Long? = null,
)

@Serializable
data class ProfileVisibilityUpdateDto(val visibility: String)

@Serializable
data class FollowRequestIdentityDto(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

@Serializable
data class FollowRequestV2Dto(
    val id: String,
    val requester: FollowRequestIdentityDto,
    val status: String,
    val createdAt: String,
    val resolvedAt: String? = null,
)

@Serializable
data class CapabilitiesV2Dto(
    val profilePrivacyV2Enabled: Boolean,
    val experiencePlanningEnabled: Boolean = false,
    val experienceAcknowledgementsEnabled: Boolean = false,
    val mixedCollectionItemsEnabled: Boolean = false,
)

@Serializable
data class PlaceAggregateV2Dto(
    val place: PlaceAggregateIdentityDto,
    val visibleExperienceCount: Long,
    val communityContributionCount: Long,
    val primaryExperiences: List<PrimaryExperienceAggregateDto> = emptyList(),
    val feelings: List<FeelingAggregateDto> = emptyList(),
    val dimensions: List<DimensionAggregateV2Dto> = emptyList(),
    val practicalSignals: List<PracticalSignalAggregateDto> = emptyList(),
)

@Serializable
data class PrimaryExperienceAggregateDto(
    val code: String,
    val visibleExperienceCount: Long,
)

@Serializable
data class PlaceAggregateIdentityDto(
    val id: String,
    val name: String,
    val category: String,
    val city: String,
    val region: String,
    val country: String,
    val coverImage: String,
)

@Serializable
data class FeelingAggregateDto(val code: String, val contributionCount: Long)

@Serializable
data class DimensionAggregateV2Dto(
    val key: String,
    val contributionCount: Long,
    val numericAverage: Double? = null,
    val legacyNumericContributionCount: Long,
    val semanticDistribution: List<SemanticStateAggregateDto> = emptyList(),
)

@Serializable
data class SemanticStateAggregateDto(val state: String, val contributionCount: Long)

@Serializable
data class PracticalSignalAggregateDto(
    val code: String,
    val contributionCount: Long,
    val eligibleContributionDenominator: Long,
)

@Serializable
data class UserSummaryDto(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val relationship: RelationshipStateDto? = null,
)

@Serializable
enum class ReportTargetTypeDto {
    USER, VISIT, CONVERSATION_ENTRY,
}

@Serializable
enum class ReportReasonDto {
    SPAM,
    HARASSMENT,
    HATE_OR_ABUSE,
    SEXUAL_CONTENT,
    VIOLENCE_OR_THREAT,
    IMPERSONATION,
    PRIVACY,
    OTHER,
}

@Serializable
data class BlockedUserDto(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val blockedAt: String,
)

@Serializable
data class CreateReportDto(
    val targetType: ReportTargetTypeDto,
    val targetId: String,
    val reason: ReportReasonDto,
    val details: String? = null,
)

@Serializable
data class ReportResponseDto(
    val id: String,
    val targetType: ReportTargetTypeDto,
    val reason: ReportReasonDto,
    val status: String,
    val createdAt: String,
)

@Serializable
data class PolicyStatusDto(
    val requiredVersion: String,
    val acceptedVersion: String? = null,
    val accepted: Boolean,
)

@Serializable
data class PolicyAcceptanceRequestDto(
    val policyVersion: String,
)
