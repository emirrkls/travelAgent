package com.emirrkls.phokarta.core.sync

import androidx.room.withTransaction
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.data.ActivityFeedInvalidator
import com.emirrkls.phokarta.core.data.LocalUserStateDataSource
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.database.dao.PendingMutationDao
import com.emirrkls.phokarta.core.database.dao.SavedPlaceDao
import com.emirrkls.phokarta.core.database.dao.ExperienceMilestoneDao
import com.emirrkls.phokarta.core.database.entity.MutationStateValue
import com.emirrkls.phokarta.core.database.entity.MutationTypeValue
import com.emirrkls.phokarta.core.database.entity.PendingMutationEntity
import com.emirrkls.phokarta.core.database.entity.SavedPlaceEntity
import com.emirrkls.phokarta.core.database.entity.PlannedExperienceEntity
import com.emirrkls.phokarta.core.database.entity.ExperienceAcknowledgementEntity
import com.emirrkls.phokarta.core.network.NetworkError
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.data.POLICY_ACCEPTANCE_REQUIRED_CODE
import com.emirrkls.phokarta.core.network.mapper.toDomain
import com.emirrkls.phokarta.core.network.mapper.toEpochMillisSafely
import com.emirrkls.phokarta.core.network.model.CreateVisitDto
import com.emirrkls.phokarta.core.network.model.CreateExperienceV2Dto
import com.emirrkls.phokarta.core.network.model.CreateExperienceV2DimensionDto
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
    private val experienceMilestones: ExperienceMilestoneDao,
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
        var pauseVisitPublishes = false
        mutations.eligible(userId, batchSize).forEach { mutation ->
            if (session.currentUserId() != mutation.userId) return@forEach
            if (pauseVisitPublishes && mutation.type in PUBLISH_TYPES) return@forEach
            if (mutations.claim(mutation.mutationId, clock.nowMillis()) == 0) return@forEach
            val outcome = when (mutation.type) {
                MutationTypeValue.PUBLISH_VISIT -> syncVisit(mutation)
                MutationTypeValue.PUBLISH_EXPERIENCE_V2 -> syncExperienceV2(mutation)
                MutationTypeValue.SET_SAVED_STATE -> syncSaved(mutation)
                MutationTypeValue.SET_PLANNED_EXPERIENCE_STATE -> syncPlannedExperience(mutation)
                MutationTypeValue.ACKNOWLEDGE_EXPERIENCE -> syncAcknowledgement(mutation)
                else -> Failure(false, "UNKNOWN_TYPE")
            }
            processed++
            if (outcome is Failure) {
                retryable = retryable || outcome.workManagerRetry
                mutations.markFailure(
                    mutation.mutationId, mutation.generation,
                    if (outcome.retryable) MutationStateValue.FAILED_RETRYABLE else MutationStateValue.FAILED_PERMANENT,
                    outcome.category, clock.nowMillis(),
                )
                if (outcome.category == POLICY_ACCEPTANCE_REQUIRED_CODE &&
                    mutation.type in PUBLISH_TYPES
                ) {
                    pauseVisitPublishes = true
                }
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

    private suspend fun syncExperienceV2(mutation: PendingMutationEntity): Outcome {
        if (mutation.payloadVersion != 2) return Failure(false, "INVALID_PAYLOAD_VERSION")
        val item = mutations.getExperienceV2(mutation.mutationId)
            ?: return Failure(false, "MISSING_PAYLOAD")
        val orderedPhotos = item.photos.sortedBy { it.position }
        if (orderedPhotos.size > 6) return Failure(false, "V2_MEDIA_LIMIT")
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
        val payload = item.payload
        val request = CreateExperienceV2Dto(
            clientMutationId = mutation.mutationId,
            placeId = payload.placeId,
            visitDate = LocalDate.ofEpochDay(payload.visitedAtEpochDay).toString(),
            primaryExperienceCode = payload.primaryExperienceCode,
            rawExperienceLabel = payload.rawExperienceLabel,
            overallFeelingCode = payload.overallFeelingCode,
            companionCode = payload.companionCode,
            timeOfDayCode = payload.timeOfDayCode,
            vibeCodes = payload.vibeCodes.stableCodes(),
            practicalSignalCodes = payload.practicalSignalCodes.stableCodes(),
            dimensions = item.dimensions.sortedBy { it.dimensionKey }.map {
                CreateExperienceV2DimensionDto(
                    key = it.dimensionKey,
                    semanticStateCode = it.semanticStateCode,
                    templateVersion = it.templateVersion,
                )
            },
            title = payload.title,
            titleSource = payload.titleSource,
            story = payload.story.takeIf(String::isNotBlank),
            tip = payload.tip.takeIf(String::isNotBlank),
            privateMemory = payload.privateMemory.takeIf(String::isNotBlank),
            visibility = payload.visibility,
            mediaIds = mediaIds,
            originAcknowledgementId = payload.originAcknowledgementId,
        )
        return when (val result = visitsRemote.createExperience(request)) {
            is RemoteResult.Failure -> result.error.toOutcome()
            is RemoteResult.Success -> {
                val experience = runCatching { result.value.toDomain() }
                    .getOrElse { return Failure(false, "INVALID_RESPONSE") }
                val canonical = com.emirrkls.phokarta.core.model.Visit(
                    id = experience.id,
                    userId = mutation.userId,
                    placeId = experience.place.id,
                    visitedAt = experience.experiencedAt,
                    overallRating = experience.feeling.compatibilityNumericRating,
                    ratingDimensions = experience.dimensions.mapNotNull { dimension ->
                        com.emirrkls.phokarta.core.model.RatingDimension.fromStoredKey(dimension.key)
                            ?.let { it to dimension.numericScore }
                    }.toMap(),
                    review = experience.story,
                    personalNote = payload.privateMemory,
                    photos = emptyList(),
                    visibility = com.emirrkls.phokarta.core.model.Visibility.valueOf(experience.visibility.name),
                    media = experience.media.mapNotNull { media ->
                        media.id?.let {
                            VisitMedia(
                                it,
                                media.position,
                                media.url,
                                media.accessExpiresAt?.toEpochMillisSafely(),
                            )
                        }
                    },
                )
                try {
                    database.withTransaction {
                        local.upsertVisit(canonical)
                        payload.originAcknowledgementId?.let { acknowledgementId ->
                            check(experienceMilestones.markConverted(
                                mutation.userId, acknowledgementId, experience.id,
                            ) == 1) { "Acknowledgement conversion anchor missing" }
                        }
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

    private suspend fun syncPlannedExperience(mutation: PendingMutationEntity): Outcome {
        val desired = mutation.desiredSaved ?: return Failure(false, "MISSING_PAYLOAD")
        val result = if (desired) visitsRemote.planExperience(mutation.resourceKey)
            else visitsRemote.unplanExperience(mutation.resourceKey)
        return when (result) {
            is RemoteResult.Failure -> if (result.error is NetworkError.NotFound) {
                database.withTransaction {
                    if (mutations.get(mutation.mutationId)?.generation == mutation.generation) {
                        experienceMilestones.deletePlan(mutation.userId, mutation.resourceKey)
                        mutations.deleteIfGeneration(mutation.mutationId, mutation.generation)
                    }
                }
                Success
            } else result.error.toOutcome()
            is RemoteResult.Success -> {
                database.withTransaction {
                    if (mutations.get(mutation.mutationId)?.generation == mutation.generation) {
                        if (desired) {
                            val dto = result.value as com.emirrkls.phokarta.core.network.model.PlannedExperienceV2Dto
                            val experience = dto.experience
                            experienceMilestones.upsertPlan(PlannedExperienceEntity(
                                ownerUserId = mutation.userId, experienceId = experience.id,
                                title = experience.title, placeId = experience.place.id,
                                placeName = experience.place.name,
                                primaryExperienceCode = experience.primaryExperience.code,
                                feelingCode = experience.feeling.code,
                                authorName = experience.author.displayName,
                                imageUrl = experience.media.minByOrNull { it.position }?.url,
                                plannedAtEpochMillis = dto.plannedAt.toEpochMillisSafely(),
                            ))
                        } else experienceMilestones.deletePlan(mutation.userId, mutation.resourceKey)
                        mutations.deleteIfGeneration(mutation.mutationId, mutation.generation)
                    }
                }
                Success
            }
        }
    }

    private suspend fun syncAcknowledgement(mutation: PendingMutationEntity): Outcome {
        val anchor = experienceMilestones.acknowledgementForSource(
            mutation.userId, mutation.resourceKey,
        ) ?: return Failure(false, "MISSING_ACKNOWLEDGEMENT_ANCHOR")
        return when (val result = visitsRemote.acknowledgeExperience(
            id = mutation.resourceKey,
            clientAcknowledgementId = mutation.mutationId,
            anchorPlaceId = anchor.placeId,
            anchorPrimaryExperienceCode = anchor.primaryExperienceCode,
            anchorRawExperienceLabel = anchor.rawExperienceLabel,
        )) {
            is RemoteResult.Failure -> result.error.toOutcome()
            is RemoteResult.Success -> {
                val dto = result.value
                database.withTransaction {
                    if (mutations.get(mutation.mutationId)?.generation == mutation.generation) {
                        experienceMilestones.deleteAcknowledgement(mutation.userId, "pending:${mutation.resourceKey}")
                        experienceMilestones.deleteAcknowledgement(mutation.userId, mutation.mutationId)
                        experienceMilestones.upsertAcknowledgement(ExperienceAcknowledgementEntity(
                            ownerUserId = mutation.userId, id = dto.id,
                            sourceExperienceId = dto.sourceExperienceId,
                            sourceAvailable = dto.sourceAvailable, placeId = dto.place.id,
                            placeName = dto.place.name, placeCity = dto.place.city,
                            placeRegion = dto.place.region, placeCountry = dto.place.country,
                            primaryExperienceCode = dto.primaryExperienceCode,
                            rawExperienceLabel = dto.rawExperienceLabel,
                            acknowledgedAtEpochMillis = dto.acknowledgedAt.toEpochMillisSafely(),
                            convertedExperienceId = dto.convertedExperienceId,
                        ))
                        mutations.deleteIfGeneration(mutation.mutationId, mutation.generation)
                    }
                }
                Success
            }
        }
    }

    private sealed interface Outcome
    private data object Success : Outcome
    private data class Failure(
        val retryable: Boolean,
        val category: String,
        val workManagerRetry: Boolean = retryable,
    ) : Outcome
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
        is NetworkError.Forbidden -> if (apiError?.code == POLICY_ACCEPTANCE_REQUIRED_CODE) {
            Failure(
                retryable = true,
                category = POLICY_ACCEPTANCE_REQUIRED_CODE,
                workManagerRetry = false,
            )
        } else {
            Failure(false, "FORBIDDEN")
        }
        is NetworkError.NotFound -> Failure(false, "NOT_FOUND")
        is NetworkError.Conflict -> Failure(false, "CONFLICT")
    }

    private fun String.stableCodes(): List<String> = split(',').filter(String::isNotBlank)

    companion object {
        private val PUBLISH_TYPES = setOf(
            MutationTypeValue.PUBLISH_VISIT,
            MutationTypeValue.PUBLISH_EXPERIENCE_V2,
        )
    }

}
