package com.emirrkls.phokarta.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.emirrkls.phokarta.core.database.entity.ExperienceAcknowledgementEntity
import com.emirrkls.phokarta.core.database.entity.PlannedExperienceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExperienceMilestoneDao {
    @Query("SELECT * FROM planned_experiences WHERE ownerUserId = :owner ORDER BY plannedAtEpochMillis DESC")
    fun observePlans(owner: String): Flow<List<PlannedExperienceEntity>>
    @Query("SELECT * FROM planned_experiences WHERE ownerUserId = :owner AND experienceId = :id")
    suspend fun plan(owner: String, id: String): PlannedExperienceEntity?
    @Upsert suspend fun upsertPlan(value: PlannedExperienceEntity)
    @Upsert suspend fun upsertPlans(values: List<PlannedExperienceEntity>)
    @Query("DELETE FROM planned_experiences WHERE ownerUserId = :owner AND experienceId = :id")
    suspend fun deletePlan(owner: String, id: String)
    @Query("DELETE FROM planned_experiences WHERE ownerUserId = :owner")
    suspend fun deletePlans(owner: String)

    @Query("SELECT * FROM experience_acknowledgements WHERE ownerUserId = :owner AND convertedExperienceId IS NULL ORDER BY acknowledgedAtEpochMillis DESC")
    fun observeUnconvertedAcknowledgements(owner: String): Flow<List<ExperienceAcknowledgementEntity>>
    @Query("SELECT * FROM experience_acknowledgements WHERE ownerUserId = :owner AND sourceExperienceId = :source LIMIT 1")
    suspend fun acknowledgementForSource(owner: String, source: String): ExperienceAcknowledgementEntity?
    @Upsert suspend fun upsertAcknowledgement(value: ExperienceAcknowledgementEntity)
    @Upsert suspend fun upsertAcknowledgements(values: List<ExperienceAcknowledgementEntity>)
    @Query("DELETE FROM experience_acknowledgements WHERE ownerUserId = :owner AND id = :id")
    suspend fun deleteAcknowledgement(owner: String, id: String)
    @Query("DELETE FROM experience_acknowledgements WHERE ownerUserId = :owner")
    suspend fun deleteAcknowledgements(owner: String)
    @Query("UPDATE experience_acknowledgements SET convertedExperienceId = :experienceId WHERE ownerUserId = :owner AND id = :acknowledgementId")
    suspend fun markConverted(owner: String, acknowledgementId: String, experienceId: String): Int
}
