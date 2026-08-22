package com.emirrkls.phokarta.core.network.mapper

import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.VerificationStatus
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.network.model.CollectionDetailDto
import com.emirrkls.phokarta.core.network.model.CreateCollectionDto
import com.emirrkls.phokarta.core.network.model.CreateVisitDto
import com.emirrkls.phokarta.core.network.model.PlaceDetailDto
import com.emirrkls.phokarta.core.network.model.PlaceSummaryDto
import com.emirrkls.phokarta.core.network.model.PublicVisitDto
import com.emirrkls.phokarta.core.network.model.RatingDimensionDto
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

fun PublicVisitDto.toDomain(): Visit = Visit(
    id = id.toCanonicalUuid(),
    userId = userId.toCanonicalUuid(),
    placeId = placeId.toCanonicalUuid(),
    visitedAt = visitedAt.toLocalDateSafely(),
    overallRating = overallRating,
    ratingDimensions = emptyMap(),
    review = publicReview,
    personalNote = "",
    photos = photos,
    visibility = Visibility.PUBLIC,
    verificationStatus = VerificationStatus.valueOf(verificationStatus.name),
)

fun Visit.toCreateDto(): CreateVisitDto = CreateVisitDto(
    userId = userId.toCanonicalUuid(),
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
