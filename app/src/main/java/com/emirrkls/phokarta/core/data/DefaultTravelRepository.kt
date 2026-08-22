package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.model.ActivityItem
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.User
import com.emirrkls.phokarta.core.model.Visit
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultTravelRepository @Inject constructor(
    private val catalog: MockPlaceCatalogDataSource,
    private val localUserState: LocalUserStateDataSource,
) : TravelRepository {
    override val currentUser: User = catalog.currentUser

    override fun observePlaces(): Flow<List<Place>> = catalog.observePlaces()
    override fun observeVisits(): Flow<List<Visit>> = localUserState.observeVisits()
    override fun observeSavedPlaceIds(): Flow<Set<String>> = localUserState.observeSavedPlaceIds()
    override fun observeCollections(): Flow<List<Collection>> = localUserState.observeCollections()
    override suspend fun getPlace(id: String): Place? = catalog.getPlace(id)
    override suspend fun getCollection(id: String): Collection? = localUserState.getCollection(id)
    override suspend fun getActivity(): List<ActivityItem> = catalog.getActivity()
    override suspend fun publishVisit(visit: Visit) = localUserState.publishVisit(visit)
    override suspend fun toggleSaved(placeId: String) = localUserState.toggleSaved(placeId)
    override suspend fun saveCollection(collection: Collection) = localUserState.saveCollection(collection)
    override suspend fun addPlaceToCollection(collectionId: String, placeId: String) =
        localUserState.addPlaceToCollection(collectionId, placeId)

    override suspend fun removePlaceFromCollection(collectionId: String, placeId: String) =
        localUserState.removePlaceFromCollection(collectionId, placeId)
}
