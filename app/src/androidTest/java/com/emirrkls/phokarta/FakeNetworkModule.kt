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
import com.emirrkls.phokarta.core.network.model.NearbyPlaceDto
import com.emirrkls.phokarta.core.network.model.PageResponseDto
import com.emirrkls.phokarta.core.network.model.PlaceCategoryDto
import com.emirrkls.phokarta.core.network.model.PlaceDetailDto
import com.emirrkls.phokarta.core.network.model.PlaceSummaryDto
import com.emirrkls.phokarta.core.network.model.PublicVisitDto
import com.emirrkls.phokarta.core.network.model.RefreshRequestDto
import com.emirrkls.phokarta.core.network.model.RegisterRequestDto
import com.emirrkls.phokarta.core.network.model.SavedPlaceDto
import com.emirrkls.phokarta.core.network.model.TokenPairDto
import com.emirrkls.phokarta.core.network.model.UserProfileDto
import com.emirrkls.phokarta.core.network.model.VerificationStatusDto
import com.emirrkls.phokarta.core.network.model.VisitOwnerDto
import com.emirrkls.phokarta.core.network.source.CollectionRemoteDataSource
import com.emirrkls.phokarta.core.network.source.PlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.SavedPlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.VisitRemoteDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.util.UUID
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import retrofit2.Response

private const val USER_ID = "11111111-1111-1111-1111-111111111111"
private const val PLACE_ID = "20000000-0000-0000-0000-000000000003"
private const val TIMESTAMP = "2026-08-22T10:00:00Z"

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [NetworkModule::class])
object FakeNetworkModule {
    @Provides @Singleton fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = true
    }
    @Provides @Singleton fun places(): PlaceRemoteDataSource = FakePlaces()
    @Provides @Singleton fun visits(): VisitRemoteDataSource = FakeVisits()
    @Provides @Singleton fun saved(): SavedPlaceRemoteDataSource = FakeSaved()
    @Provides @Singleton fun collections(): CollectionRemoteDataSource = FakeCollections()
    @Provides @Singleton fun authApi(): AuthApi = FakeAuthApi()
    @Provides @Singleton fun meApi(): MeApi = FakeMeApi()
}

private val summary = PlaceSummaryDto(
    PLACE_ID, "Sarnıç Cove", PlaceCategoryDto.BEACH,
    "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=1200",
    "Bodrum", "Muğla", "Türkiye", 37.085, 27.53, 2, 9.1, 42,
)
private val detail = PlaceDetailDto(
    PLACE_ID, "Sarnıç Cove", "A quiet Aegean cove.", PlaceCategoryDto.BEACH,
    listOf("Swimming"), 37.085, 27.53, "Bodrum", "Muğla", "Türkiye",
    "Bodrum, Muğla", summary.coverImage, listOf(summary.coverImage), 2, 9.1, 42,
    emptyList(), emptyList(),
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
        RemoteResult.Success(listOf(summary).filter { (category == null || it.category == category) && (minRating == null || (it.averageScore ?: 0.0) >= minRating) })
    override suspend fun detail(id: String) = RemoteResult.Success(detail)
}

private class FakeVisits : VisitRemoteDataSource {
    private val visits = mutableListOf<VisitOwnerDto>()
    private val publicReviews = mutableListOf<PublicVisitDto>()

    override suspend fun create(request: CreateVisitDto): RemoteResult<VisitOwnerDto> {
        val visitId = UUID.randomUUID().toString()
        val visit = VisitOwnerDto(
            visitId, summary, request.visitedAt,
            request.overallRating, request.dimensions.orEmpty(), request.publicReview.orEmpty(),
            request.privateMemory.orEmpty(), request.photos.orEmpty(), request.visibility,
            VerificationStatusDto.UNVERIFIED,
        )
        visits += visit
        if (request.visibility == com.emirrkls.phokarta.core.network.model.VisibilityDto.PUBLIC) {
            publicReviews += PublicVisitDto(
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
            publicReviews.sortByDescending { it.visitedAt }
        }
        return RemoteResult.Success(visit)
    }

    override suspend fun ownerVisits(page: Int, size: Int) = RemoteResult.Success(page(visits.toList()))

    override suspend fun publicReviews(placeId: String, page: Int, size: Int): RemoteResult<PageResponseDto<PublicVisitDto>> {
        val matching = publicReviews.filter { it.placeId == placeId }
        val from = (page * size).coerceAtMost(matching.size)
        val to = (from + size).coerceAtMost(matching.size)
        val slice = matching.subList(from, to)
        val totalPages = if (matching.isEmpty()) 0 else ((matching.size + size - 1) / size)
        return RemoteResult.Success(
            PageResponseDto(
                content = slice,
                page = page,
                size = size,
                totalElements = matching.size.toLong(),
                totalPages = totalPages,
                hasNext = to < matching.size,
            ),
        )
    }
}

private class FakeSaved : SavedPlaceRemoteDataSource {
    private val saved = linkedSetOf<String>()
    override suspend fun list(page: Int, size: Int) =
        RemoteResult.Success(page(if (PLACE_ID in saved) listOf(SavedPlaceDto(summary, TIMESTAMP)) else emptyList()))
    override suspend fun save(placeId: String): RemoteResult<SavedPlaceDto> {
        saved += placeId
        return RemoteResult.Success(SavedPlaceDto(summary, TIMESTAMP))
    }
    override suspend fun remove(placeId: String): RemoteResult<Unit> {
        saved -= placeId
        return RemoteResult.Success(Unit)
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

private class FakeMeApi : MeApi {
    override suspend fun profile(): Response<UserProfileDto> =
        Response.success(
            UserProfileDto(USER_ID, "demo@phokarta.local", "emir_demo", "Emir Kaya", "bio", null),
        )
}

private fun demoSession(email: String, username: String, displayName: String) = AuthSessionDto(
    user = UserProfileDto(USER_ID, email, username, displayName, null, null),
    accessToken = "access-token",
    refreshToken = "refresh-token",
)
