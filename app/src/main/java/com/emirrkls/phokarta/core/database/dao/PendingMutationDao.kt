package com.emirrkls.phokarta.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.emirrkls.phokarta.core.database.entity.MutationStateValue
import com.emirrkls.phokarta.core.database.entity.MutationTypeValue
import com.emirrkls.phokarta.core.database.entity.PendingMutationEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitPayloadEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitPhotoEntity
import kotlinx.coroutines.flow.Flow

data class PendingVisitMutation(
    @androidx.room.Embedded val mutation: PendingMutationEntity,
    @androidx.room.Relation(parentColumn = "mutationId", entityColumn = "mutationId")
    val payload: PendingVisitPayloadEntity,
    @androidx.room.Relation(parentColumn = "mutationId", entityColumn = "mutationId")
    val dimensions: List<PendingVisitDimensionScoreEntity>,
    @androidx.room.Relation(parentColumn = "mutationId", entityColumn = "mutationId")
    val photos: List<PendingVisitPhotoEntity>,
)

@Dao
interface PendingMutationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMutation(value: PendingMutationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVisitPayload(value: PendingVisitPayloadEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVisitDimensions(values: List<PendingVisitDimensionScoreEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVisitPhotos(values: List<PendingVisitPhotoEntity>)

    @Query("SELECT * FROM pending_visit_photos WHERE mutationId = :mutationId ORDER BY position")
    suspend fun getVisitPhotos(mutationId: String): List<PendingVisitPhotoEntity>

    @Query("SELECT * FROM pending_visit_photos")
    suspend fun getAllVisitPhotos(): List<PendingVisitPhotoEntity>

    @Query(
        """
        UPDATE pending_visit_photos
        SET remoteMediaId = :mediaId, uploadState = :state, failureCategory = NULL
        WHERE mutationId = :mutationId AND position = :position AND ownerUserId = :ownerUserId
        """,
    )
    suspend fun updatePhotoRemoteState(
        mutationId: String,
        position: Int,
        ownerUserId: String,
        mediaId: String,
        state: String,
    ): Int

    @Query(
        "UPDATE pending_visit_photos SET failureCategory = :category WHERE mutationId = :mutationId AND position = :position",
    )
    suspend fun markPhotoFailure(mutationId: String, position: Int, category: String): Int

    @Query(
        """
        UPDATE pending_visit_photos
        SET clientMediaId = :newClientMediaId, remoteMediaId = NULL,
            uploadState = 'LOCAL_ONLY', failureCategory = NULL
        WHERE mutationId = :mutationId AND position = :position AND ownerUserId = :ownerUserId
        """,
    )
    suspend fun resetAttachedPhoto(
        mutationId: String,
        position: Int,
        ownerUserId: String,
        newClientMediaId: String,
    ): Int

    @Query("SELECT * FROM pending_mutations WHERE mutationId = :mutationId")
    suspend fun get(mutationId: String): PendingMutationEntity?

    @Transaction
    @Query("SELECT * FROM pending_mutations WHERE mutationId = :mutationId AND type = 'PUBLISH_VISIT'")
    suspend fun getVisit(mutationId: String): PendingVisitMutation?

    @Transaction
    @Query("SELECT * FROM pending_mutations WHERE userId = :userId AND type = 'PUBLISH_VISIT' ORDER BY createdAtEpochMillis")
    fun observeVisitMutations(userId: String): Flow<List<PendingVisitMutation>>

    @Query("SELECT * FROM pending_mutations WHERE userId = :userId AND state IN ('PENDING','FAILED_RETRYABLE') ORDER BY createdAtEpochMillis LIMIT :limit")
    suspend fun eligible(userId: String, limit: Int): List<PendingMutationEntity>

    @Query("SELECT * FROM pending_mutations WHERE userId = :userId ORDER BY createdAtEpochMillis")
    fun observeForUser(userId: String): Flow<List<PendingMutationEntity>>

    @Query("UPDATE pending_mutations SET state = 'SYNCING', attemptCount = attemptCount + 1, updatedAtEpochMillis = :now WHERE mutationId = :mutationId AND state IN ('PENDING','FAILED_RETRYABLE')")
    suspend fun claim(mutationId: String, now: Long): Int

    @Query("UPDATE pending_mutations SET state = :state, lastErrorCategory = :category, updatedAtEpochMillis = :now WHERE mutationId = :mutationId AND generation = :generation")
    suspend fun markFailure(mutationId: String, generation: Long, state: String, category: String, now: Long): Int

    @Query("DELETE FROM pending_mutations WHERE mutationId = :mutationId AND generation = :generation")
    suspend fun deleteIfGeneration(mutationId: String, generation: Long): Int

    @Query(
        """
        DELETE FROM pending_mutations
        WHERE mutationId = :mutationId
          AND userId = :userId
          AND state = :expectedState
          AND type = 'PUBLISH_VISIT'
        """,
    )
    suspend fun deleteIfState(mutationId: String, userId: String, expectedState: String): Int

    @Query("UPDATE pending_mutations SET state = 'PENDING', lastErrorCategory = NULL, updatedAtEpochMillis = :now WHERE mutationId = :mutationId")
    suspend fun retry(mutationId: String, now: Long): Int

    @Query("UPDATE pending_mutations SET state = 'PENDING', updatedAtEpochMillis = :now WHERE state = 'SYNCING' AND updatedAtEpochMillis < :staleBefore")
    suspend fun recoverStaleSyncing(staleBefore: Long, now: Long): Int

    @Query("SELECT * FROM pending_mutations WHERE userId = :userId AND type = 'SET_SAVED_STATE' AND resourceKey = :placeId LIMIT 1")
    suspend fun savedIntent(userId: String, placeId: String): PendingMutationEntity?

    @Query("SELECT * FROM pending_mutations WHERE userId = :userId AND type = 'SET_SAVED_STATE'")
    suspend fun savedIntents(userId: String): List<PendingMutationEntity>

    @Upsert
    suspend fun upsertMutation(value: PendingMutationEntity)
}
