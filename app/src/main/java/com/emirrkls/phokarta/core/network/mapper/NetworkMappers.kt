package com.emirrkls.phokarta.core.network.mapper

import com.emirrkls.phokarta.core.model.ActivityAuthor
import com.emirrkls.phokarta.core.model.ActivityEvent
import com.emirrkls.phokarta.core.model.ActivityPlaceSummary
import com.emirrkls.phokarta.core.model.BlockedUser
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.CompanionCode
import com.emirrkls.phokarta.core.model.DimensionStateCode
import com.emirrkls.phokarta.core.model.Experience
import com.emirrkls.phokarta.core.model.ExperiencePage
import com.emirrkls.phokarta.core.model.ExperienceSummary
import com.emirrkls.phokarta.core.model.ExperienceAuthor
import com.emirrkls.phokarta.core.model.ExperienceClassification
import com.emirrkls.phokarta.core.model.ExperienceDimension
import com.emirrkls.phokarta.core.model.ExperienceFamily
import com.emirrkls.phokarta.core.model.ExperienceFeeling
import com.emirrkls.phokarta.core.model.ExperienceMedia
import com.emirrkls.phokarta.core.model.ExperienceMediaKind
import com.emirrkls.phokarta.core.model.ExperiencePlace
import com.emirrkls.phokarta.core.model.ExperiencePrimary
import com.emirrkls.phokarta.core.model.ExperienceTitleSource
import com.emirrkls.phokarta.core.model.ExperienceVisibility
import com.emirrkls.phokarta.core.model.FeelingProvenance
import com.emirrkls.phokarta.core.model.FriendPlaceSummary
import com.emirrkls.phokarta.core.model.FriendPlaceUser
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.DimensionAggregateV2
import com.emirrkls.phokarta.core.model.FeelingAggregate
import com.emirrkls.phokarta.core.model.FollowRequestV2
import com.emirrkls.phokarta.core.model.PlaceAggregateIdentity
import com.emirrkls.phokarta.core.model.PlaceAggregateV2
import com.emirrkls.phokarta.core.model.PracticalSignalAggregate
import com.emirrkls.phokarta.core.model.PrimaryExperienceAggregate
import com.emirrkls.phokarta.core.model.ProfileV2
import com.emirrkls.phokarta.core.model.ProfileVisibilityV2
import com.emirrkls.phokarta.core.model.RelationshipActionState
import com.emirrkls.phokarta.core.model.RelationshipV2
import com.emirrkls.phokarta.core.model.SemanticStateAggregate
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.PolicyStatus
import com.emirrkls.phokarta.core.model.PracticalSignalCode
import com.emirrkls.phokarta.core.model.OverallFeelingCode
import com.emirrkls.phokarta.core.model.PrimaryExperienceCode
import com.emirrkls.phokarta.core.model.PublicReview
import com.emirrkls.phokarta.core.model.PublicReviewAuthor
import com.emirrkls.phokarta.core.model.PublicReviewMedia
import com.emirrkls.phokarta.core.model.PublicUserProfile
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.RelationshipState
import com.emirrkls.phokarta.core.model.ReportReason
import com.emirrkls.phokarta.core.model.ReportTargetType
import com.emirrkls.phokarta.core.model.SavedFriendMetrics
import com.emirrkls.phokarta.core.model.SubmittedReport
import com.emirrkls.phokarta.core.model.TimeOfDayCode
import com.emirrkls.phokarta.core.model.UserSummary
import com.emirrkls.phokarta.core.model.VerificationStatus
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.model.VisitMedia
import com.emirrkls.phokarta.core.model.VibeCode
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.network.model.CollectionDetailDto
import com.emirrkls.phokarta.core.network.model.BlockedUserDto
import com.emirrkls.phokarta.core.network.model.CreateCollectionDto
import com.emirrkls.phokarta.core.network.model.CreateVisitDto
import com.emirrkls.phokarta.core.network.model.ExperienceV2Dto
import com.emirrkls.phokarta.core.network.model.ExperienceSummaryV2Dto
import com.emirrkls.phokarta.core.network.model.CursorPageDto
import com.emirrkls.phokarta.core.network.model.FriendMetricsDto
import com.emirrkls.phokarta.core.network.model.FriendPlaceSummaryDto
import com.emirrkls.phokarta.core.network.model.FriendPlaceUserDto
import com.emirrkls.phokarta.core.network.model.PlaceDetailDto
import com.emirrkls.phokarta.core.network.model.FollowRequestV2Dto
import com.emirrkls.phokarta.core.network.model.PlaceAggregateV2Dto
import com.emirrkls.phokarta.core.network.model.ProfileV2Dto
import com.emirrkls.phokarta.core.network.model.RelationshipV2Dto
import com.emirrkls.phokarta.core.network.model.PolicyStatusDto
import com.emirrkls.phokarta.core.network.model.PlaceSummaryDto
import com.emirrkls.phokarta.core.network.model.PublicActivityDto
import com.emirrkls.phokarta.core.network.model.PublicUserProfileDto
import com.emirrkls.phokarta.core.network.model.PublicVisitDto
import com.emirrkls.phokarta.core.network.model.RatingDimensionDto
import com.emirrkls.phokarta.core.network.model.RelationshipStateDto
import com.emirrkls.phokarta.core.network.model.ReportReasonDto
import com.emirrkls.phokarta.core.network.model.ReportResponseDto
import com.emirrkls.phokarta.core.network.model.ReportTargetTypeDto
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
    photos = media.sortedBy { it.order }.mapNotNull { it.accessUrl }.ifEmpty { photos },
    media = media.sortedBy { it.order }.map {
        VisitMedia(
            mediaId = it.mediaId,
            order = it.order,
            accessUrl = it.accessUrl,
            accessUrlExpiresAtEpochMillis = it.accessUrlExpiresAt?.toEpochMillisSafely(),
        )
    },
    visibility = visibility.toDomain(),
    verificationStatus = VerificationStatus.valueOf(verificationStatus.name),
)

fun ExperienceV2Dto.toDomain(): Experience = Experience(
    id = id.toCanonicalUuid(),
    classification = ExperienceClassification.fromWire(classification),
    author = ExperienceAuthor(
        id = author.id.toCanonicalUuid(),
        username = author.username,
        displayName = author.displayName,
        avatarUrl = author.avatarUrl,
        relationship = author.relationship?.toDomain(),
    ),
    place = ExperiencePlace(
        id = place.id.toCanonicalUuid(),
        name = place.name,
        categoryCode = place.category,
        city = place.city,
        region = place.region,
        country = place.country,
        coverImage = place.coverImage,
        distanceMeters = place.distanceMeters,
    ),
    experiencedAt = experiencedAt.toLocalDateSafely(),
    title = title,
    titleSource = titleSource?.let(ExperienceTitleSource::fromWire),
    titlePersisted = titlePersisted,
    story = story,
    tip = tip,
    feeling = ExperienceFeeling(
        code = com.emirrkls.phokarta.core.model.OverallFeelingCode.fromWire(feeling.code),
        provenance = FeelingProvenance.fromWire(feeling.source),
        compatibilityNumericRating = feeling.compatibilityNumericRating,
    ),
    primaryExperience = ExperiencePrimary(
        code = PrimaryExperienceCode.fromWire(primaryExperience.code),
        canonical = primaryExperience.canonical,
        family = primaryExperience.family?.let(ExperienceFamily::fromWire),
        rawLabel = primaryExperience.rawLabel,
    ),
    companion = companion?.let(CompanionCode::fromWire),
    timeOfDay = timeOfDay?.let(TimeOfDayCode::fromWire),
    vibes = vibes.map(VibeCode::fromWire),
    practicalSignals = practicalSignals.map(PracticalSignalCode::fromWire),
    dimensions = dimensions.map {
        ExperienceDimension(
            key = it.key,
            numericScore = it.numericScore,
            semanticState = it.semanticState?.let(DimensionStateCode::fromWire),
            templateVersion = it.templateVersion,
        )
    },
    media = media.sortedBy { it.position }.map {
        ExperienceMedia(
            kind = ExperienceMediaKind.fromWire(it.kind),
            position = it.position,
            id = it.id?.toCanonicalUuid(),
            url = it.url,
            accessExpiresAt = it.accessExpiresAt,
        )
    },
    visibility = ExperienceVisibility.fromWire(visibility),
    taxonomyVersion = taxonomyVersion,
    plannedByViewer = plannedByViewer,
    acknowledgedByViewer = acknowledgedByViewer,
    acknowledgementCount = acknowledgementCount,
)

fun CursorPageDto<ExperienceSummaryV2Dto>.toExperiencePage(): ExperiencePage = ExperiencePage(
    items = items.map { it.toDomain() },
    nextCursor = nextCursor,
    hasMore = hasMore,
)

fun ExperienceSummaryV2Dto.toDomain(): ExperienceSummary = ExperienceSummary(
    id = id.toCanonicalUuid(),
    classification = ExperienceClassification.fromWire(classification),
    author = ExperienceAuthor(
        id = author.id.toCanonicalUuid(),
        username = author.username,
        displayName = author.displayName,
        avatarUrl = author.avatarUrl,
        relationship = author.relationship?.toDomain(),
    ),
    place = ExperiencePlace(
        id = place.id.toCanonicalUuid(),
        name = place.name,
        categoryCode = place.category,
        city = place.city,
        region = place.region,
        country = place.country,
        coverImage = place.coverImage,
        distanceMeters = place.distanceMeters,
    ),
    experiencedAt = experiencedAt.toLocalDateSafely(),
    title = title,
    titleSource = titleSource?.let(ExperienceTitleSource::fromWire),
    primaryExperience = ExperiencePrimary(
        code = PrimaryExperienceCode.fromWire(primaryExperience.code),
        canonical = primaryExperience.canonical,
        family = primaryExperience.family?.let(ExperienceFamily::fromWire),
        rawLabel = primaryExperience.rawLabel,
    ),
    feeling = OverallFeelingCode.fromWire(feeling),
    storyPreview = storyPreview,
    tipPreview = tipPreview,
    companion = companion?.let(CompanionCode::fromWire),
    timeOfDay = timeOfDay?.let(TimeOfDayCode::fromWire),
    vibes = vibes.map(VibeCode::fromWire),
    practicalSignals = practicalSignals.map(PracticalSignalCode::fromWire),
    mediaPreview = mediaPreview?.let {
        ExperienceMedia(
            kind = ExperienceMediaKind.fromWire(it.kind),
            position = 0,
            id = it.id?.toCanonicalUuid(),
            url = it.url,
            accessExpiresAt = it.accessExpiresAt,
        )
    },
    mediaCount = mediaCount,
    visibility = ExperienceVisibility.fromWire(visibility),
    plannedByViewer = plannedByViewer,
    acknowledgedByViewer = acknowledgedByViewer,
    acknowledgementCount = acknowledgementCount,
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
    media = media.sortedBy { it.order }.map {
        PublicReviewMedia(
            mediaId = it.mediaId,
            order = it.order,
            accessUrl = it.accessUrl,
            accessUrlExpiresAtEpochMillis = it.accessUrlExpiresAt?.toEpochMillisSafely(),
        )
    },
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

fun Visit.toCreateDto(clientMutationId: String? = null): CreateVisitDto = CreateVisitDto(
    clientMutationId = clientMutationId,
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

fun RelationshipV2Dto.toDomain(): RelationshipV2 = RelationshipV2(
    state = RelationshipActionState.fromWire(state),
    followsYou = followsYou,
    canFollow = canFollow,
    canCancelRequest = canCancelRequest,
)

fun ProfileV2Dto.toDomain(): ProfileV2 = ProfileV2(
    id = id.toCanonicalUuid(),
    username = username,
    displayName = displayName.ifBlank { username.ifBlank { "Traveler" } },
    avatarUrl = avatarUrl.orEmpty(),
    bio = bio.orEmpty(),
    visibility = ProfileVisibilityV2.fromWire(profileVisibility),
    fullProfile = fullProfile,
    relationship = relationship?.toDomain(),
    cityCount = cityCount,
    countryCount = countryCount,
    followerCount = followerCount,
    followingCount = followingCount,
    friendCount = friendCount,
    visibleExperienceCount = visibleExperienceCount,
)

fun FollowRequestV2Dto.toDomain(): FollowRequestV2 = FollowRequestV2(
    id = id.toCanonicalUuid(),
    requester = UserSummary(
        id = requester.id.toCanonicalUuid(),
        username = requester.username,
        displayName = requester.displayName.ifBlank { requester.username },
        avatarUrl = requester.avatarUrl.orEmpty(),
    ),
    status = status,
    createdAt = createdAt,
    resolvedAt = resolvedAt,
)

fun PlaceAggregateV2Dto.toDomain(): PlaceAggregateV2 = PlaceAggregateV2(
    place = PlaceAggregateIdentity(
        id = place.id.toCanonicalUuid(),
        name = place.name,
        categoryCode = place.category,
        city = place.city,
        region = place.region,
        country = place.country,
        coverImage = place.coverImage,
    ),
    visibleExperienceCount = visibleExperienceCount,
    communityContributionCount = communityContributionCount,
    primaryExperiences = primaryExperiences.map {
        PrimaryExperienceAggregate(
            code = PrimaryExperienceCode.fromWire(it.code),
            visibleExperienceCount = it.visibleExperienceCount,
        )
    },
    feelings = feelings.map {
        FeelingAggregate(OverallFeelingCode.fromWire(it.code), it.contributionCount)
    },
    dimensions = dimensions.map { dimension ->
        DimensionAggregateV2(
            key = dimension.key,
            contributionCount = dimension.contributionCount,
            numericAverage = dimension.numericAverage,
            legacyNumericContributionCount = dimension.legacyNumericContributionCount,
            semanticDistribution = dimension.semanticDistribution.map {
                SemanticStateAggregate(
                    state = DimensionStateCode.fromWire(it.state),
                    contributionCount = it.contributionCount,
                )
            },
        )
    },
    practicalSignals = practicalSignals.map {
        PracticalSignalAggregate(
            code = PracticalSignalCode.fromWire(it.code),
            contributionCount = it.contributionCount,
            eligibleContributionDenominator = it.eligibleContributionDenominator,
        )
    },
)

fun BlockedUserDto.toDomain(): BlockedUser = BlockedUser(
    userId = userId.toCanonicalUuid(),
    username = username,
    displayName = displayName.ifBlank { username },
    avatarUrl = avatarUrl,
    blockedAt = blockedAt,
)

fun ReportResponseDto.toDomain(): SubmittedReport = SubmittedReport(
    id = id.toCanonicalUuid(),
    targetType = ReportTargetType.valueOf(targetType.name),
    reason = ReportReason.valueOf(reason.name),
    status = status,
)

fun PolicyStatusDto.toDomain(): PolicyStatus = PolicyStatus(
    requiredVersion = requiredVersion,
    acceptedVersion = acceptedVersion,
    accepted = accepted,
)

fun ReportTargetType.toDto(): ReportTargetTypeDto = ReportTargetTypeDto.valueOf(name)

fun ReportReason.toDto(): ReportReasonDto = ReportReasonDto.valueOf(name)
