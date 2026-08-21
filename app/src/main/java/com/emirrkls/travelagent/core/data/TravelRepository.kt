package com.emirrkls.travelagent.core.data

import com.emirrkls.travelagent.core.model.ActivityItem
import com.emirrkls.travelagent.core.model.Collection
import com.emirrkls.travelagent.core.model.Place
import com.emirrkls.travelagent.core.model.Visit
import kotlinx.coroutines.flow.Flow

interface TravelRepository {
    fun observePlaces(): Flow<List<Place>>
    fun observeVisits(): Flow<List<Visit>>
    fun observeSavedPlaceIds(): Flow<Set<String>>
    suspend fun getPlace(id: String): Place?
    suspend fun getCollections(): List<Collection>
    suspend fun getCollection(id: String): Collection?
    suspend fun getActivity(): List<ActivityItem>
    suspend fun publishVisit(visit: Visit)
    suspend fun toggleSaved(placeId: String)
}
