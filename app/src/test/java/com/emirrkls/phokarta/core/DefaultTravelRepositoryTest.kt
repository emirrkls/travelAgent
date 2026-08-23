package com.emirrkls.phokarta.core

import com.emirrkls.phokarta.core.auth.testSessionManager
import com.emirrkls.phokarta.core.data.DefaultTravelRepository
import com.emirrkls.phokarta.core.data.LocalUserStateDataSource
import com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource
import com.emirrkls.phokarta.core.data.PlaceCacheDataSource
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.network.NetworkError
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.mapper.toDomain
import com.emirrkls.phokarta.core.network.model.CollectionDetailDto
import com.emirrkls.phokarta.core.network.model.CollectionSummaryDto
import com.emirrkls.phokarta.core.network.model.CreateCollectionDto
import com.emirrkls.phokarta.core.network.model.CreateVisitDto
import com.emirrkls.phokarta.core.network.model.DimensionScoreDto
import com.emirrkls.phokarta.core.network.model.NearbyPlaceDto
import com.emirrkls.phokarta.core.network.model.PageResponseDto
import com.emirrkls.phokarta.core.network.model.PlaceCategoryDto
import com.emirrkls.phokarta.core.network.model.PlaceDetailDto
import com.emirrkls.phokarta.core.network.model.PlaceSummaryDto
import com.emirrkls.phokarta.core.network.model.PublicVisitDto
import com.emirrkls.phokarta.core.network.model.RatingDimensionDto
import com.emirrkls.phokarta.core.network.model.SavedPlaceDto
import com.emirrkls.phokarta.core.network.model.VerificationStatusDto
import com.emirrkls.phokarta.core.network.model.VisitOwnerDto
import com.emirrkls.phokarta.core.network.model.VisibilityDto
import com.emirrkls.phokarta.core.network.source.CollectionRemoteDataSource
import com.emirrkls.phokarta.core.network.source.PlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.SavedPlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.VisitRemoteDataSource
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val USER_ID = "11111111-1111-1111-1111-111111111111"
private const val PLACE_ID = "22222222-2222-2222-2222-222222222222"
private const val VISIT_ID = "33333333-3333-3333-3333-333333333333"
private const val SECOND_PLACE_ID = "20000000-0000-0000-0000-000000000003"
private const val SECOND_VISIT_ID = "55555555-5555-5555-5555-555555555555"

class DefaultTravelRepositoryTest {
    @Test
    fun `remote place and Room saved visited state meet at repository boundary`() = runBlocking {
        val local = FakeLocal()
        val places = FakePlaces(page(summary()))
        val visits = FakeVisits(ownerResult = page(ownerVisit()))
        val saved = FakeSaved(listResult = page(SavedPlaceDto(summary(), "2026-08-22T10:00:00Z")))
        val repository = repository(local, places, visits, saved)

        repository.refreshCatalog()
        repository.refreshSaved()
        repository.refreshOwnerVisits()

        assertEquals(listOf(PLACE_ID), repository.observePlaces().first().map { it.id })
        assertEquals(setOf(PLACE_ID), repository.observeSavedPlaceIds().first())
        assertEquals(listOf(VISIT_ID), repository.observeVisits().first().map { it.id })
    }

    @Test
    fun `remote null score remains unrated and social scores are absent`() {
        val place = summary(score = null).toDomain()
        assertNull(place.communityScore)
        assertNull(place.friendsScore)
        assertNull(place.similarUsersScore)
    }

    @Test
    fun `save failure rolls Room state back`() = runBlocking {
        val local = FakeLocal()
        val repository = repository(
            local = local,
            saved = FakeSaved(saveResult = RemoteResult.Failure(NetworkError.Connection)),
        )

        val result = repository.toggleSaved(PLACE_ID)

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(emptySet<String>(), local.saved.value)
    }

    @Test
    fun `publish failure writes nothing and leaves input untouched`() = runBlocking {
        val local = FakeLocal()
        val remote = FakeVisits(createResult = RemoteResult.Failure(NetworkError.Timeout))
        val repository = repository(local = local, visits = remote)
        val input = visit()
        val original = input.copy()

        val result = repository.publishVisit(input)

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(original, input)
        assertEquals(emptyList<Visit>(), local.visits.value)
    }

    @Test
    fun `publish request uses canonical dimension key and review memory fields`() = runBlocking {
        val remote = FakeVisits(createResult = RemoteResult.Failure(NetworkError.Connection))
        val repository = repository(visits = remote)

        repository.publishVisit(visit())

        assertEquals(RatingDimensionDto.SEA, remote.lastCreate?.dimensions?.single()?.key)
        assertEquals("Public", remote.lastCreate?.publicReview)
        assertEquals("Private", remote.lastCreate?.privateMemory)
    }

    @Test
    fun `owner refresh deduplicates canonical server ids`() = runBlocking {
        val local = FakeLocal()
        val remoteVisit = ownerVisit()
        val repository = repository(
            local = local,
            visits = FakeVisits(ownerResult = page(remoteVisit, remoteVisit)),
        )

        repository.refreshOwnerVisits()

        assertEquals(listOf(VISIT_ID), local.visits.value.map { it.id })
    }

    @Test
    fun `catalog and owner refresh consume all pages and deduplicate ids`() = runBlocking {
        val secondPlace = summary().copy(id = SECOND_PLACE_ID, name = "Second Cove")
        val secondVisit = ownerVisit().copy(id = SECOND_VISIT_ID, place = secondPlace)
        val places = FakePlaces(
            listResult = page(),
            listResults = mapOf(
                0 to remotePage(0, true, summary()),
                1 to remotePage(1, false, summary(), secondPlace),
            ),
        )
        val visits = FakeVisits(
            ownerResults = mapOf(
                0 to remotePage(0, true, ownerVisit()),
                1 to remotePage(1, false, ownerVisit(), secondVisit),
            ),
        )
        val local = FakeLocal()
        val repository = repository(local = local, places = places, visits = visits)

        val catalog = repository.refreshCatalog()
        val ownerVisits = repository.refreshOwnerVisits()

        assertEquals(listOf(0, 1), places.requestedPages)
        assertEquals(listOf(0, 1), visits.requestedOwnerPages)
        assertEquals(listOf(PLACE_ID, SECOND_PLACE_ID), (catalog as RepositoryResult.Success).value.places.map { it.id })
        assertEquals(listOf(VISIT_ID, SECOND_VISIT_ID), (ownerVisits as RepositoryResult.Success).value.map { it.id })
        assertEquals(listOf(VISIT_ID, SECOND_VISIT_ID), local.visits.value.map { it.id })
    }

    @Test
    fun `later page failure preserves existing catalog and local visits`() = runBlocking {
        val local = FakeLocal().apply { visits.value = listOf(visit()) }
        val places = FakePlaces(
            listResult = page(),
            listResults = mapOf(
                0 to remotePage(0, true, summary()),
                1 to RemoteResult.Failure(NetworkError.Connection),
            ),
        )
        val visits = FakeVisits(
            ownerResults = mapOf(
                0 to remotePage(0, true, ownerVisit()),
                1 to RemoteResult.Failure(NetworkError.Timeout),
            ),
        )
        val repository = repository(local = local, places = places, visits = visits)

        assertTrue(repository.refreshCatalog() is RepositoryResult.Failure)
        assertEquals(emptyList<String>(), repository.observePlaces().first().map { it.id })
        assertTrue(repository.refreshOwnerVisits() is RepositoryResult.Failure)
        assertEquals(listOf("44444444-4444-4444-4444-444444444444"), local.visits.value.map { it.id })
    }

    @Test
    fun `cold offline refresh exposes cached places while reporting remote failure`() = runBlocking {
        val cache = FakePlaceCache(listOf(summary().toDomain()))
        val repository = repository(
            places = FakePlaces(RemoteResult.Failure(NetworkError.Connection)),
            placeCache = cache,
        )

        assertTrue(repository.refreshCatalog() is RepositoryResult.Failure)
        assertEquals(listOf(PLACE_ID), repository.observePlaces().first().map { it.id })
    }
}

private fun repository(
    local: FakeLocal = FakeLocal(),
    places: FakePlaces = FakePlaces(page()),
    visits: FakeVisits = FakeVisits(),
    saved: FakeSaved = FakeSaved(),
    placeCache: PlaceCacheDataSource = FakePlaceCache(),
) = DefaultTravelRepository(
    MockPlaceCatalogDataSource(),
    local,
    places,
    visits,
    saved,
    FakeCollections(),
    testSessionManager(USER_ID),
    placeCache,
)

private fun summary(score: Double? = 8.7) = PlaceSummaryDto(
    PLACE_ID, "Remote Cove", PlaceCategoryDto.BEACH, "cover", "Bodrum", "Muğla", "Türkiye",
    37.0, 27.0, 2, score, 12,
)

private fun ownerVisit() = VisitOwnerDto(
    VISIT_ID, summary(), "2026-08-22", 9.1,
    listOf(DimensionScoreDto(RatingDimensionDto.SEA, 9.4)),
    "Public", "Private", emptyList(), VisibilityDto.PUBLIC, VerificationStatusDto.UNVERIFIED,
)

private fun visit() = Visit(
    "44444444-4444-4444-4444-444444444444",
    USER_ID,
    PLACE_ID,
    LocalDate.of(2026, 8, 22),
    9.1,
    mapOf(RatingDimension.SEA to 9.4),
    "Public",
    "Private",
)

private fun <T> page(vararg values: T) = RemoteResult.Success(
    PageResponseDto(values.toList(), 0, 100, values.size.toLong(), 1, false),
)

private fun <T> remotePage(page: Int, hasNext: Boolean, vararg values: T) = RemoteResult.Success(
    PageResponseDto(values.toList(), page, 100, values.size.toLong(), if (hasNext) page + 2 else page + 1, hasNext),
)

private class FakeLocal : LocalUserStateDataSource {
    val visits = MutableStateFlow<List<Visit>>(emptyList())
    val saved = MutableStateFlow<Set<String>>(emptySet())
    val collections = MutableStateFlow<List<Collection>>(emptyList())
    override fun observeVisits(): Flow<List<Visit>> = visits
    override fun observeSavedPlaceIds(): Flow<Set<String>> = saved
    override fun observeCollections(): Flow<List<Collection>> = collections
    override suspend fun getCollection(id: String) = collections.value.firstOrNull { it.id == id }
    override suspend fun upsertVisit(visit: Visit) = upsertVisits(listOf(visit))
    override suspend fun upsertVisits(visits: List<Visit>) {
        this.visits.value = (this.visits.value + visits).associateBy { it.id }.values.toList()
    }
    override suspend fun isSaved(placeId: String) = placeId in saved.value
    override suspend fun setSaved(placeId: String, saved: Boolean) {
        this.saved.value = if (saved) this.saved.value + placeId else this.saved.value - placeId
    }
    override suspend fun replaceSavedPlaceIds(placeIds: Set<String>) { saved.value = placeIds }
    override suspend fun upsertCollection(collection: Collection) {
        collections.value = collections.value.filterNot { it.id == collection.id } + collection
    }
    override suspend fun replaceCollections(collections: List<Collection>) { this.collections.value = collections }
    override suspend fun addPlaceToCollection(collectionId: String, placeId: String) = Unit
    override suspend fun removePlaceFromCollection(collectionId: String, placeId: String) = Unit
}

private class FakePlaceCache(initial: List<Place> = emptyList()) : PlaceCacheDataSource {
    private val places = initial.associateBy { it.id }.toMutableMap()
    override suspend fun getAll(): List<Place> = places.values.toList()
    override suspend fun upsert(places: List<Place>) {
        places.forEach { this.places[it.id] = it }
    }
}

private class FakePlaces(
    var listResult: RemoteResult<PageResponseDto<PlaceSummaryDto>>,
    var listResults: Map<Int, RemoteResult<PageResponseDto<PlaceSummaryDto>>>? = null,
) : PlaceRemoteDataSource {
    val requestedPages = mutableListOf<Int>()
    override suspend fun list(category: PlaceCategoryDto?, city: String?, search: String?, minRating: Double?, sort: String, page: Int, size: Int): RemoteResult<PageResponseDto<PlaceSummaryDto>> {
        requestedPages += page
        return listResults?.get(page) ?: listResult
    }
    override suspend fun nearby(latitude: Double, longitude: Double, radiusMeters: Double, category: PlaceCategoryDto?, minRating: Double?, limit: Int): RemoteResult<List<NearbyPlaceDto>> = RemoteResult.Success(emptyList())
    override suspend fun bounds(west: Double, south: Double, east: Double, north: Double, category: PlaceCategoryDto?, minRating: Double?, limit: Int): RemoteResult<List<PlaceSummaryDto>> = RemoteResult.Success(emptyList())
    override suspend fun detail(id: String): RemoteResult<PlaceDetailDto> = RemoteResult.Failure(NetworkError.NotFound(null))
}

private class FakeVisits(
    var createResult: RemoteResult<VisitOwnerDto> = RemoteResult.Failure(NetworkError.Connection),
    var ownerResult: RemoteResult<PageResponseDto<VisitOwnerDto>> = page(),
    var ownerResults: Map<Int, RemoteResult<PageResponseDto<VisitOwnerDto>>>? = null,
) : VisitRemoteDataSource {
    var lastCreate: CreateVisitDto? = null
    val requestedOwnerPages = mutableListOf<Int>()
    override suspend fun create(request: CreateVisitDto): RemoteResult<VisitOwnerDto> {
        lastCreate = request
        return createResult
    }
    override suspend fun ownerVisits(page: Int, size: Int): RemoteResult<PageResponseDto<VisitOwnerDto>> {
        requestedOwnerPages += page
        return ownerResults?.get(page) ?: ownerResult
    }
    override suspend fun publicReviews(placeId: String, page: Int, size: Int): RemoteResult<PageResponseDto<PublicVisitDto>> = page()
}

private class FakeSaved(
    var listResult: RemoteResult<PageResponseDto<SavedPlaceDto>> = page(),
    var saveResult: RemoteResult<SavedPlaceDto> = RemoteResult.Success(SavedPlaceDto(summary(), "2026-08-22T10:00:00Z")),
    var removeResult: RemoteResult<Unit> = RemoteResult.Success(Unit),
) : SavedPlaceRemoteDataSource {
    override suspend fun list(page: Int, size: Int) = listResult
    override suspend fun save(placeId: String) = saveResult
    override suspend fun remove(placeId: String) = removeResult
}

private class FakeCollections : CollectionRemoteDataSource {
    override suspend fun list(page: Int, size: Int): RemoteResult<PageResponseDto<CollectionSummaryDto>> = page()
    override suspend fun create(request: CreateCollectionDto): RemoteResult<CollectionDetailDto> =
        RemoteResult.Failure(NetworkError.Connection)
    override suspend fun detail(collectionId: String): RemoteResult<CollectionDetailDto> =
        RemoteResult.Failure(NetworkError.NotFound(null))
    override suspend fun addPlace(collectionId: String, placeId: String): RemoteResult<CollectionDetailDto> =
        RemoteResult.Failure(NetworkError.Connection)
    override suspend fun removePlace(collectionId: String, placeId: String): RemoteResult<Unit> =
        RemoteResult.Failure(NetworkError.Connection)
}
