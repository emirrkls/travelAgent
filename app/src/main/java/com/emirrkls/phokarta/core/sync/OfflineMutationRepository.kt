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
import com.emirrkls.phokarta.core.database.entity.PendingExperienceV2DimensionEntity
import com.emirrkls.phokarta.core.database.entity.PendingExperienceV2PayloadEntity
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
import kotlinx.coroutines.flow.combine
import com.emirrkls.phokarta.core.auth.AuthState
import com.emirrkls.phokarta.core.data.VisitDraftRepository
import com.emirrkls.phokarta.core.data.toDraftDimensionEntities
import com.emirrkls.phokarta.core.data.toDraftEntity
import com.emirrkls.phokarta.core.data.toDomain
import com.emirrkls.phokarta.core.model.Visibility
import java.time.LocalDate
import com.emirrkls.phokarta.core.media.VisitMediaStore
import com.emirrkls.phokarta.feature.rating.VisitDraft
import com.emirrkls.phokarta.feature.rating.VisitDraftLogic
import com.emirrkls.phokarta.core.model.Experience
import com.emirrkls.phokarta.core.database.dao.ExperienceMilestoneDao
import com.emirrkls.phokarta.core.database.entity.PlannedExperienceEntity
import com.emirrkls.phokarta.core.database.entity.ExperienceAcknowledgementEntity
import com.emirrkls.phokarta.core.database.dao.ConversationDao
import com.emirrkls.phokarta.core.database.entity.ConversationEntryEntity
import com.emirrkls.phokarta.core.database.entity.PendingConversationPayloadEntity
import com.emirrkls.phokarta.core.model.ConversationEntry
import com.emirrkls.phokarta.core.model.ConversationEntryType
import com.emirrkls.phokarta.core.model.ConversationSyncState
import java.time.Instant

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
    suspend fun commitExperienceV2(placeId: String, draft: VisitDraft): String
    suspend fun toggleSaved(placeId: String): Boolean
    suspend fun togglePlannedExperience(experience: Experience): Boolean
    suspend fun acknowledgeExperience(experience: Experience): String
    suspend fun createConversationRoot(experience: Experience, type: ConversationEntryType, body: String): String
    suspend fun createConversationReply(experience: Experience, root: ConversationEntry, body: String): String
    suspend fun editConversationEntry(entry: ConversationEntry, body: String)
    suspend fun deleteConversationEntry(entry: ConversationEntry)
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
    override suspend fun commitExperienceV2(placeId: String, draft: VisitDraft) =
        error("Offline mutation repository unavailable")
    override suspend fun toggleSaved(placeId: String) = error("Offline mutation repository unavailable")
    override suspend fun togglePlannedExperience(experience: Experience) = error("Offline mutation repository unavailable")
    override suspend fun acknowledgeExperience(experience: Experience) = error("Offline mutation repository unavailable")
    override suspend fun createConversationRoot(experience: Experience, type: ConversationEntryType, body: String) =
        error("Offline mutation repository unavailable")
    override suspend fun createConversationReply(experience: Experience, root: ConversationEntry, body: String) =
        error("Offline mutation repository unavailable")
    override suspend fun editConversationEntry(entry: ConversationEntry, body: String) =
        error("Offline mutation repository unavailable")
    override suspend fun deleteConversationEntry(entry: ConversationEntry) =
        error("Offline mutation repository unavailable")
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
    private val experienceMilestones: ExperienceMilestoneDao,
    private val conversations: ConversationDao = database.conversationDao(),
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

    override suspend fun commitExperienceV2(placeId: String, draft: VisitDraft): String {
        val userId = requireNotNull(session.currentUserId()) { "Authenticated user required" }
        require(draft.payloadVersion == 2 && VisitDraftLogic.canPublish(draft)) {
            "Valid native V2 Experience draft required"
        }
        val mutationId = UUID.randomUUID().toString()
        val now = clock.nowMillis()
        database.withTransaction {
            val draftPhotos = drafts.getPhotos(userId, placeId).sortedBy { it.position }
            require(draftPhotos.size <= VisitDraftLogic.V2_MAX_MEDIA) { "V2 media limit exceeded" }
            mutations.insertMutation(PendingMutationEntity(
                mutationId = mutationId,
                userId = userId,
                type = MutationTypeValue.PUBLISH_EXPERIENCE_V2,
                resourceKey = mutationId,
                state = MutationStateValue.PENDING,
                generation = 1,
                desiredSaved = null,
                attemptCount = 0,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                lastErrorCategory = null,
                payloadVersion = 2,
            ))
            mutations.insertExperienceV2Payload(PendingExperienceV2PayloadEntity(
                mutationId = mutationId,
                placeId = placeId,
                visitedAtEpochDay = draft.visitDate.toEpochDay(),
                primaryExperienceCode = requireNotNull(draft.primaryExperience).name,
                rawExperienceLabel = draft.rawExperienceLabel?.trim()?.takeIf(String::isNotEmpty),
                overallFeelingCode = requireNotNull(draft.overallFeeling).name,
                companionCode = draft.companion?.name,
                timeOfDayCode = draft.timeOfDay?.name,
                vibeCodes = draft.vibes.map { it.name }.sorted().joinToString(","),
                practicalSignalCodes = draft.practicalSignals.map { it.name }.sorted().joinToString(","),
                title = draft.title?.trim()?.takeIf(String::isNotEmpty),
                titleSource = draft.titleSource.name,
                story = draft.story.trim(),
                tip = draft.tip.trim(),
                privateMemory = draft.privateMemory.trim(),
                visibility = draft.visibility.name,
                originAcknowledgementId = draft.originAcknowledgementId,
            ))
            if (draft.semanticDimensions.isNotEmpty()) {
                mutations.insertExperienceV2Dimensions(
                    draft.semanticDimensions.toSortedMap().map { (key, state) ->
                        PendingExperienceV2DimensionEntity(mutationId, key, state.name, 1)
                    },
                )
            }
            if (draftPhotos.isNotEmpty()) {
                mutations.insertVisitPhotos(draftPhotos.map { photo ->
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
            }
            drafts.deleteDraft(userId, placeId)
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

    override suspend fun togglePlannedExperience(experience: Experience): Boolean {
        val userId = requireNotNull(session.currentUserId()) { "Authenticated user required" }
        val now = clock.nowMillis()
        val desired = database.withTransaction {
            val desiredState = experienceMilestones.plan(userId, experience.id) == null
            if (desiredState) {
                experienceMilestones.upsertPlan(PlannedExperienceEntity(
                    ownerUserId = userId,
                    experienceId = experience.id,
                    title = experience.title,
                    placeId = experience.place.id,
                    placeName = experience.place.name,
                    primaryExperienceCode = experience.primaryExperience.code.name,
                    feelingCode = experience.feeling.code.name,
                    authorName = experience.author.displayName,
                    imageUrl = experience.media.minByOrNull { it.position }?.url,
                    plannedAtEpochMillis = now,
                ))
            } else experienceMilestones.deletePlan(userId, experience.id)
            val existing = mutations.intent(userId, MutationTypeValue.SET_PLANNED_EXPERIENCE_STATE, experience.id)
            mutations.upsertMutation(PendingMutationEntity(
                mutationId = existing?.mutationId ?: UUID.randomUUID().toString(), userId = userId,
                type = MutationTypeValue.SET_PLANNED_EXPERIENCE_STATE, resourceKey = experience.id,
                state = MutationStateValue.PENDING, generation = (existing?.generation ?: 0) + 1,
                desiredSaved = desiredState, attemptCount = existing?.attemptCount ?: 0,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now, lastErrorCategory = null,
            ))
            desiredState
        }
        scheduler.schedule()
        return desired
    }

    override suspend fun acknowledgeExperience(experience: Experience): String {
        val userId = requireNotNull(session.currentUserId()) { "Authenticated user required" }
        experienceMilestones.acknowledgementForSource(userId, experience.id)?.let { return it.id }
        val now = clock.nowMillis()
        val pendingId = UUID.randomUUID().toString()
        database.withTransaction {
            experienceMilestones.upsertAcknowledgement(ExperienceAcknowledgementEntity(
                ownerUserId = userId, id = pendingId, sourceExperienceId = experience.id,
                sourceAvailable = true, placeId = experience.place.id, placeName = experience.place.name,
                placeCity = experience.place.city, placeRegion = experience.place.region,
                placeCountry = experience.place.country,
                primaryExperienceCode = experience.primaryExperience.code.name,
                rawExperienceLabel = experience.primaryExperience.rawLabel,
                acknowledgedAtEpochMillis = now, convertedExperienceId = null,
            ))
            val existing = mutations.intent(userId, MutationTypeValue.ACKNOWLEDGE_EXPERIENCE, experience.id)
            mutations.upsertMutation(PendingMutationEntity(
                mutationId = existing?.mutationId ?: pendingId, userId = userId,
                type = MutationTypeValue.ACKNOWLEDGE_EXPERIENCE, resourceKey = experience.id,
                state = MutationStateValue.PENDING, generation = existing?.generation ?: 1,
                desiredSaved = true, attemptCount = existing?.attemptCount ?: 0,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now, lastErrorCategory = null,
            ))
        }
        scheduler.schedule()
        return pendingId
    }

    override suspend fun createConversationRoot(
        experience: Experience,
        type: ConversationEntryType,
        body: String,
    ): String {
        require(type == ConversationEntryType.QUESTION || type == ConversationEntryType.COMMENT)
        val normalized = conversationBody(body)
        val user = (session.state.value as? AuthState.Authenticated)?.user
            ?: error("Authenticated user required")
        val mutationId = UUID.randomUUID().toString()
        val now = clock.nowMillis()
        database.withTransaction {
            mutations.insertMutation(PendingMutationEntity(
                mutationId, user.id, MutationTypeValue.CREATE_CONVERSATION_ROOT, mutationId,
                MutationStateValue.PENDING, 1, null, 0, now, now, null,
            ))
            conversations.upsertPayload(PendingConversationPayloadEntity(
                mutationId, experience.id, null, null, type.name, normalized, mutationId,
            ))
            conversations.upsertEntry(ConversationEntryEntity(
                ownerUserId = user.id, id = mutationId, experienceId = experience.id,
                parentEntryId = null, type = type.name, body = normalized,
                authorId = user.id, authorUsername = user.username,
                authorDisplayName = user.displayName, authorAvatarUrl = user.avatarUrl,
                createdAt = Instant.ofEpochMilli(now).toString(),
                updatedAt = Instant.ofEpochMilli(now).toString(), edited = false,
                experienceAuthor = user.id == experience.author.id, ownedByViewer = true,
                reportableByViewer = false, syncState = ConversationSyncState.PENDING.name,
                clientMutationId = mutationId,
            ))
        }
        scheduler.schedule()
        return mutationId
    }

    override suspend fun createConversationReply(
        experience: Experience,
        root: ConversationEntry,
        body: String,
    ): String {
        require(root.type != ConversationEntryType.REPLY && root.syncState == ConversationSyncState.SYNCED) {
            "Reply is available after the root finishes sending"
        }
        val normalized = conversationBody(body)
        val user = (session.state.value as? AuthState.Authenticated)?.user
            ?: error("Authenticated user required")
        val mutationId = UUID.randomUUID().toString()
        val now = clock.nowMillis()
        database.withTransaction {
            mutations.insertMutation(PendingMutationEntity(
                mutationId, user.id, MutationTypeValue.CREATE_CONVERSATION_REPLY, mutationId,
                MutationStateValue.PENDING, 1, null, 0, now, now, null,
            ))
            conversations.upsertPayload(PendingConversationPayloadEntity(
                mutationId, experience.id, null, root.id, ConversationEntryType.REPLY.name,
                normalized, mutationId,
            ))
            conversations.upsertEntry(ConversationEntryEntity(
                ownerUserId = user.id, id = mutationId, experienceId = experience.id,
                parentEntryId = root.id, type = ConversationEntryType.REPLY.name, body = normalized,
                authorId = user.id, authorUsername = user.username,
                authorDisplayName = user.displayName, authorAvatarUrl = user.avatarUrl,
                createdAt = Instant.ofEpochMilli(now).toString(),
                updatedAt = Instant.ofEpochMilli(now).toString(), edited = false,
                experienceAuthor = user.id == experience.author.id, ownedByViewer = true,
                reportableByViewer = false, syncState = ConversationSyncState.PENDING.name,
                clientMutationId = mutationId,
            ))
        }
        scheduler.schedule()
        return mutationId
    }

    override suspend fun editConversationEntry(entry: ConversationEntry, body: String) {
        require(entry.ownedByViewer && entry.syncState == ConversationSyncState.SYNCED)
        val userId = requireNotNull(session.currentUserId()) { "Authenticated user required" }
        val normalized = conversationBody(body)
        val mutationId = UUID.randomUUID().toString()
        val now = clock.nowMillis()
        database.withTransaction {
            mutations.insertMutation(PendingMutationEntity(
                mutationId, userId, MutationTypeValue.EDIT_CONVERSATION_ENTRY, entry.id,
                MutationStateValue.PENDING, 1, null, 0, now, now, null,
            ))
            conversations.upsertPayload(PendingConversationPayloadEntity(
                mutationId, entry.experienceId, entry.id, null, null, normalized, entry.id,
            ))
            conversations.updateBody(userId, entry.id, normalized,
                Instant.ofEpochMilli(now).toString(), ConversationSyncState.PENDING.name)
        }
        scheduler.schedule()
    }

    override suspend fun deleteConversationEntry(entry: ConversationEntry) {
        require(entry.ownedByViewer && entry.syncState == ConversationSyncState.SYNCED)
        val userId = requireNotNull(session.currentUserId()) { "Authenticated user required" }
        val mutationId = UUID.randomUUID().toString()
        val now = clock.nowMillis()
        database.withTransaction {
            mutations.insertMutation(PendingMutationEntity(
                mutationId, userId, MutationTypeValue.DELETE_CONVERSATION_ENTRY, entry.id,
                MutationStateValue.PENDING, 1, null, 0, now, now, null,
            ))
            conversations.upsertPayload(PendingConversationPayloadEntity(
                mutationId, entry.experienceId, entry.id, null, null, null, entry.id,
            ))
            conversations.markEntryState(userId, entry.id, mutationId, ConversationSyncState.PENDING.name)
        }
        scheduler.schedule()
    }

    override suspend fun retry(mutationId: String) {
        mutations.retry(mutationId, clock.nowMillis())
        val userId = session.currentUserId()
        if (userId != null) conversations.markState(userId, mutationId, ConversationSyncState.PENDING.name)
        scheduler.schedule()
    }

    override suspend fun recoverFailedVisitForEditing(
        mutationId: String,
        replaceExisting: Boolean,
    ): RecoverFailedVisitResult {
        val userId = session.currentUserId() ?: return RecoverFailedVisitResult.NOT_OWNER
        val row = mutations.get(mutationId) ?: return RecoverFailedVisitResult.NOT_FOUND
        if (row.userId != userId) return RecoverFailedVisitResult.NOT_OWNER
        if (row.state != MutationStateValue.FAILED_PERMANENT) {
            return RecoverFailedVisitResult.INVALID_STATE
        }
        val legacy = if (row.type == MutationTypeValue.PUBLISH_VISIT) mutations.getVisit(mutationId) else null
        val native = if (row.type == MutationTypeValue.PUBLISH_EXPERIENCE_V2) mutations.getExperienceV2(mutationId) else null
        val placeId = legacy?.payload?.placeId ?: native?.payload?.placeId
            ?: return RecoverFailedVisitResult.NOT_FOUND
        val recovered = legacy?.let(FailedVisitRecoveryMapper::toDraft)
            ?: native?.let(FailedVisitRecoveryMapper::toDraft)
            ?: return RecoverFailedVisitResult.NOT_FOUND
        val photos = legacy?.photos ?: native?.photos.orEmpty()
        if (!replaceExisting) {
            val existing = drafts.getDraftWithDimensions(userId, placeId)
            val existingDraft = existing?.let { (entity, dimensions) -> entity.toDomain(dimensions) }
            if (FailedVisitRecoveryMapper.hasMeaningfulDraftConflict(existingDraft)) {
                return RecoverFailedVisitResult.EXISTING_DRAFT_CONFLICT
            }
        }
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
                    photos.map { photo ->
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
            val retained = photos.mapNotNull { it.localRelativePath }.toSet()
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
            if (userId == null) flowOf(emptyList()) else combine(
                mutations.observeVisitMutations(userId),
                mutations.observeExperienceV2Mutations(userId),
            ) { legacyItems, nativeItems ->
                val createdAtByMutation = (
                    legacyItems.map { it.mutation.mutationId to it.mutation.createdAtEpochMillis } +
                        nativeItems.map { it.mutation.mutationId to it.mutation.createdAtEpochMillis }
                    ).toMap()
                val legacyPending = legacyItems.map { item ->
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
                val nativePending = nativeItems.map { item ->
                    val row = item.mutation
                    val feelingScore = when (item.payload.overallFeelingCode) {
                        "BAYILDIM" -> 10.0
                        "GUZELDI" -> 8.0
                        "EH_ISTE" -> 6.0
                        "BEKLENTIMI_KARSILAMADI" -> 4.0
                        "BIR_DAHA_TERCIH_ETMEM" -> 2.0
                        else -> 0.0
                    }
                    PendingVisit(
                        mutationId = row.mutationId,
                        state = row.state,
                        lastErrorCategory = row.lastErrorCategory,
                        visit = Visit(
                            id = row.mutationId,
                            userId = row.userId,
                            placeId = item.payload.placeId,
                            visitedAt = LocalDate.ofEpochDay(item.payload.visitedAtEpochDay),
                            overallRating = feelingScore,
                            ratingDimensions = item.dimensions.mapNotNull { dimension ->
                                RatingDimension.fromStoredKey(dimension.dimensionKey)?.let { key ->
                                    val score = when (dimension.semanticStateCode) {
                                        "VERY_GOOD" -> 10.0
                                        "GOOD" -> 8.0
                                        "MEDIUM" -> 6.0
                                        "WEAK" -> 4.0
                                        "VERY_WEAK" -> 2.0
                                        else -> return@mapNotNull null
                                    }
                                    key to score
                                }
                            }.toMap(),
                            review = item.payload.story,
                            personalNote = item.payload.privateMemory,
                            photos = item.photos.sortedBy { it.position }.mapNotNull {
                                it.localRelativePath ?: it.legacyUrl
                            },
                            visibility = Visibility.valueOf(item.payload.visibility),
                        ),
                    )
                }
                (legacyPending + nativePending).sortedBy { pending ->
                    createdAtByMutation[pending.mutationId] ?: Long.MAX_VALUE
                }
            }
        }
}

private fun conversationBody(value: String): String = value.trim().also {
    require(it.isNotEmpty() && it.length <= 1000) { "Conversation body must be between 1 and 1000 characters" }
}
