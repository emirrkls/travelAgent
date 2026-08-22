package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.model.ActivityItem
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.User
import com.emirrkls.phokarta.core.model.Visit
import kotlinx.coroutines.flow.Flow

interface TravelRepository {
    val currentUser: User
    fun observePlaces(): Flow<List<Place>>
    fun observeVisits(): Flow<List<Visit>>
    fun observeSavedPlaceIds(): Flow<Set<String>>
    fun observeCollections(): Flow<List<Collection>>
    suspend fun getPlace(id: String): Place?
    suspend fun getCollection(id: String): Collection?
    suspend fun getActivity(): List<ActivityItem>
    suspend fun publishVisit(visit: Visit)
    suspend fun toggleSaved(placeId: String)
    suspend fun saveCollection(collection: Collection)
    suspend fun addPlaceToCollection(collectionId: String, placeId: String)
    suspend fun removePlaceFromCollection(collectionId: String, placeId: String)
}
