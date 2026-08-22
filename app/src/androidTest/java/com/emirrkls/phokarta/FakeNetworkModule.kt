package com.emirrkls.phokarta

import com.emirrkls.phokarta.core.network.DemoUserProvider
import com.emirrkls.phokarta.core.network.NetworkModule
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.model.CollectionDetailDto
import com.emirrkls.phokarta.core.network.model.CollectionSummaryDto
import com.emirrkls.phokarta.core.network.model.CreateCollectionDto
import com.emirrkls.phokarta.core.network.model.CreateVisitDto
import com.emirrkls.phokarta.core.network.model.NearbyPlaceDto
import com.emirrkls.phokarta.core.network.model.PageResponseDto
import com.emirrkls.phokarta.core.network.model.PlaceCategoryDto
import com.emirrkls.phokarta.core.network.model.PlaceDetailDto
import com.emirrkls.phokarta.core.network.model.PlaceSummaryDto
import com.emirrkls.phokarta.core.network.model.PublicVisitDto
import com.emirrkls.phokarta.core.network.model.SavedPlaceDto
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
import javax.inject.Singleton

private const val USER_ID = "11111111-1111-1111-1111-111111111111"
private const val PLACE_ID = "20000000-0000-0000-0000-000000000003"

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [NetworkModule::class])
object FakeNetworkModule {
    @Provides @Singleton fun places(): PlaceRemoteDataSource = FakePlaces()
    @Provides @Singleton fun visits(): VisitRemoteDataSource = FakeVisits()
    @Provides @Singleton fun saved(): SavedPlaceRemoteDataSource = FakeSaved()
    @Provides @Singleton fun collections(): CollectionRemoteDataSource = FakeCollections()
    @Provides @Singleton fun user(): DemoUserProvider = object : DemoUserProvider {
        override val userId = USER_ID
    }
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
    override suspend fun create(request: CreateVisitDto): RemoteResult<VisitOwnerDto> {
        val visit = VisitOwnerDto(
            "33333333-3333-3333-3333-333333333333", summary, request.visitedAt,
            request.overallRating, request.dimensions.orEmpty(), request.publicReview.orEmpty(),
            request.privateMemory.orEmpty(), request.photos.orEmpty(), request.visibility,
            VerificationStatusDto.UNVERIFIED,
        )
        visits.removeAll { it.id == visit.id }
        visits += visit
        return RemoteResult.Success(visit)
    }
    override suspend fun ownerVisits(userId: String, page: Int, size: Int) = RemoteResult.Success(page(visits.toList()))
    override suspend fun publicReviews(placeId: String, page: Int, size: Int) =
        RemoteResult.Success(page(emptyList<PublicVisitDto>()))
}

private class FakeSaved : SavedPlaceRemoteDataSource {
    private val saved = linkedSetOf<String>()
    override suspend fun list(userId: String, page: Int, size: Int) =
        RemoteResult.Success(page(if (PLACE_ID in saved) listOf(SavedPlaceDto(summary, "2026-08-22T10:00:00Z")) else emptyList()))
    override suspend fun save(userId: String, placeId: String): RemoteResult<SavedPlaceDto> {
        saved += placeId
        return RemoteResult.Success(SavedPlaceDto(summary, "2026-08-22T10:00:00Z"))
    }
    override suspend fun remove(userId: String, placeId: String): RemoteResult<Unit> {
        saved -= placeId
        return RemoteResult.Success(Unit)
    }
}

private class FakeCollections : CollectionRemoteDataSource {
    override suspend fun list(userId: String, page: Int, size: Int) = RemoteResult.Success(page(emptyList<CollectionSummaryDto>()))
    override suspend fun create(userId: String, request: CreateCollectionDto): RemoteResult<CollectionDetailDto> = error("Not used")
    override suspend fun detail(collectionId: String): RemoteResult<CollectionDetailDto> = error("Not used")
    override suspend fun addPlace(collectionId: String, placeId: String, userId: String): RemoteResult<CollectionDetailDto> = error("Not used")
    override suspend fun removePlace(collectionId: String, placeId: String, userId: String) = RemoteResult.Success(Unit)
}
