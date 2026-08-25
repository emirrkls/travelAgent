package com.emirrkls.phokarta

import com.emirrkls.phokarta.core.network.NetworkError
import com.emirrkls.phokarta.core.network.NetworkModule
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.api.AuthApi
import com.emirrkls.phokarta.core.network.api.MeApi
import com.emirrkls.phokarta.core.network.model.AuthSessionDto
import com.emirrkls.phokarta.core.network.model.CollectionDetailDto
import com.emirrkls.phokarta.core.network.model.CollectionPlaceDto
import com.emirrkls.phokarta.core.network.model.CollectionSummaryDto
import com.emirrkls.phokarta.core.network.model.CreateCollectionDto
import com.emirrkls.phokarta.core.network.model.CreateVisitDto
import com.emirrkls.phokarta.core.network.model.LoginRequestDto
import com.emirrkls.phokarta.core.network.model.LogoutRequestDto
import com.emirrkls.phokarta.core.network.model.FriendMetricsDto
import com.emirrkls.phokarta.core.network.model.FriendMetricsRequestDto
import com.emirrkls.phokarta.core.network.model.FriendPlaceSummaryDto
import com.emirrkls.phokarta.core.network.model.FriendPlaceUserDto
import com.emirrkls.phokarta.core.network.model.NearbyPlaceDto
import com.emirrkls.phokarta.core.network.model.PageResponseDto
import com.emirrkls.phokarta.core.network.model.PlaceCategoryDto
import com.emirrkls.phokarta.core.network.model.PlaceDetailDto
import com.emirrkls.phokarta.core.network.model.PlaceSummaryDto
import com.emirrkls.phokarta.core.network.model.PublicActivityDto
import com.emirrkls.phokarta.core.network.model.PublicActivityAuthorDto
import com.emirrkls.phokarta.core.network.model.PublicActivityPlaceDto
import com.emirrkls.phokarta.core.network.model.PublicVisitDto
import com.emirrkls.phokarta.core.network.model.RefreshRequestDto
import com.emirrkls.phokarta.core.network.model.RegisterRequestDto
import com.emirrkls.phokarta.core.network.model.RelationshipStateDto
import com.emirrkls.phokarta.core.network.model.SavedPlaceDto
import com.emirrkls.phokarta.core.network.model.TokenPairDto
import com.emirrkls.phokarta.core.network.model.UserProfileDto
import com.emirrkls.phokarta.core.network.model.UserSummaryDto
import com.emirrkls.phokarta.core.network.model.VerificationStatusDto
import com.emirrkls.phokarta.core.network.model.VisitOwnerDto
import com.emirrkls.phokarta.core.network.model.PublicUserProfileDto
import com.emirrkls.phokarta.core.network.source.CollectionRemoteDataSource
import com.emirrkls.phokarta.core.network.source.PlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.SavedPlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.SocialRemoteDataSource
import com.emirrkls.phokarta.core.network.source.VisitRemoteDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import retrofit2.Response

private const val USER_ID = "11111111-1111-1111-1111-111111111111"
private const val OTHER_USER_ID = "22222222-2222-2222-2222-222222222222"
private const val THIRD_USER_ID = "33333333-3333-3333-3333-333333333333"
private const val FOURTH_USER_ID = "44444444-4444-4444-4444-444444444444"
private const val PLACE_ID = "20000000-0000-0000-0000-000000000003"
private const val OTHER_PLACE_ID = "20000000-0000-0000-0000-000000000099"
private const val FRIEND_ONLY_PLACE_ID = "20000000-0000-0000-0000-000000000088"
private const val TIMESTAMP = "2026-08-22T10:00:00Z"

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [NetworkModule::class])
object FakeNetworkModule {
    @Provides @Singleton fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = true
    }
    @Provides @Singleton fun places(): PlaceRemoteDataSource = FakePlaces()
    @Provides @Singleton fun fakeSocial(): FakeSocial = FakeSocial()
    @Provides @Singleton fun fakeVisits(social: FakeSocial): FakeVisits = FakeVisits(social)
    @Provides @Singleton fun visits(fake: FakeVisits): VisitRemoteDataSource = fake
    @Provides @Singleton fun saved(visits: VisitRemoteDataSource): SavedPlaceRemoteDataSource =
        FakeSaved(visits as FakeVisits)
    @Provides @Singleton fun collections(): CollectionRemoteDataSource = FakeCollections()
    @Provides @Singleton fun social(fake: FakeSocial): SocialRemoteDataSource = fake
    @Provides @Singleton fun authApi(): AuthApi = FakeAuthApi()
    @Provides @Singleton fun meApi(fake: FakeSocial): MeApi = FakeMeApi(fake)
}

private val summary = PlaceSummaryDto(
    PLACE_ID, "Sarnıç Cove", PlaceCategoryDto.BEACH,
    "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=1200",
    "Bodrum", "Muğla", "Türkiye", 37.085, 27.53, 2, 8.7, 42,
)
private val detail = PlaceDetailDto(
    PLACE_ID, "Sarnıç Cove", "A quiet Aegean cove.", PlaceCategoryDto.BEACH,
    listOf("Swimming"), 37.085, 27.53, "Bodrum", "Muğla", "Türkiye",
    "Bodrum, Muğla", summary.coverImage, listOf(summary.coverImage), 2, 8.7, 42,
    emptyList(), emptyList(),
)

private val otherSummary = PlaceSummaryDto(
    OTHER_PLACE_ID, "Quiet Bay", PlaceCategoryDto.CAFE,
    "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=800",
    "Bodrum", "Muğla", "Türkiye", 37.09, 27.54, 2, 8.0, 12,
)

private val friendOnlySummary = PlaceSummaryDto(
    FRIEND_ONLY_PLACE_ID, "Friend Cove", PlaceCategoryDto.BEACH,
    "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=900",
    "Bodrum", "Muğla", "Türkiye", 37.08, 27.52, 2, 8.4, 18,
)

private fun <T> page(values: List<T>) = PageResponseDto(values, 0, 100, values.size.toLong(), 1, false)

private class FakePlaces : PlaceRemoteDataSource {
    override suspend fun list(category: PlaceCategoryDto?, city: String?, search: String?, minRating: Double?, sort: String, page: Int, size: Int) =
        RemoteResult.Success(page(listOf(summary).filter {
            (category == null || it.category == category) &&
                (search.isNullOrBlank() || it.name.contains(search, true) || it.city.contains(search, true))
        }))
    override suspend fun nearby(latitude: Double, longitude: Double, radiusMeters: Double, category: PlaceCategoryDto?, minRating: Double?, limit: Int) =
        RemoteResult.Success(listOf(NearbyPlaceDto(summary, 240.0)))
    override suspend fun bounds(west: Double, south: Double, east: Double, north: Double, category: PlaceCategoryDto?, minRating: Double?, limit: Int) =
        RemoteResult.Success(
            listOf(summary, otherSummary, friendOnlySummary).filter {
                (category == null || it.category == category) &&
                    (minRating == null || (it.averageScore ?: 0.0) >= minRating)
            },
        )
    override suspend fun detail(id: String) = RemoteResult.Success(detail)
}

class FakeVisits(
    private val social: FakeSocial,
) : VisitRemoteDataSource {
    private val visits = mutableListOf<VisitOwnerDto>()
    /** Community-readable (PUBLIC) only. */
    private val communityReviews = mutableListOf<PublicVisitDto>()
    private val communityActivity = mutableListOf<PublicActivityDto>()
    /** Friend-readable (PUBLIC + FRIENDS). */
    private val friendReadableReviews = mutableListOf<PublicVisitDto>()
    private val friendReadableActivity = mutableListOf<PublicActivityDto>()
    @Volatile var failCreate: Boolean = false
    @Volatile var failCreatePermanent: NetworkError? = null
    val recordedClientMutationIds = mutableListOf<String>()

    fun resetRecordedClientMutationIds() {
        recordedClientMutationIds.clear()
    }

    init {
        fun indexActivity(event: PublicActivityDto, friendOnly: Boolean = false) {
            friendReadableActivity += event
            if (!friendOnly) communityActivity += event
        }
        fun indexReview(dto: PublicVisitDto, friendOnly: Boolean = false) {
            friendReadableReviews += dto
            if (!friendOnly) communityReviews += dto
        }

        indexActivity(
            PublicActivityDto(
                visitId = "30000000-0000-0000-0000-000000000101",
                author = PublicActivityAuthorDto(OTHER_USER_ID, "ahmetgoes", "Ahmet Deniz", null),
                place = activityPlace(),
                overallScore = 9.1,
                publicReview = "Beautiful cove with clear water.",
                visitedAt = "2026-08-20",
            ),
        )
        indexActivity(
            PublicActivityDto(
                visitId = "30000000-0000-0000-0000-000000000102",
                author = PublicActivityAuthorDto(THIRD_USER_ID, "eceeats", "Ece Aksoy", null),
                place = activityPlace(),
                overallScore = 8.7,
                publicReview = "",
                visitedAt = "2026-08-18",
            ),
        )
        indexActivity(
            PublicActivityDto(
                visitId = "30000000-0000-0000-0000-000000000103",
                author = PublicActivityAuthorDto(FOURTH_USER_ID, "denizmaps", "Deniz Community", null),
                place = activityPlace(),
                overallScore = 8.2,
                publicReview = "Community-only cove notes.",
                visitedAt = "2026-08-17",
            ),
        )
        // Mutual friend's FRIENDS-only visit — friends activity yes, community no.
        indexActivity(
            PublicActivityDto(
                visitId = "30000000-0000-0000-0000-000000000104",
                author = PublicActivityAuthorDto(OTHER_USER_ID, "ahmetgoes", "Ahmet Deniz", null),
                place = activityPlace(),
                overallScore = 9.6,
                publicReview = "Friends-only cove notes.",
                visitedAt = "2026-08-21",
            ),
            friendOnly = true,
        )
        repeat(22) { index ->
            indexActivity(
                PublicActivityDto(
                    visitId = "30000000-0000-0000-0000-${"%012d".format(200 + index)}",
                    author = PublicActivityAuthorDto(OTHER_USER_ID, "ahmetgoes", "Ahmet Deniz", null),
                    place = activityPlace(),
                    overallScore = 7.5,
                    publicReview = "Paged activity $index",
                    visitedAt = "2026-07-%02d".format((index % 28) + 1),
                ),
            )
        }
        communityActivity.sortByDescending { it.visitedAt }
        friendReadableActivity.sortByDescending { it.visitedAt }

        indexReview(
            PublicVisitDto(
                id = "30000000-0000-0000-0000-000000000201",
                placeId = PLACE_ID,
                placeName = summary.name,
                userId = OTHER_USER_ID,
                username = "ahmetgoes",
                displayName = "Ahmet Deniz",
                avatarUrl = null,
                visitedAt = "2026-05-03",
                overallRating = 9.4,
                publicReview = "Friend public review of the cove.",
                photos = emptyList(),
                verificationStatus = VerificationStatusDto.UNVERIFIED,
            ),
        )
        indexReview(
            PublicVisitDto(
                id = "30000000-0000-0000-0000-000000000203",
                placeId = PLACE_ID,
                placeName = summary.name,
                userId = OTHER_USER_ID,
                username = "ahmetgoes",
                displayName = "Ahmet Deniz",
                avatarUrl = null,
                visitedAt = "2026-03-01",
                overallRating = 8.8,
                publicReview = "Earlier friend visit.",
                photos = emptyList(),
                verificationStatus = VerificationStatusDto.UNVERIFIED,
            ),
        )
        indexReview(
            PublicVisitDto(
                id = "30000000-0000-0000-0000-000000000202",
                placeId = PLACE_ID,
                placeName = summary.name,
                userId = THIRD_USER_ID,
                username = "eceeats",
                displayName = "Ece Aksoy",
                avatarUrl = null,
                visitedAt = "2026-04-10",
                overallRating = 8.0,
                publicReview = "One-way visitor review.",
                photos = emptyList(),
                verificationStatus = VerificationStatusDto.UNVERIFIED,
            ),
        )
        indexReview(
            PublicVisitDto(
                id = "30000000-0000-0000-0000-000000000204",
                placeId = PLACE_ID,
                placeName = summary.name,
                userId = OTHER_USER_ID,
                username = "ahmetgoes",
                displayName = "Ahmet Deniz",
                avatarUrl = null,
                visitedAt = "2026-05-10",
                overallRating = 9.1,
                publicReview = "Friend-only review of the cove.",
                photos = emptyList(),
                verificationStatus = VerificationStatusDto.UNVERIFIED,
            ),
            friendOnly = true,
        )
        communityReviews.sortByDescending { it.visitedAt }
        friendReadableReviews.sortByDescending { it.visitedAt }
    }

    override suspend fun create(request: CreateVisitDto): RemoteResult<VisitOwnerDto> {
        request.clientMutationId?.let { recordedClientMutationIds += it }
        failCreatePermanent?.let { return RemoteResult.Failure(it) }
        if (failCreate) return RemoteResult.Failure(NetworkError.Server(500, null))
        val visitId = UUID.randomUUID().toString()
        val visit = VisitOwnerDto(
            visitId, summary, request.visitedAt,
            request.overallRating, request.dimensions.orEmpty(), request.publicReview.orEmpty(),
            request.privateMemory.orEmpty(), request.photos.orEmpty(), request.visibility,
            VerificationStatusDto.UNVERIFIED,
        )
        visits += visit
        val visibility = request.visibility
        if (visibility == com.emirrkls.phokarta.core.network.model.VisibilityDto.PUBLIC ||
            visibility == com.emirrkls.phokarta.core.network.model.VisibilityDto.FRIENDS
        ) {
            val dto = PublicVisitDto(
                id = visitId,
                placeId = request.placeId,
                placeName = summary.name,
                userId = USER_ID,
                username = "emir_demo",
                displayName = "Emir Kaya",
                avatarUrl = null,
                visitedAt = request.visitedAt,
                overallRating = request.overallRating,
                publicReview = request.publicReview.orEmpty(),
                photos = request.photos.orEmpty(),
                verificationStatus = VerificationStatusDto.UNVERIFIED,
            )
            val event = PublicActivityDto(
                visitId = visitId,
                author = PublicActivityAuthorDto(USER_ID, "emir_demo", "Emir Kaya", null),
                place = activityPlace(request.placeId),
                overallScore = request.overallRating,
                publicReview = request.publicReview.orEmpty(),
                visitedAt = request.visitedAt,
            )
            friendReadableReviews += dto
            friendReadableReviews.sortByDescending { it.visitedAt }
            friendReadableActivity += event
            friendReadableActivity.sortByDescending { it.visitedAt }
            if (visibility == com.emirrkls.phokarta.core.network.model.VisibilityDto.PUBLIC) {
                communityReviews += dto
                communityReviews.sortByDescending { it.visitedAt }
                communityActivity += event
                communityActivity.sortByDescending { it.visitedAt }
            }
        }
        return RemoteResult.Success(visit)
    }

    override suspend fun ownerVisits(page: Int, size: Int) = RemoteResult.Success(page(visits.toList()))

    override suspend fun publicReviews(
        placeId: String,
        scope: String?,
        page: Int,
        size: Int,
    ): RemoteResult<PageResponseDto<PublicVisitDto>> {
        val source = if (scope?.lowercase() == "friends") friendReadableReviews else communityReviews
        val matching = source.filter { it.placeId == placeId }.filterByScope(scope) { it.userId }
        return RemoteResult.Success(paginate(matching, page, size))
    }

    override suspend fun publicActivity(
        scope: String?,
        page: Int,
        size: Int,
    ): RemoteResult<PageResponseDto<PublicActivityDto>> {
        val source = if (scope?.lowercase() == "friends") friendReadableActivity else communityActivity
        val matching = source.filterByScope(scope) { it.author.id }
        return RemoteResult.Success(paginate(matching, page, size))
    }

    override suspend fun friendsSummary(placeId: String): RemoteResult<FriendPlaceSummaryDto> {
        if (placeId != PLACE_ID) {
            return RemoteResult.Success(FriendPlaceSummaryDto(null, 0, emptyList()))
        }
        val friends = social.mutualFriendIds()
        val friendVisits = friendReadableReviews
            .filter { it.placeId == placeId && it.userId in friends }
            .groupBy { it.userId }
        if (friendVisits.isEmpty()) {
            return RemoteResult.Success(FriendPlaceSummaryDto(null, 0, emptyList()))
        }
        val previews = friendVisits.map { (userId, visitsForUser) ->
            val latest = visitsForUser.maxBy { it.visitedAt }
            FriendPlaceUserDto(
                userId = userId,
                displayName = latest.displayName,
                avatarUrl = latest.avatarUrl,
                latestScore = latest.overallRating,
                latestVisitedAt = latest.visitedAt,
            )
        }.sortedByDescending { it.latestVisitedAt }
        val average = friendVisits.values
            .map { visitsForUser -> visitsForUser.map { it.overallRating }.average() }
            .average()
        return RemoteResult.Success(
            FriendPlaceSummaryDto(
                averageScore = average,
                friendsVisitedCount = previews.size.toLong(),
                friends = previews.take(5),
            ),
        )
    }

    private fun <T> List<T>.filterByScope(scope: String?, userId: (T) -> String): List<T> {
        return when (scope?.lowercase()) {
            "friends" -> filter { id ->
                val uid = userId(id)
                uid != USER_ID && social.isMutualFriend(uid)
            }
            else -> this
        }
    }

    private fun <T> paginate(values: List<T>, page: Int, size: Int): PageResponseDto<T> {
        val from = (page * size).coerceAtMost(values.size)
        val to = (from + size).coerceAtMost(values.size)
        val totalPages = if (values.isEmpty()) 0 else ((values.size + size - 1) / size)
        return PageResponseDto(
            content = values.subList(from, to),
            page = page,
            size = size,
            totalElements = values.size.toLong(),
            totalPages = totalPages,
            hasNext = to < values.size,
        )
    }

    private fun activityPlace(placeId: String = PLACE_ID) = PublicActivityPlaceDto(
        id = placeId,
        name = summary.name,
        category = summary.category,
        city = summary.city,
        coverImage = summary.coverImage,
    )
}

private class FakeSaved(
    private val visits: FakeVisits,
) : SavedPlaceRemoteDataSource {
    private val saved = linkedSetOf<String>()
    private val summaries = mapOf(
        PLACE_ID to summary,
        OTHER_PLACE_ID to otherSummary,
        FRIEND_ONLY_PLACE_ID to friendOnlySummary,
    )

    override suspend fun list(page: Int, size: Int): RemoteResult<PageResponseDto<SavedPlaceDto>> {
        val content = saved.mapNotNull { id ->
            val place = summaries[id] ?: return@mapNotNull null
            enrichedDto(place)
        }
        return RemoteResult.Success(paginate(content, page, size))
    }

    override suspend fun save(placeId: String): RemoteResult<SavedPlaceDto> {
        val place = summaries[placeId] ?: summary.copy(id = placeId)
        saved += placeId
        return RemoteResult.Success(enrichedDto(place))
    }

    override suspend fun remove(placeId: String): RemoteResult<Unit> {
        saved -= placeId
        return RemoteResult.Success(Unit)
    }

    private suspend fun enrichedDto(place: PlaceSummaryDto): SavedPlaceDto {
        val friend = when (val result = visits.friendsSummary(place.id)) {
            is RemoteResult.Success -> result.value
            is RemoteResult.Failure -> FriendPlaceSummaryDto(null, 0, emptyList())
        }
        return SavedPlaceDto(
            place = place,
            savedAt = TIMESTAMP,
            friendAverageScore = friend.averageScore,
            friendsVisitedCount = friend.friendsVisitedCount,
        )
    }

    private fun <T> paginate(values: List<T>, page: Int, size: Int): PageResponseDto<T> {
        val from = (page * size).coerceAtMost(values.size)
        val to = (from + size).coerceAtMost(values.size)
        val totalPages = if (values.isEmpty()) 0 else ((values.size + size - 1) / size)
        return PageResponseDto(
            content = values.subList(from, to),
            page = page,
            size = size,
            totalElements = values.size.toLong(),
            totalPages = totalPages,
            hasNext = to < values.size,
        )
    }
}

private class FakeCollections : CollectionRemoteDataSource {
    private val collections = linkedMapOf<String, CollectionDetailDto>()

    override suspend fun list(page: Int, size: Int) =
        RemoteResult.Success(page(collections.values.map { it.toSummary() }))

    override suspend fun create(request: CreateCollectionDto): RemoteResult<CollectionDetailDto> {
        val detail = CollectionDetailDto(
            id = UUID.randomUUID().toString(),
            userId = USER_ID,
            title = request.title,
            description = request.description.orEmpty(),
            visibility = request.visibility,
            coverImage = request.coverImage,
            createdAt = TIMESTAMP,
            updatedAt = TIMESTAMP,
            places = emptyList(),
        )
        collections[detail.id] = detail
        return RemoteResult.Success(detail)
    }

    override suspend fun detail(collectionId: String): RemoteResult<CollectionDetailDto> =
        collections[collectionId]?.let { RemoteResult.Success(it) }
            ?: RemoteResult.Failure(NetworkError.NotFound(null))

    override suspend fun addPlace(collectionId: String, placeId: String): RemoteResult<CollectionDetailDto> {
        val current = collections[collectionId]
            ?: return RemoteResult.Failure(NetworkError.NotFound(null))
        if (current.places.any { it.place.id == placeId }) {
            return RemoteResult.Failure(NetworkError.Conflict(null))
        }
        if (placeId != PLACE_ID) {
            return RemoteResult.Failure(NetworkError.NotFound(null))
        }
        val updated = current.copy(
            places = current.places + CollectionPlaceDto(
                place = summary,
                displayOrder = current.places.size,
                addedAt = TIMESTAMP,
            ),
            updatedAt = TIMESTAMP,
        )
        collections[collectionId] = updated
        return RemoteResult.Success(updated)
    }

    override suspend fun removePlace(collectionId: String, placeId: String): RemoteResult<Unit> {
        val current = collections[collectionId]
            ?: return RemoteResult.Failure(NetworkError.NotFound(null))
        collections[collectionId] = current.copy(
            places = current.places.filterNot { it.place.id == placeId },
            updatedAt = TIMESTAMP,
        )
        return RemoteResult.Success(Unit)
    }

    private fun CollectionDetailDto.toSummary() = CollectionSummaryDto(
        id = id,
        userId = userId,
        title = title,
        description = description,
        visibility = visibility,
        coverImage = coverImage,
        placeCount = places.size.toLong(),
        updatedAt = updatedAt,
    )
}

private class FakeAuthApi : AuthApi {
    override suspend fun register(request: RegisterRequestDto): Response<AuthSessionDto> =
        Response.success(demoSession(request.email, request.username, request.displayName))
    override suspend fun login(request: LoginRequestDto): Response<AuthSessionDto> =
        Response.success(demoSession("demo@phokarta.local", "emir_demo", "Emir Kaya"))
    override suspend fun refresh(request: RefreshRequestDto): Response<TokenPairDto> =
        Response.success(TokenPairDto("access-refreshed", "refresh-rotated"))
    override suspend fun logout(request: LogoutRequestDto): Response<Unit> =
        Response.success(Unit)
}

private class FakeMeApi(
    private val social: FakeSocial,
) : MeApi {
    override suspend fun profile(): Response<UserProfileDto> =
        Response.success(
            UserProfileDto(
                USER_ID,
                "demo@phokarta.local",
                "emir_demo",
                "Emir Kaya",
                "bio",
                null,
                social.ownerFollowerCount(),
                social.ownerFollowingCount(),
                social.ownerFriendCount(),
            ),
        )

    override suspend fun followers(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> =
        Response.success(page(emptyList()))

    override suspend fun following(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> =
        Response.success(page(emptyList()))

    override suspend fun friends(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> =
        Response.success(page(emptyList()))

    override suspend fun friendMetrics(
        request: FriendMetricsRequestDto,
    ): Response<List<FriendMetricsDto>> = Response.success(emptyList())
}

class FakeSocial : SocialRemoteDataSource {
    private val following = ConcurrentHashMap.newKeySet<String>()
    private val followsYou = setOf(OTHER_USER_ID)
    @Volatile var failFriendMetrics: Boolean = false

    private val users = listOf(
        summary(OTHER_USER_ID, "ahmetgoes", "Ahmet Deniz"),
        summary(THIRD_USER_ID, "selinmaps", "Selin Maps"),
        summary(FOURTH_USER_ID, "denizmaps", "Deniz Community"),
    )

    fun ownerFollowerCount(): Long = followsYou.size.toLong()
    fun ownerFollowingCount(): Long = following.size.toLong()
    fun ownerFriendCount(): Long = following.count { it in followsYou }.toLong()

    fun isMutualFriend(userId: String): Boolean = userId in following && userId in followsYou

    fun mutualFriendIds(): Set<String> = following.filter { it in followsYou }.toSet()

    fun seedMutualFriend(userId: String = OTHER_USER_ID) {
        following += userId
    }

    fun reset() {
        following.clear()
        failFriendMetrics = false
    }

    override suspend fun search(query: String, page: Int, size: Int): RemoteResult<PageResponseDto<UserSummaryDto>> {
        val matched = users.filter {
            it.username.contains(query, true) || it.displayName.contains(query, true)
        }.map { it.withRelationship() }
        return RemoteResult.Success(page(matched))
    }

    override suspend fun profile(userId: String): RemoteResult<PublicUserProfileDto> {
        val user = users.firstOrNull { it.id == userId }
            ?: return RemoteResult.Failure(NetworkError.NotFound(null))
        val isFollowing = userId in following
        val inbound = userId in followsYou
        return RemoteResult.Success(
            PublicUserProfileDto(
                id = user.id,
                username = user.username,
                displayName = user.displayName,
                avatarUrl = null,
                bio = "Travel notes",
                cityCount = 4,
                countryCount = 2,
                followerCount = if (isFollowing) 12 else 11,
                followingCount = 5,
                friendCount = if (isFollowing && inbound) 1 else 0,
                relationship = RelationshipStateDto(isFollowing, inbound, isFollowing && inbound),
            ),
        )
    }

    override suspend fun follow(userId: String): RemoteResult<Unit> {
        following += userId
        return RemoteResult.Success(Unit)
    }

    override suspend fun unfollow(userId: String): RemoteResult<Unit> {
        following -= userId
        return RemoteResult.Success(Unit)
    }

    override suspend fun followers(page: Int, size: Int): RemoteResult<PageResponseDto<UserSummaryDto>> =
        RemoteResult.Success(page(listOf(summary(OTHER_USER_ID, "ahmetgoes", "Ahmet Deniz").withRelationship())))

    override suspend fun following(page: Int, size: Int): RemoteResult<PageResponseDto<UserSummaryDto>> =
        RemoteResult.Success(
            page(
                following.mapNotNull { id -> users.firstOrNull { it.id == id }?.withRelationship() },
            ),
        )

    override suspend fun friends(page: Int, size: Int): RemoteResult<PageResponseDto<UserSummaryDto>> =
        RemoteResult.Success(
            page(
                following.filter { it in followsYou }
                    .mapNotNull { id -> users.firstOrNull { it.id == id }?.withRelationship() },
            ),
        )

    override suspend fun meProfile(): RemoteResult<UserProfileDto> =
        RemoteResult.Success(
            UserProfileDto(
                USER_ID,
                "demo@phokarta.local",
                "emir_demo",
                "Emir Kaya",
                "bio",
                null,
                ownerFollowerCount(),
                ownerFollowingCount(),
                ownerFriendCount(),
            ),
        )

    override suspend fun friendMetrics(placeIds: List<String>): RemoteResult<List<FriendMetricsDto>> {
        if (failFriendMetrics) return RemoteResult.Failure(NetworkError.Connection)
        val unique = placeIds.distinct()
        val friendSignal = isMutualFriend(OTHER_USER_ID)
        return RemoteResult.Success(
            unique.map { id ->
                if (friendSignal && (id == PLACE_ID || id == FRIEND_ONLY_PLACE_ID)) {
                    FriendMetricsDto(placeId = id, friendAverageScore = 9.1, friendsVisitedCount = 1)
                } else {
                    FriendMetricsDto(placeId = id, friendAverageScore = null, friendsVisitedCount = 0)
                }
            },
        )
    }

    private fun summary(id: String, username: String, displayName: String) =
        UserSummaryDto(id, username, displayName, null, null)

    private fun UserSummaryDto.withRelationship(): UserSummaryDto {
        val isFollowing = id in following
        val inbound = id in followsYou
        return copy(relationship = RelationshipStateDto(isFollowing, inbound, isFollowing && inbound))
    }
}

private fun demoSession(email: String, username: String, displayName: String) = AuthSessionDto(
    user = UserProfileDto(USER_ID, email, username, displayName, null, null, 0, 0, 0),
    accessToken = "access-token",
    refreshToken = "refresh-token",
)
