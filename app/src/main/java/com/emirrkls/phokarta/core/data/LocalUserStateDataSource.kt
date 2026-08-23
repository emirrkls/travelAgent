package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Visit
import kotlinx.coroutines.flow.Flow

interface LocalUserStateDataSource {
    fun observeVisits(): Flow<List<Visit>>
    fun observeSavedPlaceIds(): Flow<Set<String>>
    fun observeCollections(): Flow<List<Collection>>
    suspend fun getCollection(id: String): Collection?
    suspend fun upsertVisit(visit: Visit)
    suspend fun upsertVisits(visits: List<Visit>)
    suspend fun isSaved(placeId: String): Boolean
    suspend fun setSaved(placeId: String, saved: Boolean)
    /** Replaces all saved rows for the current owner, preserving each entry's `savedAt` timestamp. */
    suspend fun replaceSavedPlaces(entries: List<Pair<String, Long>>)
    suspend fun upsertCollection(collection: Collection)
    suspend fun replaceCollections(collections: List<Collection>)
    suspend fun addPlaceToCollection(collectionId: String, placeId: String)
    suspend fun removePlaceFromCollection(collectionId: String, placeId: String)
}
