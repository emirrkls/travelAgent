package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Visit
import kotlinx.coroutines.flow.Flow

interface LocalUserStateDataSource {
    fun observeVisits(): Flow<List<Visit>>
    fun observeSavedPlaceIds(): Flow<Set<String>>
    fun observeCollections(): Flow<List<Collection>>
    suspend fun getCollection(id: String): Collection?
    suspend fun publishVisit(visit: Visit)
    suspend fun toggleSaved(placeId: String)
    suspend fun saveCollection(collection: Collection)
    suspend fun addPlaceToCollection(collectionId: String, placeId: String)
    suspend fun removePlaceFromCollection(collectionId: String, placeId: String)
}
