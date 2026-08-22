package com.emirrkls.phokarta.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.emirrkls.phokarta.core.database.entity.VisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.VisitEntity
import com.emirrkls.phokarta.core.database.relation.VisitWithDimensions
import kotlinx.coroutines.flow.Flow

@Dao
interface VisitDao {
    @Transaction
    @Query("SELECT * FROM visits ORDER BY visitedAtEpochDay DESC, createdAtEpochMillis DESC")
    fun observeVisitsWithDimensions(): Flow<List<VisitWithDimensions>>

    @Transaction
    @Query("SELECT * FROM visits WHERE placeId = :placeId ORDER BY visitedAtEpochDay DESC, createdAtEpochMillis DESC")
    suspend fun getVisitsForPlace(placeId: String): List<VisitWithDimensions>

    @Insert
    suspend fun insertVisit(visit: VisitEntity)

    @Insert
    suspend fun insertDimensionScores(scores: List<VisitDimensionScoreEntity>)

    @Delete
    suspend fun deleteVisit(visit: VisitEntity)

    @Transaction
    suspend fun insertVisitWithDimensions(
        visit: VisitEntity,
        scores: List<VisitDimensionScoreEntity>,
    ) {
        insertVisit(visit)
        if (scores.isNotEmpty()) insertDimensionScores(scores)
    }
}
