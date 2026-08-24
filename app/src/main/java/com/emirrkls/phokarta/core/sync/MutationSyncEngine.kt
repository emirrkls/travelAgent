package com.emirrkls.phokarta.core.sync

import androidx.room.withTransaction
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.data.ActivityFeedInvalidator
import com.emirrkls.phokarta.core.data.LocalUserStateDataSource
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.database.dao.PendingMutationDao
import com.emirrkls.phokarta.core.database.dao.SavedPlaceDao
import com.emirrkls.phokarta.core.database.entity.MutationStateValue
import com.emirrkls.phokarta.core.database.entity.MutationTypeValue
import com.emirrkls.phokarta.core.database.entity.PendingMutationEntity
import com.emirrkls.phokarta.core.database.entity.SavedPlaceEntity
import com.emirrkls.phokarta.core.network.NetworkError
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.mapper.toDomain
import com.emirrkls.phokarta.core.network.mapper.toEpochMillisSafely
import com.emirrkls.phokarta.core.network.model.CreateVisitDto
import com.emirrkls.phokarta.core.network.model.DimensionScoreDto
import com.emirrkls.phokarta.core.network.model.RatingDimensionDto
import com.emirrkls.phokarta.core.network.model.VisibilityDto
import com.emirrkls.phokarta.core.network.source.SavedPlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.VisitRemoteDataSource
import com.emirrkls.phokarta.core.time.EpochClock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncRunResult(val retryableFailure: Boolean, val processed: Int)

@Singleton
class MutationSyncEngine @Inject constructor(
    private val database: TravelDatabase,
    private val mutations: PendingMutationDao,
    private val savedDao: SavedPlaceDao,
    private val visitsRemote: VisitRemoteDataSource,
    private val savedRemote: SavedPlaceRemoteDataSource,
    private val local: LocalUserStateDataSource,
    private val session: SessionManager,
    private val clock: EpochClock,
    private val activityInvalidator: ActivityFeedInvalidator,
) {
    private val drainMutex = Mutex()

    suspend fun drain(batchSize: Int = 20): SyncRunResult = drainMutex.withLock {
        val userId = session.currentUserId() ?: return@withLock SyncRunResult(false, 0)
        val now = clock.nowMillis()
        // Unique WorkManager chaining serializes drains, so any durable SYNCING row observed
        // at worker start belongs to an interrupted prior run and is safe to retry.
        mutations.recoverStaleSyncing(Long.MAX_VALUE, now)
        var retryable = false
        var processed = 0
        mutations.eligible(userId, batchSize).forEach { mutation ->
            if (session.currentUserId() != mutation.userId) return@forEach
            if (mutations.claim(mutation.mutationId, clock.nowMillis()) == 0) return@forEach
            val outcome = when (mutation.type) {
                MutationTypeValue.PUBLISH_VISIT -> syncVisit(mutation)
                MutationTypeValue.SET_SAVED_STATE -> syncSaved(mutation)
                else -> Failure(false, "UNKNOWN_TYPE")
            }
            processed++
            if (outcome is Failure) {
                retryable = retryable || outcome.retryable
                mutations.markFailure(
                    mutation.mutationId, mutation.generation,
                    if (outcome.retryable) MutationStateValue.FAILED_RETRYABLE else MutationStateValue.FAILED_PERMANENT,
                    outcome.category, clock.nowMillis(),
                )
            }
        }
        SyncRunResult(retryable, processed)
    }

    private suspend fun syncVisit(mutation: PendingMutationEntity): Outcome {
        val item = mutations.getVisit(mutation.mutationId) ?: return Failure(false, "MISSING_PAYLOAD")
        val payload = item.payload
        val request = CreateVisitDto(
            clientMutationId = mutation.mutationId,
            placeId = payload.placeId,
            visitedAt = LocalDate.ofEpochDay(payload.visitedAtEpochDay).toString(),
            overallRating = payload.overallRating,
            dimensions = item.dimensions.takeIf { it.isNotEmpty() }?.map {
                DimensionScoreDto(RatingDimensionDto.valueOf(it.dimensionKey), it.score)
            },
            publicReview = payload.publicReview.takeIf(String::isNotBlank),
            privateMemory = payload.privateMemory.takeIf(String::isNotBlank),
            photos = item.photos.sortedBy { it.position }.map { it.url }.takeIf { it.isNotEmpty() },
            visibility = VisibilityDto.valueOf(payload.visibility),
        )
        return when (val result = visitsRemote.create(request)) {
            is RemoteResult.Failure -> result.error.toOutcome()
            is RemoteResult.Success -> {
                val canonical = runCatching { result.value.toDomain(mutation.userId) }
                    .getOrElse { return Failure(false, "INVALID_RESPONSE") }
                database.withTransaction {
                    local.upsertVisit(canonical)
                    mutations.deleteIfGeneration(mutation.mutationId, mutation.generation)
                }
                if (canonical.visibility.name == "PUBLIC") activityInvalidator.markDirty()
                Success
            }
        }
    }

    private suspend fun syncSaved(mutation: PendingMutationEntity): Outcome {
        val desired = mutation.desiredSaved ?: return Failure(false, "MISSING_PAYLOAD")
        val result = if (desired) savedRemote.save(mutation.resourceKey) else savedRemote.remove(mutation.resourceKey)
        return when (result) {
            is RemoteResult.Failure -> result.error.toOutcome()
            is RemoteResult.Success -> {
                database.withTransaction {
                    val current = mutations.get(mutation.mutationId)
                    if (current?.generation == mutation.generation) {
                        if (desired) {
                            val dto = result.value as com.emirrkls.phokarta.core.network.model.SavedPlaceDto
                            savedDao.upsertSavedPlace(SavedPlaceEntity(
                                mutation.userId, mutation.resourceKey, dto.savedAt.toEpochMillisSafely(),
                            ))
                        } else {
                            savedDao.deleteSavedPlace(mutation.userId, mutation.resourceKey)
                        }
                        mutations.deleteIfGeneration(mutation.mutationId, mutation.generation)
                    }
                }
                Success
            }
        }
    }

    private sealed interface Outcome
    private data object Success : Outcome
    private data class Failure(val retryable: Boolean, val category: String) : Outcome

    private fun NetworkError.toOutcome(): Failure = when (this) {
        NetworkError.Connection -> Failure(true, "CONNECTION")
        NetworkError.Timeout -> Failure(true, "TIMEOUT")
        is NetworkError.Server -> Failure(true, "HTTP_$status")
        is NetworkError.Unknown -> Failure(status == 401 || status == 408 || status == 429, "HTTP_${status ?: "UNKNOWN"}")
        is NetworkError.Validation -> Failure(false, "VALIDATION")
        is NetworkError.Forbidden -> Failure(false, "FORBIDDEN")
        is NetworkError.NotFound -> Failure(false, "NOT_FOUND")
        is NetworkError.Conflict -> Failure(false, "CONFLICT")
    }

}
