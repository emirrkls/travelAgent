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
import com.emirrkls.phokarta.core.network.source.MediaRemoteDataSource
import com.emirrkls.phokarta.core.network.source.DirectMediaUploader
import com.emirrkls.phokarta.core.network.source.DirectUploadResult
import com.emirrkls.phokarta.core.network.model.MediaUploadIntentRequestDto
import com.emirrkls.phokarta.core.database.entity.MediaUploadState
import com.emirrkls.phokarta.core.database.entity.MediaFailureCategory
import com.emirrkls.phokarta.core.media.VisitMediaStore
import com.emirrkls.phokarta.core.model.VisitMedia
import com.emirrkls.phokarta.core.time.EpochClock
import java.time.LocalDate
import java.util.UUID
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
    private val mediaRemote: MediaRemoteDataSource,
    private val mediaUploader: DirectMediaUploader,
    private val mediaStore: VisitMediaStore,
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
        val orderedPhotos = item.photos.sortedBy { it.position }
        if (orderedPhotos.any { it.legacyUrl != null }) {
            return Failure(false, MediaFailureCategory.LEGACY_MEDIA_RESELECT_REQUIRED)
        }
        val mediaIds = mutableListOf<String>()
        for (photo in orderedPhotos) {
            when (val prepared = preparePhoto(mutation, photo)) {
                is PhotoPrepared -> mediaIds += prepared.mediaId
                is PhotoFailed -> return prepared.failure
            }
        }
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
            photos = null,
            mediaIds = mediaIds.takeIf { it.isNotEmpty() },
            visibility = VisibilityDto.valueOf(payload.visibility),
        )
        return when (val result = visitsRemote.create(request)) {
            is RemoteResult.Failure -> result.error.toOutcome()
            is RemoteResult.Success -> {
                var canonical = runCatching { result.value.toDomain(mutation.userId) }
                    .getOrElse { return Failure(false, "INVALID_RESPONSE") }
                if (canonical.media.isEmpty() && mediaIds.isNotEmpty()) {
                    canonical = canonical.copy(media = mediaIds.mapIndexed { index, id ->
                        VisitMedia(id, index)
                    })
                }
                try {
                    database.withTransaction {
                        local.upsertVisit(canonical)
                        check(mutations.deleteIfGeneration(mutation.mutationId, mutation.generation) == 1) {
                            "Mutation generation changed before reconciliation"
                        }
                    }
                } catch (_: IllegalStateException) {
                    return Failure(true, "RECONCILIATION_RACE")
                }
                orderedPhotos.forEach {
                    mediaStore.deleteOwned(mutation.userId, it.localRelativePath)
                }
                if (canonical.visibility.name == "PUBLIC") activityInvalidator.markDirty()
                Success
            }
        }
    }

    private suspend fun preparePhoto(
        mutation: PendingMutationEntity,
        photo: com.emirrkls.phokarta.core.database.entity.PendingVisitPhotoEntity,
    ): PhotoOutcome {
        if (photo.ownerUserId != mutation.userId || session.currentUserId() != mutation.userId) {
            mutations.markPhotoFailure(mutation.mutationId, photo.position, MediaFailureCategory.OWNERSHIP)
            return PhotoFailed(Failure(false, MediaFailureCategory.OWNERSHIP))
        }
        if (photo.uploadState == MediaUploadState.READY_REMOTE && photo.remoteMediaId != null) {
            return PhotoPrepared(photo.remoteMediaId)
        }
        val path = photo.localRelativePath
            ?: return photoFailure(mutation, photo.position, MediaFailureCategory.MISSING_FILE)
        val contentType = photo.contentType
            ?: return photoFailure(mutation, photo.position, MediaFailureCategory.INVALID_STATE)
        val byteSize = photo.byteSize
            ?: return photoFailure(mutation, photo.position, MediaFailureCategory.INVALID_STATE)
        if (contentType !in VisitMediaStore.SUPPORTED_TYPES) {
            return photoFailure(mutation, photo.position, MediaFailureCategory.UNSUPPORTED_TYPE)
        }
        if (byteSize !in 1..VisitMediaStore.MAX_BYTES) {
            return photoFailure(mutation, photo.position, MediaFailureCategory.TOO_LARGE)
        }
        val file = mediaStore.resolveOwned(mutation.userId, path)
        if (file == null || !file.isFile || file.length() != byteSize) {
            return photoFailure(mutation, photo.position, MediaFailureCategory.MISSING_FILE)
        }

        val intent = when (val result = mediaRemote.createIntent(
            MediaUploadIntentRequestDto(
                photo.clientMediaId, contentType, byteSize, photo.width, photo.height,
            ),
        )) {
            is RemoteResult.Failure -> return PhotoFailed(result.error.toOutcome())
            is RemoteResult.Success -> result.value
        }
        if (intent.status == "ATTACHED") {
            mutations.resetAttachedPhoto(
                mutation.mutationId, photo.position, mutation.userId, UUID.randomUUID().toString(),
            )
            return PhotoFailed(Failure(true, "MEDIA_ATTACHED_RESET"))
        }
        mutations.updatePhotoRemoteState(
            mutation.mutationId, photo.position, mutation.userId,
            intent.mediaId, if (intent.status == "READY") MediaUploadState.READY_REMOTE else MediaUploadState.INTENT_CREATED,
        )
        if (intent.status == "READY") return PhotoPrepared(intent.mediaId)
        val uploadUrl = intent.uploadUrl
            ?: return PhotoFailed(Failure(true, "UPLOAD_URL_MISSING"))
        when (val upload = mediaUploader.put(
            uploadUrl, intent.requiredHeaders, file, contentType, byteSize,
        )) {
            DirectUploadResult.Success -> Unit
            is DirectUploadResult.Retryable -> return PhotoFailed(Failure(true, upload.category))
            is DirectUploadResult.Permanent ->
                return photoFailure(mutation, photo.position, upload.category)
        }
        return when (val confirmed = mediaRemote.confirm(intent.mediaId)) {
            is RemoteResult.Failure -> PhotoFailed(confirmed.error.toOutcome())
            is RemoteResult.Success -> when (confirmed.value.status) {
                "READY" -> {
                    mutations.updatePhotoRemoteState(
                        mutation.mutationId, photo.position, mutation.userId,
                        intent.mediaId, MediaUploadState.READY_REMOTE,
                    )
                    PhotoPrepared(intent.mediaId)
                }
                "ATTACHED" -> {
                    mutations.resetAttachedPhoto(
                        mutation.mutationId, photo.position, mutation.userId, UUID.randomUUID().toString(),
                    )
                    PhotoFailed(Failure(true, "MEDIA_ATTACHED_RESET"))
                }
                else -> PhotoFailed(Failure(true, "MEDIA_CONFIRM_PENDING"))
            }
        }
    }

    private suspend fun photoFailure(
        mutation: PendingMutationEntity,
        position: Int,
        category: String,
    ): PhotoFailed {
        mutations.markPhotoFailure(mutation.mutationId, position, category)
        return PhotoFailed(Failure(false, category))
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
    private sealed interface PhotoOutcome
    private data class PhotoPrepared(val mediaId: String) : PhotoOutcome
    private data class PhotoFailed(val failure: Failure) : PhotoOutcome

    private fun NetworkError.toOutcome(): Failure = when (this) {
        NetworkError.Connection -> Failure(true, "CONNECTION")
        NetworkError.Timeout -> Failure(true, "TIMEOUT")
        is NetworkError.Server -> Failure(true, "HTTP_$status")
        is NetworkError.Unknown -> Failure(status == 408 || status == 429, "HTTP_${status ?: "UNKNOWN"}")
        is NetworkError.Unauthorized -> Failure(false, "UNAUTHORIZED")
        is NetworkError.Validation -> Failure(false, "VALIDATION")
        is NetworkError.Forbidden -> Failure(false, "FORBIDDEN")
        is NetworkError.NotFound -> Failure(false, "NOT_FOUND")
        is NetworkError.Conflict -> Failure(false, "CONFLICT")
    }

}
