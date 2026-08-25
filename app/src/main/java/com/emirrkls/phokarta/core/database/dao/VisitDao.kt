package com.emirrkls.phokarta.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.emirrkls.phokarta.core.database.entity.VisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.VisitEntity
import com.emirrkls.phokarta.core.database.entity.VisitMediaEntity
import com.emirrkls.phokarta.core.database.relation.VisitWithDimensions
import kotlinx.coroutines.flow.Flow

@Dao
interface VisitDao {
    @Transaction
    @Query(
        """
        SELECT * FROM visits
        WHERE userId = :ownerUserId
        ORDER BY visitedAtEpochDay DESC, createdAtEpochMillis DESC
        """,
    )
    fun observeVisitsWithDimensions(ownerUserId: String): Flow<List<VisitWithDimensions>>

    @Transaction
    @Query(
        """
        SELECT * FROM visits
        WHERE userId = :ownerUserId AND placeId = :placeId
        ORDER BY visitedAtEpochDay DESC, createdAtEpochMillis DESC
        """,
    )
    suspend fun getVisitsForPlace(ownerUserId: String, placeId: String): List<VisitWithDimensions>

    @Upsert
    suspend fun upsertVisit(visit: VisitEntity)

    @Upsert
    suspend fun upsertDimensionScores(scores: List<VisitDimensionScoreEntity>)

    @Upsert
    suspend fun upsertMedia(media: List<VisitMediaEntity>)

    @Query("DELETE FROM visit_media WHERE ownerUserId = :ownerUserId AND visitId = :visitId")
    suspend fun deleteMedia(ownerUserId: String, visitId: String)

    @Query(
        """
        SELECT * FROM visit_media
        WHERE ownerUserId = :ownerUserId AND visitId = :visitId AND mediaId = :mediaId
        LIMIT 1
        """,
    )
    suspend fun getMedia(ownerUserId: String, visitId: String, mediaId: String): VisitMediaEntity?

    @Query(
        """
        UPDATE visit_media
        SET accessUrl = :accessUrl, accessUrlExpiresAtEpochMillis = :expiresAt
        WHERE ownerUserId = :ownerUserId AND visitId = :visitId AND mediaId = :mediaId
        """,
    )
    suspend fun updateMediaAccess(
        ownerUserId: String,
        visitId: String,
        mediaId: String,
        accessUrl: String,
        expiresAt: Long,
    ): Int

    @Query("DELETE FROM visit_dimension_scores WHERE visitId = :visitId")
    suspend fun deleteDimensionScores(visitId: String)

    @Delete
    suspend fun deleteVisit(visit: VisitEntity)

    @Transaction
    suspend fun upsertVisitWithDimensions(
        visit: VisitEntity,
        scores: List<VisitDimensionScoreEntity>,
        media: List<VisitMediaEntity> = emptyList(),
    ) {
        upsertVisit(visit)
        deleteDimensionScores(visit.id)
        if (scores.isNotEmpty()) upsertDimensionScores(scores)
        deleteMedia(visit.userId, visit.id)
        if (media.isNotEmpty()) upsertMedia(media)
    }

    @Transaction
    suspend fun upsertVisitsWithDimensions(
        visits: List<VisitEntity>,
        scores: List<VisitDimensionScoreEntity>,
        media: List<VisitMediaEntity> = emptyList(),
    ) {
        visits.forEach { visit ->
            upsertVisit(visit)
            deleteDimensionScores(visit.id)
        }
        if (scores.isNotEmpty()) upsertDimensionScores(scores)
        visits.forEach { deleteMedia(it.userId, it.id) }
        if (media.isNotEmpty()) upsertMedia(media)
    }
}
