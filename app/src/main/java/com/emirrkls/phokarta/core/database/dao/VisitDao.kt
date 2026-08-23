package com.emirrkls.phokarta.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.emirrkls.phokarta.core.database.entity.VisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.VisitEntity
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

    @Query("DELETE FROM visit_dimension_scores WHERE visitId = :visitId")
    suspend fun deleteDimensionScores(visitId: String)

    @Delete
    suspend fun deleteVisit(visit: VisitEntity)

    @Transaction
    suspend fun upsertVisitWithDimensions(
        visit: VisitEntity,
        scores: List<VisitDimensionScoreEntity>,
    ) {
        upsertVisit(visit)
        deleteDimensionScores(visit.id)
        if (scores.isNotEmpty()) upsertDimensionScores(scores)
    }

    @Transaction
    suspend fun upsertVisitsWithDimensions(
        visits: List<VisitEntity>,
        scores: List<VisitDimensionScoreEntity>,
    ) {
        visits.forEach { visit ->
            upsertVisit(visit)
            deleteDimensionScores(visit.id)
        }
        if (scores.isNotEmpty()) upsertDimensionScores(scores)
    }
}
