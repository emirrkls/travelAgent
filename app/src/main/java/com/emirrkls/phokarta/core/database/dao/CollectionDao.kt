package com.emirrkls.phokarta.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.emirrkls.phokarta.core.database.entity.CollectionEntity
import com.emirrkls.phokarta.core.database.entity.CollectionPlaceCrossRef
import com.emirrkls.phokarta.core.database.relation.CollectionWithPlaceIds
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Transaction
    @Query("SELECT * FROM collections ORDER BY updatedAtEpochMillis DESC, title ASC")
    fun observeCollectionsWithPlaceIds(): Flow<List<CollectionWithPlaceIds>>

    @Transaction
    @Query("SELECT * FROM collections WHERE id = :collectionId")
    suspend fun getCollectionWithPlaceIds(collectionId: String): CollectionWithPlaceIds?

    @Upsert
    suspend fun upsertCollection(collection: CollectionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollectionPlace(crossRef: CollectionPlaceCrossRef): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollectionPlaces(crossRefs: List<CollectionPlaceCrossRef>)

    @Query("DELETE FROM collection_places WHERE collectionId = :collectionId AND placeId = :placeId")
    suspend fun deleteCollectionPlace(collectionId: String, placeId: String): Int

    @Query("DELETE FROM collection_places WHERE collectionId = :collectionId")
    suspend fun deleteCollectionPlaces(collectionId: String)

    @Query("DELETE FROM collections")
    suspend fun deleteAllCollections()

    @Query("SELECT COUNT(*) FROM collection_places WHERE collectionId = :collectionId")
    suspend fun countCollectionPlaces(collectionId: String): Int

    @Query("UPDATE collections SET updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = :collectionId")
    suspend fun touchCollection(collectionId: String, updatedAtEpochMillis: Long)

    @Transaction
    suspend fun addPlaceToCollection(collectionId: String, placeId: String, nowEpochMillis: Long) {
        if (insertCollectionPlace(CollectionPlaceCrossRef(collectionId, placeId)) != -1L) {
            touchCollection(collectionId, nowEpochMillis)
        }
    }

    @Transaction
    suspend fun removePlaceFromCollection(collectionId: String, placeId: String, nowEpochMillis: Long) {
        if (deleteCollectionPlace(collectionId, placeId) > 0) {
            touchCollection(collectionId, nowEpochMillis)
        }
    }

    @Transaction
    suspend fun upsertCollectionWithPlaces(
        collection: CollectionEntity,
        placeIds: List<String>,
    ) {
        upsertCollection(collection)
        deleteCollectionPlaces(collection.id)
        insertCollectionPlaces(placeIds.distinct().map { CollectionPlaceCrossRef(collection.id, it) })
    }

    @Transaction
    suspend fun replaceCollectionsWithPlaces(
        collections: List<CollectionEntity>,
        memberships: Map<String, List<String>>,
    ) {
        deleteAllCollections()
        collections.forEach { collection ->
            upsertCollection(collection)
            insertCollectionPlaces(
                memberships[collection.id].orEmpty().distinct()
                    .map { CollectionPlaceCrossRef(collection.id, it) },
            )
        }
    }
}
