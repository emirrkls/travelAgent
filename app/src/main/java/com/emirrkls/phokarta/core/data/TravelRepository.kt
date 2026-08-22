package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.model.ActivityItem
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.NearbyPlace
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.User
import com.emirrkls.phokarta.core.model.Visit
import kotlinx.coroutines.flow.Flow

data class PlacePage(
    val places: List<Place>,
    val page: Int,
    val totalPages: Int,
    val totalElements: Long,
    val hasNext: Boolean,
)

interface TravelRepository {
    val currentUser: User
    fun observePlaces(): Flow<List<Place>>
    fun observeVisits(): Flow<List<Visit>>
    fun observeSavedPlaceIds(): Flow<Set<String>>
    fun observeCollections(): Flow<List<Collection>>
    suspend fun getPlace(id: String): Place?
    suspend fun getCollection(id: String): Collection?
    suspend fun getActivity(): List<ActivityItem>
    suspend fun listPlaces(
        category: com.emirrkls.phokarta.core.model.PlaceCategory? = null,
        city: String? = null,
        search: String? = null,
        minRating: Double? = null,
        sort: String = "averageScore,desc",
        page: Int = 0,
        size: Int = 20,
    ): RepositoryResult<PlacePage>
    suspend fun refreshCatalog(): RepositoryResult<PlacePage>
    suspend fun refreshBounds(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        category: PlaceCategory? = null,
        minRating: Double? = null,
    ): RepositoryResult<List<Place>>
    suspend fun nearby(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = 5_000.0,
        category: PlaceCategory? = null,
        minRating: Double? = null,
    ): RepositoryResult<List<NearbyPlace>>
    suspend fun refreshPlaceDetail(id: String): RepositoryResult<Place>
    suspend fun refreshOwnerVisits(page: Int = 0, size: Int = 50): RepositoryResult<List<Visit>>
    suspend fun refreshSaved(page: Int = 0, size: Int = 100): RepositoryResult<Set<String>>
    suspend fun refreshCollections(page: Int = 0, size: Int = 100): RepositoryResult<List<Collection>>
    suspend fun refreshCollectionDetail(id: String): RepositoryResult<Collection>
    suspend fun publishVisit(visit: Visit): RepositoryResult<Visit>
    suspend fun toggleSaved(placeId: String): RepositoryResult<Boolean>
    suspend fun saveCollection(collection: Collection): RepositoryResult<Collection>
    suspend fun addPlaceToCollection(collectionId: String, placeId: String): RepositoryResult<Collection>
    suspend fun removePlaceFromCollection(collectionId: String, placeId: String): RepositoryResult<Unit>
}
