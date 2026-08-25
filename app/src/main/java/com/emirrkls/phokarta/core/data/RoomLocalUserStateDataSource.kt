package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.auth.AuthState
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.database.dao.CollectionDao
import com.emirrkls.phokarta.core.database.dao.SavedPlaceDao
import com.emirrkls.phokarta.core.database.dao.VisitDao
import com.emirrkls.phokarta.core.database.mapper.toDimensionEntities
import com.emirrkls.phokarta.core.database.mapper.toDomain
import com.emirrkls.phokarta.core.database.mapper.toEntity
import com.emirrkls.phokarta.core.database.mapper.toMediaEntities
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Visit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class RoomLocalUserStateDataSource @Inject constructor(
    private val visitDao: VisitDao,
    private val savedPlaceDao: SavedPlaceDao,
    private val collectionDao: CollectionDao,
    private val sessionManager: SessionManager,
) : LocalUserStateDataSource {
    private fun ownerIdOrNull(): String? = sessionManager.currentUserId()

    override fun observeVisits(): Flow<List<Visit>> =
        sessionManager.state.flatMapLatest { state ->
            val ownerId = (state as? AuthState.Authenticated)?.user?.id
            if (ownerId == null) {
                flowOf(emptyList())
            } else {
                visitDao.observeVisitsWithDimensions(ownerId).map { visits ->
                    visits.map { it.toDomain() }
                }
            }
        }

    override fun observeSavedPlaceIds(): Flow<Set<String>> =
        sessionManager.state.flatMapLatest { state ->
            val ownerId = (state as? AuthState.Authenticated)?.user?.id
            if (ownerId == null) {
                flowOf(emptySet())
            } else {
                // LinkedHashSet keeps Room's savedAt DESC order for Want to Go shelves.
                savedPlaceDao.observeSavedPlaceIds(ownerId).map { it.toCollection(LinkedHashSet()) }
            }
        }

    override fun observeCollections(): Flow<List<Collection>> =
        sessionManager.state.flatMapLatest { state ->
            val ownerId = (state as? AuthState.Authenticated)?.user?.id
            if (ownerId == null) {
                flowOf(emptyList())
            } else {
                collectionDao.observeCollectionsWithPlaceIds(ownerId).map { collections ->
                    collections.map { it.toDomain() }
                }
            }
        }

    override suspend fun getCollection(id: String): Collection? {
        val ownerId = ownerIdOrNull() ?: return null
        return collectionDao.getCollectionWithPlaceIds(ownerId, id)?.toDomain()
    }

    override suspend fun upsertVisit(visit: Visit) {
        visitDao.upsertVisitWithDimensions(
            visit = visit.toEntity(System.currentTimeMillis()),
            scores = visit.toDimensionEntities(),
            media = visit.toMediaEntities(),
        )
    }

    override suspend fun upsertVisits(visits: List<Visit>) {
        val now = System.currentTimeMillis()
        visitDao.upsertVisitsWithDimensions(
            visits = visits.map { it.toEntity(now) },
            scores = visits.flatMap { it.toDimensionEntities() },
            media = visits.flatMap { it.toMediaEntities() },
        )
    }

    override suspend fun isSaved(placeId: String): Boolean {
        val ownerId = ownerIdOrNull() ?: return false
        return savedPlaceDao.getSavedPlace(ownerId, placeId) != null
    }

    override suspend fun setSaved(placeId: String, saved: Boolean) {
        val ownerId = ownerIdOrNull() ?: return
        savedPlaceDao.setSaved(ownerId, placeId, saved, System.currentTimeMillis())
    }

    override suspend fun replaceSavedPlaces(entries: List<Pair<String, Long>>) {
        val ownerId = ownerIdOrNull() ?: return
        savedPlaceDao.replaceSavedPlaces(
            ownerUserId = ownerId,
            entries = entries.map { (placeId, savedAt) ->
                com.emirrkls.phokarta.core.database.entity.SavedPlaceEntity(ownerId, placeId, savedAt)
            },
        )
    }

    override suspend fun upsertCollection(collection: Collection) {
        val ownerId = ownerIdOrNull() ?: return
        val now = System.currentTimeMillis()
        val createdAt = collectionDao.getCollectionWithPlaceIds(ownerId, collection.id)
            ?.collection
            ?.createdAtEpochMillis
            ?: now
        collectionDao.upsertCollectionWithPlaces(
            collection.toEntity(createdAtEpochMillis = createdAt, updatedAtEpochMillis = now),
            collection.placeIds,
        )
    }

    override suspend fun replaceCollections(collections: List<Collection>) {
        val ownerId = ownerIdOrNull() ?: return
        val now = System.currentTimeMillis()
        collectionDao.replaceCollectionsWithPlaces(
            ownerUserId = ownerId,
            collections = collections.map { it.toEntity(now, now) },
            memberships = collections.associate { it.id to it.placeIds },
        )
    }

    override suspend fun addPlaceToCollection(collectionId: String, placeId: String) {
        collectionDao.addPlaceToCollection(collectionId, placeId, System.currentTimeMillis())
    }

    override suspend fun removePlaceFromCollection(collectionId: String, placeId: String) {
        collectionDao.removePlaceFromCollection(collectionId, placeId, System.currentTimeMillis())
    }
}
