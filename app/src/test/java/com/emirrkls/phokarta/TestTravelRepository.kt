package com.emirrkls.phokarta

import com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource
import com.emirrkls.phokarta.core.data.PlacePage
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.ActivityItem
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.NearbyPlace
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.PublicReview
import com.emirrkls.phokarta.core.model.PublicReviewPage
import com.emirrkls.phokarta.core.model.User
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.model.VisitStateLogic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

open class TestTravelRepository : TravelRepository {
    val places = MutableStateFlow(MockPlaceCatalogDataSource.mockPlaces.take(4))
    val visits = MutableStateFlow<List<Visit>>(emptyList())
    val saved = MutableStateFlow<Set<String>>(emptySet())
    val collections = MutableStateFlow<List<Collection>>(emptyList())
    val publicReviewsByPlace = MutableStateFlow<Map<String, List<PublicReview>>>(emptyMap())
    var publicReviewsError: com.emirrkls.phokarta.core.data.TravelError? = null
    override val currentUser: User = MockPlaceCatalogDataSource().currentUser

    override fun observePlaces(): Flow<List<Place>> = places
    override fun observeVisits(): Flow<List<Visit>> = visits
    override fun observeVisitedPlaceIds(): Flow<Set<String>> = visits.map(VisitStateLogic::visitedPlaceIds)
    override fun observeSavedPlaceIds(): Flow<Set<String>> = saved
    override fun observeCollections(): Flow<List<Collection>> = collections
    override suspend fun getPlace(id: String) = places.value.firstOrNull { it.id == id }
    override suspend fun getCollection(id: String) = collections.value.firstOrNull { it.id == id }
    override suspend fun getActivity(): List<ActivityItem> = emptyList()
    override suspend fun listPlaces(category: PlaceCategory?, city: String?, search: String?, minRating: Double?, sort: String, page: Int, size: Int): RepositoryResult<PlacePage> =
        RepositoryResult.Success(PlacePage(places.value, page, 1, places.value.size.toLong(), false))
    override suspend fun refreshCatalog() = listPlaces(size = 100)
    override suspend fun refreshBounds(west: Double, south: Double, east: Double, north: Double, category: PlaceCategory?, minRating: Double?): RepositoryResult<List<Place>> =
        RepositoryResult.Success(places.value)
    override suspend fun nearby(latitude: Double, longitude: Double, radiusMeters: Double, category: PlaceCategory?, minRating: Double?): RepositoryResult<List<NearbyPlace>> =
        RepositoryResult.Success(emptyList())
    override suspend fun refreshPlaceDetail(id: String): RepositoryResult<Place> =
        getPlace(id)?.let { RepositoryResult.Success(it) } ?: RepositoryResult.Failure(com.emirrkls.phokarta.core.data.TravelError.NotFound())
    override suspend fun refreshPublicReviews(placeId: String, page: Int, size: Int): RepositoryResult<PublicReviewPage> {
        publicReviewsError?.let { return RepositoryResult.Failure(it) }
        val all = publicReviewsByPlace.value[placeId].orEmpty()
        val from = (page * size).coerceAtMost(all.size)
        val to = (from + size).coerceAtMost(all.size)
        val slice = all.subList(from, to)
        val totalPages = if (all.isEmpty()) 0 else ((all.size + size - 1) / size)
        return RepositoryResult.Success(
            PublicReviewPage(
                reviews = slice,
                page = page,
                totalPages = totalPages,
                totalElements = all.size.toLong(),
                hasNext = to < all.size,
            ),
        )
    }
    override suspend fun refreshOwnerVisits(page: Int, size: Int): RepositoryResult<List<Visit>> = RepositoryResult.Success(visits.value)
    override suspend fun refreshSaved(page: Int, size: Int): RepositoryResult<Set<String>> = RepositoryResult.Success(saved.value)
    override suspend fun refreshCollections(page: Int, size: Int): RepositoryResult<List<Collection>> = RepositoryResult.Success(collections.value)
    override suspend fun refreshCollectionDetail(id: String): RepositoryResult<Collection> =
        getCollection(id)?.let { RepositoryResult.Success(it) } ?: RepositoryResult.Failure(com.emirrkls.phokarta.core.data.TravelError.NotFound())
    override suspend fun publishVisit(visit: Visit): RepositoryResult<Visit> {
        visits.value = visits.value + visit
        return RepositoryResult.Success(visit)
    }
    override suspend fun toggleSaved(placeId: String): RepositoryResult<Boolean> {
        val value = placeId !in saved.value
        saved.value = if (value) saved.value + placeId else saved.value - placeId
        return RepositoryResult.Success(value)
    }
    override suspend fun saveCollection(collection: Collection): RepositoryResult<Collection> = RepositoryResult.Success(collection)
    override suspend fun addPlaceToCollection(collectionId: String, placeId: String): RepositoryResult<Collection> =
        RepositoryResult.Failure(com.emirrkls.phokarta.core.data.TravelError.NotFound())
    override suspend fun removePlaceFromCollection(collectionId: String, placeId: String): RepositoryResult<Unit> = RepositoryResult.Success(Unit)
}
