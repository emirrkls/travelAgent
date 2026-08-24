package com.emirrkls.phokarta.core.network.mapper

import com.emirrkls.phokarta.core.model.ActivityAuthor
import com.emirrkls.phokarta.core.model.ActivityEvent
import com.emirrkls.phokarta.core.model.ActivityPlaceSummary
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.FriendPlaceSummary
import com.emirrkls.phokarta.core.model.FriendPlaceUser
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.PublicReview
import com.emirrkls.phokarta.core.model.PublicReviewAuthor
import com.emirrkls.phokarta.core.model.PublicUserProfile
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.RelationshipState
import com.emirrkls.phokarta.core.model.SavedFriendMetrics
import com.emirrkls.phokarta.core.model.UserSummary
import com.emirrkls.phokarta.core.model.VerificationStatus
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.network.model.CollectionDetailDto
import com.emirrkls.phokarta.core.network.model.CreateCollectionDto
import com.emirrkls.phokarta.core.network.model.CreateVisitDto
import com.emirrkls.phokarta.core.network.model.FriendMetricsDto
import com.emirrkls.phokarta.core.network.model.FriendPlaceSummaryDto
import com.emirrkls.phokarta.core.network.model.FriendPlaceUserDto
import com.emirrkls.phokarta.core.network.model.PlaceDetailDto
import com.emirrkls.phokarta.core.network.model.PlaceSummaryDto
import com.emirrkls.phokarta.core.network.model.PublicActivityDto
import com.emirrkls.phokarta.core.network.model.PublicUserProfileDto
import com.emirrkls.phokarta.core.network.model.PublicVisitDto
import com.emirrkls.phokarta.core.network.model.RatingDimensionDto
import com.emirrkls.phokarta.core.network.model.RelationshipStateDto
import com.emirrkls.phokarta.core.network.model.SavedPlaceDto
import com.emirrkls.phokarta.core.network.model.UserSummaryDto
import com.emirrkls.phokarta.core.network.model.VisitOwnerDto
import com.emirrkls.phokarta.core.network.model.VisibilityDto
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

fun String.toCanonicalUuid(): String {
    val parsed = runCatching { UUID.fromString(this) }
        .getOrElse { throw IllegalArgumentException("Invalid UUID: $this", it) }
    require(parsed.toString().equals(this, ignoreCase = true)) { "Invalid UUID: $this" }
    return parsed.toString()
}

private fun String.toLocalDateSafely(): LocalDate =
    runCatching { LocalDate.parse(this) }.getOrElse { throw IllegalArgumentException("Invalid date: $this", it) }

internal fun String.toEpochMillisSafely(): Long =
    runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }
        .getOrElse { throw IllegalArgumentException("Invalid timestamp: $this", it) }

private fun RatingDimensionDto.toDomain() = RatingDimension.valueOf(name)
private fun RatingDimension.toDto() = RatingDimensionDto.valueOf(apiKey)
private fun VisibilityDto.toDomain() = Visibility.valueOf(name)
private fun Visibility.toDto() = VisibilityDto.valueOf(name)

fun PlaceSummaryDto.toDomain(): Place = Place(
    id = id.toCanonicalUuid(),
    name = name,
    description = "",
    category = PlaceCategory.valueOf(category.name),
    subcategories = emptyList(),
    latitude = latitude,
    longitude = longitude,
    city = city,
    region = region,
    country = country,
    address = "",
    coverImage = coverImage,
    photos = emptyList(),
    priceLevel = priceLevel,
    communityScore = averageScore,
    friendsScore = null,
    similarUsersScore = null,
    ratingCount = ratingCount.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
    ratingBreakdown = emptyMap(),
    friendSignal = null,
)

fun PlaceDetailDto.toDomain(): Place = Place(
    id = id.toCanonicalUuid(),
    name = name,
    description = description,
    category = PlaceCategory.valueOf(category.name),
    subcategories = subcategories,
    latitude = latitude,
    longitude = longitude,
    city = city,
    region = region,
    country = country,
    address = address,
    coverImage = coverImage,
    photos = photos,
    priceLevel = priceLevel,
    communityScore = averageScore,
    friendsScore = null,
    similarUsersScore = null,
    ratingCount = ratingCount.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
    ratingBreakdown = dimensionScores.associate { it.key.toDomain() to it.average },
    friendSignal = null,
)

fun VisitOwnerDto.toDomain(userId: String): Visit = Visit(
    id = id.toCanonicalUuid(),
    userId = userId.toCanonicalUuid(),
    placeId = place.id.toCanonicalUuid(),
    visitedAt = visitedAt.toLocalDateSafely(),
    overallRating = overallRating,
    ratingDimensions = dimensions.associate { it.key.toDomain() to it.score },
    review = publicReview,
    personalNote = privateMemory,
    photos = photos,
    visibility = visibility.toDomain(),
    verificationStatus = VerificationStatus.valueOf(verificationStatus.name),
)

fun PublicVisitDto.toPublicReview(): PublicReview = PublicReview(
    id = id.toCanonicalUuid(),
    placeId = placeId.toCanonicalUuid(),
    author = PublicReviewAuthor(
        userId = userId.toCanonicalUuid(),
        username = username,
        displayName = displayName,
        avatarUrl = avatarUrl,
    ),
    overallScore = overallRating,
    publicReview = publicReview,
    visitDate = visitedAt.toLocalDateSafely(),
    photos = photos,
    verificationStatus = VerificationStatus.valueOf(verificationStatus.name),
)

fun PublicActivityDto.toActivityEvent(): ActivityEvent = ActivityEvent(
    visitId = visitId.toCanonicalUuid(),
    author = ActivityAuthor(
        userId = author.id.toCanonicalUuid(),
        username = author.username,
        displayName = author.displayName.ifBlank { author.username.ifBlank { "Traveler" } },
        avatarUrl = author.avatarUrl,
    ),
    place = ActivityPlaceSummary(
        id = place.id.toCanonicalUuid(),
        name = place.name,
        category = PlaceCategory.valueOf(place.category.name),
        city = place.city,
        coverImage = place.coverImage,
    ),
    overallScore = overallScore,
    publicReview = publicReview,
    visitDate = visitedAt.toLocalDateSafely(),
)

fun FriendPlaceSummaryDto.toDomain(): FriendPlaceSummary = FriendPlaceSummary(
    averageScore = averageScore,
    friendsVisitedCount = friendsVisitedCount.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
    friends = friends.map { it.toDomain() },
)

fun FriendPlaceUserDto.toDomain(): FriendPlaceUser = FriendPlaceUser(
    userId = userId.toCanonicalUuid(),
    displayName = displayName.ifBlank { "Traveler" },
    avatarUrl = avatarUrl,
    latestScore = latestScore,
    latestVisitedAt = latestVisitedAt.toLocalDateSafely(),
)

fun SavedPlaceDto.toFriendMetrics(): SavedFriendMetrics {
    val count = friendsVisitedCount.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    return SavedFriendMetrics(
        averageScore = if (count == 0) null else friendAverageScore,
        friendsVisitedCount = count,
    )
}

fun FriendMetricsDto.toFriendMetrics(): SavedFriendMetrics {
    val count = friendsVisitedCount.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    return SavedFriendMetrics(
        averageScore = if (count == 0) null else friendAverageScore,
        friendsVisitedCount = count,
    )
}

fun Visit.toCreateDto(): CreateVisitDto = CreateVisitDto(
    placeId = placeId.toCanonicalUuid(),
    visitedAt = visitedAt.toString(),
    overallRating = overallRating,
    dimensions = ratingDimensions.takeIf { it.isNotEmpty() }?.map { (key, score) ->
        com.emirrkls.phokarta.core.network.model.DimensionScoreDto(key.toDto(), score)
    },
    publicReview = review.takeIf { it.isNotBlank() },
    privateMemory = personalNote.takeIf { it.isNotBlank() },
    photos = photos.takeIf { it.isNotEmpty() },
    visibility = visibility.toDto(),
)

fun CollectionDetailDto.toDomain(): Collection {
    createdAt.toEpochMillisSafely()
    updatedAt.toEpochMillisSafely()
    places.forEach { it.addedAt.toEpochMillisSafely() }
    return Collection(
        id = id.toCanonicalUuid(),
        userId = userId.toCanonicalUuid(),
        title = title,
        description = description,
        placeIds = places.sortedBy { it.displayOrder }.map { it.place.id.toCanonicalUuid() }.distinct(),
        visibility = visibility.toDomain(),
        coverImage = coverImage,
    )
}

fun Collection.toCreateDto(): CreateCollectionDto = CreateCollectionDto(
    title = title,
    description = description.takeIf { it.isNotBlank() },
    visibility = visibility.toDto(),
    coverImage = coverImage,
)

fun RelationshipStateDto.toDomain(): RelationshipState = RelationshipState(
    isFollowing = isFollowing,
    followsYou = followsYou,
    isFriend = isFriend,
)

fun UserSummaryDto.toDomain(): UserSummary = UserSummary(
    id = id.toCanonicalUuid(),
    displayName = displayName.ifBlank { username.ifBlank { "Traveler" } },
    username = username,
    avatarUrl = avatarUrl.orEmpty(),
    relationship = relationship?.toDomain(),
)

fun PublicUserProfileDto.toDomain(): PublicUserProfile = PublicUserProfile(
    id = id.toCanonicalUuid(),
    username = username,
    displayName = displayName.ifBlank { username.ifBlank { "Traveler" } },
    avatarUrl = avatarUrl.orEmpty(),
    bio = bio.orEmpty(),
    cityCount = cityCount,
    countryCount = countryCount,
    followerCount = followerCount,
    followingCount = followingCount,
    friendCount = friendCount,
    relationship = relationship?.toDomain(),
)
