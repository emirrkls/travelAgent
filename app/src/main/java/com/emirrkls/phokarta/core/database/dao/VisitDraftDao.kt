package com.emirrkls.phokarta.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.emirrkls.phokarta.core.database.entity.VisitDraftDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.VisitDraftEntity
import com.emirrkls.phokarta.core.database.entity.VisitDraftPhotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VisitDraftDao {
    @Query("SELECT * FROM visit_drafts WHERE userId = :userId AND placeId = :placeId LIMIT 1")
    suspend fun getDraft(userId: String, placeId: String): VisitDraftEntity?

    @Query("SELECT * FROM visit_drafts WHERE userId = :userId AND placeId = :placeId LIMIT 1")
    fun observeDraft(userId: String, placeId: String): Flow<VisitDraftEntity?>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM visit_drafts
            WHERE userId = :userId AND placeId = :placeId
        )
        """,
    )
    fun observeHasDraft(userId: String, placeId: String): Flow<Boolean>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM visit_drafts
            WHERE userId = :userId AND placeId = :placeId
        )
        """,
    )
    suspend fun hasDraft(userId: String, placeId: String): Boolean

    @Query(
        """
        SELECT * FROM visit_draft_dimension_scores
        WHERE userId = :userId AND placeId = :placeId
        """,
    )
    suspend fun getDimensionScores(userId: String, placeId: String): List<VisitDraftDimensionScoreEntity>

    @Query(
        "SELECT * FROM visit_draft_photos WHERE ownerUserId = :userId AND placeId = :placeId ORDER BY position",
    )
    suspend fun getPhotos(userId: String, placeId: String): List<VisitDraftPhotoEntity>

    @Query("SELECT * FROM visit_draft_photos")
    suspend fun getAllPhotos(): List<VisitDraftPhotoEntity>

    @Query(
        "SELECT * FROM visit_draft_photos WHERE ownerUserId = :userId AND placeId = :placeId ORDER BY position",
    )
    fun observePhotos(userId: String, placeId: String): Flow<List<VisitDraftPhotoEntity>>

    @Upsert
    suspend fun upsertPhotos(photos: List<VisitDraftPhotoEntity>)

    @Query("DELETE FROM visit_draft_photos WHERE ownerUserId = :userId AND placeId = :placeId")
    suspend fun deletePhotos(userId: String, placeId: String)

    @Upsert
    suspend fun upsertDraft(draft: VisitDraftEntity)

    @Upsert
    suspend fun upsertDimensionScores(scores: List<VisitDraftDimensionScoreEntity>)

    @Query(
        """
        DELETE FROM visit_draft_dimension_scores
        WHERE userId = :userId AND placeId = :placeId
        """,
    )
    suspend fun deleteDimensionScores(userId: String, placeId: String)

    @Query("DELETE FROM visit_drafts WHERE userId = :userId AND placeId = :placeId")
    suspend fun deleteDraft(userId: String, placeId: String)

    @Query("DELETE FROM visit_drafts WHERE updatedAtEpochMillis < :cutoffEpochMillis")
    suspend fun deleteExpired(cutoffEpochMillis: Long): Int

    @Query(
        """
        SELECT p.* FROM visit_draft_photos p
        INNER JOIN visit_drafts d ON d.userId = p.ownerUserId AND d.placeId = p.placeId
        WHERE d.updatedAtEpochMillis < :cutoffEpochMillis
        """,
    )
    suspend fun getExpiredPhotos(cutoffEpochMillis: Long): List<VisitDraftPhotoEntity>

    @Transaction
    suspend fun upsertDraftWithDimensions(
        draft: VisitDraftEntity,
        scores: List<VisitDraftDimensionScoreEntity>,
    ) {
        upsertDraft(draft)
        deleteDimensionScores(draft.userId, draft.placeId)
        if (scores.isNotEmpty()) upsertDimensionScores(scores)
    }

    @Transaction
    suspend fun getDraftWithDimensions(
        userId: String,
        placeId: String,
    ): Pair<VisitDraftEntity, List<VisitDraftDimensionScoreEntity>>? {
        val draft = getDraft(userId, placeId) ?: return null
        return draft to getDimensionScores(userId, placeId)
    }

    @Transaction
    suspend fun replacePhotos(userId: String, placeId: String, photos: List<VisitDraftPhotoEntity>) {
        deletePhotos(userId, placeId)
        if (photos.isNotEmpty()) upsertPhotos(photos)
    }
}
