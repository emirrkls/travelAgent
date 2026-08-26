package com.emirrkls.phokarta.core.sync

import androidx.room.withTransaction
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.database.dao.PendingMutationDao
import com.emirrkls.phokarta.core.database.dao.SavedPlaceDao
import com.emirrkls.phokarta.core.database.dao.VisitDraftDao
import com.emirrkls.phokarta.core.database.entity.MutationStateValue
import com.emirrkls.phokarta.core.database.entity.MutationTypeValue
import com.emirrkls.phokarta.core.data.POLICY_ACCEPTANCE_REQUIRED_CODE
import com.emirrkls.phokarta.core.database.entity.PendingMutationEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitPayloadEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitPhotoEntity
import com.emirrkls.phokarta.core.database.entity.VisitDraftPhotoEntity
import com.emirrkls.phokarta.core.database.entity.MediaUploadState
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.time.EpochClock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import com.emirrkls.phokarta.core.auth.AuthState
import com.emirrkls.phokarta.core.data.VisitDraftRepository
import com.emirrkls.phokarta.core.data.toDraftDimensionEntities
import com.emirrkls.phokarta.core.data.toDraftEntity
import com.emirrkls.phokarta.core.data.toDomain
import com.emirrkls.phokarta.core.model.Visibility
import java.time.LocalDate
import com.emirrkls.phokarta.core.media.VisitMediaStore

data class PendingVisit(
    val mutationId: String,
    val visit: Visit,
    val state: String,
    val lastErrorCategory: String? = null,
) {
    val failed: Boolean get() = state == MutationStateValue.FAILED_PERMANENT ||
        state == MutationStateValue.FAILED_RETRYABLE

    val failureReason: SyncFailureReason?
        get() = when {
            lastErrorCategory == POLICY_ACCEPTANCE_REQUIRED_CODE ->
                SyncFailureReason.POLICY_ACCEPTANCE_REQUIRED
            state == MutationStateValue.FAILED_PERMANENT ->
                SyncFailureReason.fromCategory(lastErrorCategory)
            else -> null
        }

    val actions: PendingVisitActions
        get() = FailedVisitRecoveryPolicy.actionsFor(state, lastErrorCategory)
}

interface OfflineMutationRepository {
    suspend fun commitVisit(visit: Visit): String
    suspend fun toggleSaved(placeId: String): Boolean
    suspend fun retry(mutationId: String)
    suspend fun recoverFailedVisitForEditing(mutationId: String, replaceExisting: Boolean = false): RecoverFailedVisitResult
    suspend fun removeFailedVisit(mutationId: String): RemoveFailedVisitResult
    fun scheduleSync()
    fun observePendingVisits(): Flow<List<PendingVisit>>
    suspend fun mutationState(mutationId: String): String?
    suspend fun savedMutationState(placeId: String): String?
}

/** Marker used only by plain JVM repository tests that exercise the legacy remote fakes. */
object NoOpOfflineMutationRepository : OfflineMutationRepository {
    override suspend fun commitVisit(visit: Visit) = error("Offline mutation repository unavailable")
    override suspend fun toggleSaved(placeId: String) = error("Offline mutation repository unavailable")
    override suspend fun retry(mutationId: String) = Unit
    override suspend fun recoverFailedVisitForEditing(mutationId: String, replaceExisting: Boolean) =
        RecoverFailedVisitResult.NOT_FOUND
    override suspend fun removeFailedVisit(mutationId: String) = RemoveFailedVisitResult.NOT_FOUND
    override fun scheduleSync() = Unit
    override fun observePendingVisits(): Flow<List<PendingVisit>> = flowOf(emptyList())
    override suspend fun mutationState(mutationId: String): String? = null
    override suspend fun savedMutationState(placeId: String): String? = null
}

@Singleton
class RoomOfflineMutationRepository @Inject constructor(
    private val database: TravelDatabase,
    private val mutations: PendingMutationDao,
    private val drafts: VisitDraftDao,
    private val draftRepository: VisitDraftRepository,
    private val saved: SavedPlaceDao,
    private val session: SessionManager,
    private val clock: EpochClock,
    private val scheduler: MutationSyncScheduler,
    private val mediaStore: VisitMediaStore,
) : OfflineMutationRepository {
    override suspend fun commitVisit(visit: Visit): String {
        val userId = requireNotNull(session.currentUserId()) { "Authenticated user required" }
        val mutationId = UUID.randomUUID().toString()
        val now = clock.nowMillis()
        database.withTransaction {
            val draftPhotos = drafts.getPhotos(userId, visit.placeId)
            mutations.insertMutation(PendingMutationEntity(
                mutationId, userId, MutationTypeValue.PUBLISH_VISIT, mutationId,
                MutationStateValue.PENDING, 1, null, 0, now, now, null,
            ))
            mutations.insertVisitPayload(PendingVisitPayloadEntity(
                mutationId, visit.placeId, visit.visitedAt.toEpochDay(), visit.overallRating,
                visit.review, visit.personalNote, visit.visibility.name,
            ))
            if (visit.ratingDimensions.isNotEmpty()) {
                mutations.insertVisitDimensions(visit.ratingDimensions.map { (key, score) ->
                    PendingVisitDimensionScoreEntity(mutationId, key.name, score)
                })
            }
            if (draftPhotos.isNotEmpty()) {
                mutations.insertVisitPhotos(draftPhotos.sortedBy { it.position }.map { photo ->
                    PendingVisitPhotoEntity(
                        mutationId = mutationId,
                        position = photo.position,
                        ownerUserId = userId,
                        clientMediaId = photo.clientMediaId,
                        localRelativePath = photo.localRelativePath,
                        contentType = photo.contentType,
                        byteSize = photo.byteSize,
                        width = photo.width,
                        height = photo.height,
                        remoteMediaId = photo.remoteMediaId,
                        uploadState = photo.uploadState,
                        failureCategory = photo.failureCategory,
                        legacyUrl = photo.legacyUrl,
                    )
                })
            } else if (visit.photos.isNotEmpty()) {
                // Explicit legacy compatibility for old in-process callers/tests only.
                mutations.insertVisitPhotos(visit.photos.mapIndexed { index, url ->
                    PendingVisitPhotoEntity(mutationId, index, url)
                })
            }
            drafts.deleteDraft(userId, visit.placeId)
        }
        scheduler.schedule()
        return mutationId
    }

    override suspend fun toggleSaved(placeId: String): Boolean {
        val userId = requireNotNull(session.currentUserId()) { "Authenticated user required" }
        val now = clock.nowMillis()
        val target = database.withTransaction {
            val current = saved.getSavedPlace(userId, placeId) != null
            val desired = !current
            saved.setSaved(userId, placeId, desired, now)
            val existing = mutations.savedIntent(userId, placeId)
            mutations.upsertMutation(PendingMutationEntity(
                mutationId = existing?.mutationId ?: UUID.randomUUID().toString(),
                userId = userId,
                type = MutationTypeValue.SET_SAVED_STATE,
                resourceKey = placeId,
                state = MutationStateValue.PENDING,
                generation = (existing?.generation ?: 0) + 1,
                desiredSaved = desired,
                attemptCount = existing?.attemptCount ?: 0,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
                lastErrorCategory = null,
            ))
            desired
        }
        scheduler.schedule()
        return target
    }

    override suspend fun retry(mutationId: String) {
        mutations.retry(mutationId, clock.nowMillis())
        scheduler.schedule()
    }

    override suspend fun recoverFailedVisitForEditing(
        mutationId: String,
        replaceExisting: Boolean,
    ): RecoverFailedVisitResult {
        val userId = session.currentUserId() ?: return RecoverFailedVisitResult.NOT_OWNER
        val item = mutations.getVisit(mutationId) ?: return RecoverFailedVisitResult.NOT_FOUND
        if (item.mutation.userId != userId) return RecoverFailedVisitResult.NOT_OWNER
        if (item.mutation.state != MutationStateValue.FAILED_PERMANENT) {
            return RecoverFailedVisitResult.INVALID_STATE
        }
        val placeId = item.payload.placeId
        if (!replaceExisting) {
            val existing = drafts.getDraftWithDimensions(userId, placeId)
            val existingDraft = existing?.let { (entity, dimensions) -> entity.toDomain(dimensions) }
            if (FailedVisitRecoveryMapper.hasMeaningfulDraftConflict(existingDraft)) {
                return RecoverFailedVisitResult.EXISTING_DRAFT_CONFLICT
            }
        }
        val recovered = FailedVisitRecoveryMapper.toDraft(item)
        val now = clock.nowMillis()
        val displacedPhotos = drafts.getPhotos(userId, placeId)
        return try {
            database.withTransaction {
                val existing = drafts.getDraft(userId, placeId)
                val createdAt = existing?.createdAtEpochMillis ?: now
                drafts.upsertDraftWithDimensions(
                    draft = recovered.toDraftEntity(userId, placeId, createdAt, now),
                    scores = recovered.toDraftDimensionEntities(userId, placeId),
                )
                drafts.replacePhotos(
                    userId,
                    placeId,
                    item.photos.map { photo ->
                        VisitDraftPhotoEntity(
                            ownerUserId = userId,
                            placeId = placeId,
                            position = photo.position,
                            clientMediaId = photo.clientMediaId,
                            localRelativePath = photo.localRelativePath.orEmpty(),
                            contentType = photo.contentType ?: "application/octet-stream",
                            byteSize = photo.byteSize ?: 0,
                            width = photo.width,
                            height = photo.height,
                            remoteMediaId = photo.remoteMediaId,
                            uploadState = photo.uploadState,
                            failureCategory = photo.failureCategory,
                            legacyUrl = photo.legacyUrl,
                        )
                    },
                )
                val deleted = mutations.deleteIfState(
                    mutationId, userId, MutationStateValue.FAILED_PERMANENT,
                )
                if (deleted != 1) {
                    throw IllegalStateException("Failed mutation state changed during recovery")
                }
            }
            val retained = item.photos.mapNotNull { it.localRelativePath }.toSet()
            displacedPhotos.filterNot { it.localRelativePath in retained }.forEach {
                mediaStore.deleteOwned(userId, it.localRelativePath)
            }
            RecoverFailedVisitResult.SUCCESS
        } catch (_: IllegalStateException) {
            RecoverFailedVisitResult.INVALID_STATE
        }
    }

    override suspend fun removeFailedVisit(mutationId: String): RemoveFailedVisitResult {
        val userId = session.currentUserId() ?: return RemoveFailedVisitResult.NOT_OWNER
        val row = mutations.get(mutationId) ?: return RemoveFailedVisitResult.NOT_FOUND
        if (row.userId != userId) return RemoveFailedVisitResult.NOT_OWNER
        if (row.state != MutationStateValue.FAILED_PERMANENT) {
            return RemoveFailedVisitResult.INVALID_STATE
        }
        val files = mutations.getVisitPhotos(mutationId)
        val deleted = mutations.deleteIfState(mutationId, userId, MutationStateValue.FAILED_PERMANENT)
        if (deleted == 1) files.forEach { mediaStore.deleteOwned(userId, it.localRelativePath) }
        return if (deleted == 1) RemoveFailedVisitResult.SUCCESS else RemoveFailedVisitResult.INVALID_STATE
    }

    override fun scheduleSync() = scheduler.schedule()

    override suspend fun mutationState(mutationId: String): String? = mutations.get(mutationId)?.state

    override suspend fun savedMutationState(placeId: String): String? {
        val userId = session.currentUserId() ?: return null
        return mutations.savedIntent(userId, placeId)?.state
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observePendingVisits(): Flow<List<PendingVisit>> =
        session.state.flatMapLatest { auth ->
            val userId = (auth as? AuthState.Authenticated)?.user?.id
            if (userId == null) flowOf(emptyList()) else mutations.observeVisitMutations(userId).map { items ->
                items.map { item ->
                    val row = item.mutation
                    PendingVisit(
                        mutationId = row.mutationId,
                        state = row.state,
                        lastErrorCategory = row.lastErrorCategory,
                        visit = Visit(
                            id = row.mutationId,
                            userId = row.userId,
                            placeId = item.payload.placeId,
                            visitedAt = LocalDate.ofEpochDay(item.payload.visitedAtEpochDay),
                            overallRating = item.payload.overallRating,
                            ratingDimensions = item.dimensions.associate {
                                RatingDimension.valueOf(it.dimensionKey) to it.score
                            },
                            review = item.payload.publicReview,
                            personalNote = item.payload.privateMemory,
                            photos = item.photos.sortedBy { it.position }.mapNotNull { it.localRelativePath ?: it.legacyUrl },
                            visibility = Visibility.valueOf(item.payload.visibility),
                        ),
                    )
                }
            }
        }
}
