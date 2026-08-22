package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.database.dao.CollectionDao
import com.emirrkls.phokarta.core.database.dao.SavedPlaceDao
import com.emirrkls.phokarta.core.database.dao.VisitDao
import com.emirrkls.phokarta.core.database.mapper.toDimensionEntities
import com.emirrkls.phokarta.core.database.mapper.toDomain
import com.emirrkls.phokarta.core.database.mapper.toEntity
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Visit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomLocalUserStateDataSource @Inject constructor(
    private val visitDao: VisitDao,
    private val savedPlaceDao: SavedPlaceDao,
    private val collectionDao: CollectionDao,
) : LocalUserStateDataSource {
    override fun observeVisits(): Flow<List<Visit>> = visitDao.observeVisitsWithDimensions()
        .map { visits -> visits.map { it.toDomain() } }

    override fun observeSavedPlaceIds(): Flow<Set<String>> = savedPlaceDao.observeSavedPlaceIds()
        .map { it.toSet() }

    override fun observeCollections(): Flow<List<Collection>> = collectionDao.observeCollectionsWithPlaceIds()
        .map { collections -> collections.map { it.toDomain() } }

    override suspend fun getCollection(id: String): Collection? = collectionDao.getCollectionWithPlaceIds(id)?.toDomain()

    override suspend fun publishVisit(visit: Visit) {
        visitDao.insertVisitWithDimensions(
            visit = visit.toEntity(System.currentTimeMillis()),
            scores = visit.toDimensionEntities(),
        )
    }

    override suspend fun toggleSaved(placeId: String) {
        savedPlaceDao.toggle(placeId, System.currentTimeMillis())
    }

    override suspend fun saveCollection(collection: Collection) {
        val now = System.currentTimeMillis()
        val createdAt = collectionDao.getCollectionWithPlaceIds(collection.id)
            ?.collection
            ?.createdAtEpochMillis
            ?: now
        collectionDao.upsertCollectionWithPlaces(
            collection.toEntity(createdAtEpochMillis = createdAt, updatedAtEpochMillis = now),
            collection.placeIds,
        )
    }

    override suspend fun addPlaceToCollection(collectionId: String, placeId: String) {
        collectionDao.addPlaceToCollection(collectionId, placeId, System.currentTimeMillis())
    }

    override suspend fun removePlaceFromCollection(collectionId: String, placeId: String) {
        collectionDao.removePlaceFromCollection(collectionId, placeId, System.currentTimeMillis())
    }
}
