package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.model.Experience
import com.emirrkls.phokarta.core.model.ExperienceFeedLens
import com.emirrkls.phokarta.core.model.ExperiencePage
import com.emirrkls.phokarta.core.model.PlaceAggregateV2
import com.emirrkls.phokarta.core.model.RelationshipV2
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.mapper.toCanonicalUuid
import com.emirrkls.phokarta.core.network.mapper.toDomain
import com.emirrkls.phokarta.core.network.mapper.toExperiencePage
import com.emirrkls.phokarta.core.network.source.PlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.MediaRemoteDataSource
import com.emirrkls.phokarta.core.network.source.SocialRemoteDataSource
import com.emirrkls.phokarta.core.network.source.VisitRemoteDataSource
import com.emirrkls.phokarta.core.network.source.CollectionRemoteDataSource
import com.emirrkls.phokarta.core.sync.OfflineMutationRepository
import com.emirrkls.phokarta.core.database.dao.ExperienceMilestoneDao
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.model.PlannedExperienceItem
import com.emirrkls.phokarta.core.model.AcknowledgementAnchor
import com.emirrkls.phokarta.core.model.PrimaryExperienceCode
import com.emirrkls.phokarta.core.model.OverallFeelingCode
import com.emirrkls.phokarta.core.network.mapper.toEpochMillisSafely
import com.emirrkls.phokarta.core.database.entity.PlannedExperienceEntity
import com.emirrkls.phokarta.core.database.entity.ExperienceAcknowledgementEntity
import com.emirrkls.phokarta.core.database.entity.MutationTypeValue
import com.emirrkls.phokarta.core.database.dao.ConversationDao
import com.emirrkls.phokarta.core.database.entity.ConversationEntryEntity
import com.emirrkls.phokarta.core.model.ConversationAuthor
import com.emirrkls.phokarta.core.model.ConversationEntry
import com.emirrkls.phokarta.core.model.ConversationEntryType
import com.emirrkls.phokarta.core.model.ConversationSyncState
import com.emirrkls.phokarta.core.network.mapper.toConversationPage
import com.emirrkls.phokarta.core.network.model.ConversationEntryDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
import com.emirrkls.phokarta.core.database.TravelDatabase
import javax.inject.Inject
import javax.inject.Singleton

interface ExperienceFeedGateway {
    suspend fun feed(
        lens: ExperienceFeedLens,
        cursor: String? = null,
        size: Int = 20,
        search: String? = null,
        primary: String? = null,
        vibe: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        radiusMeters: Double? = null,
    ): RepositoryResult<ExperiencePage>

    suspend fun follow(authorId: String): RepositoryResult<RelationshipV2>
    suspend fun unfollow(authorId: String): RepositoryResult<RelationshipV2>
    suspend fun cancelFollowRequest(authorId: String): RepositoryResult<RelationshipV2>
    suspend fun togglePlan(id: String): RepositoryResult<Boolean> =
        RepositoryResult.Failure(TravelError.Unknown("Experience planning unavailable"))
    suspend fun acknowledge(id: String): RepositoryResult<String> =
        RepositoryResult.Failure(TravelError.Unknown("Experience acknowledgement unavailable"))
}

@Singleton
class ExperienceRepository @Inject constructor(
    private val visits: VisitRemoteDataSource,
    private val places: PlaceRemoteDataSource,
    private val social: SocialRemoteDataSource,
    private val media: MediaRemoteDataSource,
    private val offline: OfflineMutationRepository,
    private val milestoneDao: ExperienceMilestoneDao,
    private val session: SessionManager,
    private val database: TravelDatabase,
    private val collectionsRemote: CollectionRemoteDataSource,
    private val conversationDao: ConversationDao,
) : ExperienceFeedGateway {
    override suspend fun feed(
        lens: ExperienceFeedLens,
        cursor: String?,
        size: Int,
        search: String?,
        primary: String?,
        vibe: String?,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Double?,
    ): RepositoryResult<ExperiencePage> = map(
        request = {
            visits.experienceFeed(
                lens = lens.name,
                cursor = cursor,
                size = size,
                search = search?.trim()?.takeIf(String::isNotEmpty),
                primary = primary,
                vibe = vibe,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
            )
        },
        transform = { it.toExperiencePage() },
    )

    suspend fun detail(id: String): RepositoryResult<Experience> = map(
        request = { visits.experience(id.toCanonicalUuid()) },
        transform = { it.toDomain() },
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun conversation(experienceId: String): Flow<List<ConversationEntry>> = session.state.flatMapLatest { state ->
        val userId = (state as? com.emirrkls.phokarta.core.auth.AuthState.Authenticated)?.user?.id
            ?: return@flatMapLatest flowOf(emptyList())
        conversationDao.observe(userId, experienceId).map(::assembleConversation)
    }

    suspend fun refreshConversation(experienceId: String): RepositoryResult<List<ConversationEntry>> {
        val userId = session.currentUserId()
            ?: return RepositoryResult.Failure(TravelError.Unknown("Authentication required"))
        return when (val result = visits.conversation(experienceId.toCanonicalUuid(), null, 30)) {
            is RemoteResult.Failure -> {
                val cached = conversationDao.entries(userId, experienceId)
                if (cached.isNotEmpty()) RepositoryResult.Success(assembleConversation(cached))
                else RepositoryResult.Failure(result.error.toTravelError())
            }
            is RemoteResult.Success -> {
                database.withTransaction {
                    conversationDao.deleteSyncedSnapshot(userId, experienceId)
                    conversationDao.upsertEntries(result.value.items.flatMap { it.flatten(userId) })
                }
                RepositoryResult.Success(assembleConversation(conversationDao.entries(userId, experienceId)))
            }
        }
    }

    suspend fun createConversationRoot(
        experience: Experience, type: ConversationEntryType, body: String,
    ): RepositoryResult<String> = runCatching {
        offline.createConversationRoot(experience, type, body)
    }.fold({ RepositoryResult.Success(it) }, { RepositoryResult.Failure(TravelError.Validation(it.message)) })

    suspend fun createConversationReply(
        experience: Experience, root: ConversationEntry, body: String,
    ): RepositoryResult<String> = runCatching {
        offline.createConversationReply(experience, root, body)
    }.fold({ RepositoryResult.Success(it) }, { RepositoryResult.Failure(TravelError.Validation(it.message)) })

    suspend fun editConversationEntry(entry: ConversationEntry, body: String): RepositoryResult<Unit> =
        runCatching { offline.editConversationEntry(entry, body) }
            .fold({ RepositoryResult.Success(Unit) }, { RepositoryResult.Failure(TravelError.Validation(it.message)) })

    suspend fun deleteConversationEntry(entry: ConversationEntry): RepositoryResult<Unit> =
        runCatching { offline.deleteConversationEntry(entry) }
            .fold({ RepositoryResult.Success(Unit) }, { RepositoryResult.Failure(TravelError.Validation(it.message)) })

    suspend fun retryConversation(mutationId: String): RepositoryResult<Unit> = runCatching {
        offline.retry(mutationId)
    }.fold({ RepositoryResult.Success(Unit) }, { RepositoryResult.Failure(TravelError.Validation(it.message)) })

    override suspend fun togglePlan(id: String): RepositoryResult<Boolean> = when (val loaded = detail(id)) {
        is RepositoryResult.Failure -> loaded
        is RepositoryResult.Success -> RepositoryResult.Success(offline.togglePlannedExperience(loaded.value))
    }

    override suspend fun acknowledge(id: String): RepositoryResult<String> = when (val loaded = detail(id)) {
        is RepositoryResult.Failure -> loaded
        is RepositoryResult.Success -> RepositoryResult.Success(offline.acknowledgeExperience(loaded.value))
    }

    suspend fun addToCollection(collectionId: String, experienceId: String): RepositoryResult<Unit> = map(
        request = { collectionsRemote.addExperience(collectionId.toCanonicalUuid(), experienceId.toCanonicalUuid()) },
        transform = { Unit },
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun plannedExperiences(): Flow<List<PlannedExperienceItem>> = session.state.flatMapLatest { state ->
        val userId = (state as? com.emirrkls.phokarta.core.auth.AuthState.Authenticated)?.user?.id
            ?: return@flatMapLatest flowOf(emptyList())
        milestoneDao.observePlans(userId).map { rows -> rows.map { row ->
            PlannedExperienceItem(row.experienceId, row.title, row.placeId, row.placeName,
                PrimaryExperienceCode.fromWire(row.primaryExperienceCode),
                OverallFeelingCode.fromWire(row.feelingCode), row.authorName, row.imageUrl)
        } }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun acknowledgements(): Flow<List<AcknowledgementAnchor>> = session.state.flatMapLatest { state ->
        val userId = (state as? com.emirrkls.phokarta.core.auth.AuthState.Authenticated)?.user?.id
            ?: return@flatMapLatest flowOf(emptyList())
        milestoneDao.observeUnconvertedAcknowledgements(userId).map { rows -> rows.map { row ->
            AcknowledgementAnchor(row.id, row.sourceExperienceId, row.sourceAvailable,
                row.placeId, row.placeName, row.placeCity,
                PrimaryExperienceCode.fromWire(row.primaryExperienceCode), row.rawExperienceLabel,
                row.acknowledgedAtEpochMillis)
        } }
    }

    suspend fun refreshMilestoneState() {
        val userId = session.currentUserId() ?: return
        // Snapshot optimistic intents before starting network I/O. A worker can complete while
        // these list requests are in flight, so the response can legitimately be older than the
        // local action that triggered it.
        val mutationDao = database.pendingMutationDao()
        val planIntentsBefore = mutationDao.intents(userId, MutationTypeValue.SET_PLANNED_EXPERIENCE_STATE)
        val acknowledgementIntentsBefore = mutationDao.intents(userId, MutationTypeValue.ACKNOWLEDGE_EXPERIENCE)
        val plans = visits.plannedExperiences()
        val acknowledgements = visits.myAcknowledgements()
        database.withTransaction {
            if (plans is RemoteResult.Success) {
                val currentIntents = mutationDao.intents(userId, MutationTypeValue.SET_PLANNED_EXPERIENCE_STATE)
                val intents = (planIntentsBefore + currentIntents)
                    .associateBy { it.resourceKey }
                val preservedPlans = intents.values
                    .filter { it.desiredSaved == true }
                    .mapNotNull { milestoneDao.plan(userId, it.resourceKey) }
                milestoneDao.deletePlans(userId)
                milestoneDao.upsertPlans(plans.value.content
                    .filterNot { it.experience.id in intents.keys }
                    .map { dto ->
                    PlannedExperienceEntity(userId, dto.experience.id, dto.experience.title,
                        dto.experience.place.id, dto.experience.place.name,
                        dto.experience.primaryExperience.code, dto.experience.feeling.code,
                        dto.experience.author.displayName,
                        dto.experience.media.minByOrNull { it.position }?.url,
                        dto.plannedAt.toEpochMillisSafely())
                })
                milestoneDao.upsertPlans(preservedPlans)
            }
            if (acknowledgements is RemoteResult.Success) {
                val currentIntents = mutationDao.intents(userId, MutationTypeValue.ACKNOWLEDGE_EXPERIENCE)
                val intents = (acknowledgementIntentsBefore + currentIntents)
                    .associateBy { it.resourceKey }
                val preservedAcknowledgements = intents.values.mapNotNull {
                    milestoneDao.acknowledgementForSource(userId, it.resourceKey)
                }
                milestoneDao.deleteAcknowledgements(userId)
                milestoneDao.upsertAcknowledgements(acknowledgements.value.content
                    .filterNot { it.sourceExperienceId in intents.keys }
                    .map { dto ->
                    ExperienceAcknowledgementEntity(userId, dto.id, dto.sourceExperienceId,
                        dto.sourceAvailable, dto.place.id, dto.place.name, dto.place.city,
                        dto.place.region, dto.place.country, dto.primaryExperienceCode,
                        dto.rawExperienceLabel, dto.acknowledgedAt.toEpochMillisSafely(),
                        dto.convertedExperienceId)
                })
                milestoneDao.upsertAcknowledgements(preservedAcknowledgements)
            }
        }
    }

    suspend fun forPlace(
        placeId: String,
        cursor: String? = null,
        size: Int = 20,
        primary: String? = null,
    ): RepositoryResult<ExperiencePage> = map(
        request = { visits.placeExperiences(placeId.toCanonicalUuid(), cursor, size, primary) },
        transform = { it.toExperiencePage() },
    )

    suspend fun forProfile(
        userId: String,
        cursor: String? = null,
        size: Int = 20,
    ): RepositoryResult<ExperiencePage> = map(
        request = { visits.profileExperiences(userId.toCanonicalUuid(), cursor, size) },
        transform = { it.toExperiencePage() },
    )

    suspend fun aggregate(placeId: String): RepositoryResult<PlaceAggregateV2> = map(
        request = { places.aggregateV2(placeId.toCanonicalUuid()) },
        transform = { it.toDomain() },
    )

    override suspend fun follow(authorId: String): RepositoryResult<RelationshipV2> = map(
        request = { social.followV2(authorId.toCanonicalUuid()) },
        transform = { it.toDomain() },
    )

    override suspend fun unfollow(authorId: String): RepositoryResult<RelationshipV2> = map(
        request = { social.unfollowV2(authorId.toCanonicalUuid()) },
        transform = { it.toDomain() },
    )

    override suspend fun cancelFollowRequest(authorId: String): RepositoryResult<RelationshipV2> = map(
        request = { social.cancelFollowRequest(authorId.toCanonicalUuid()) },
        transform = { it.toDomain() },
    )

    suspend fun renewMediaUrl(mediaId: String): RepositoryResult<Pair<String, String>> = map(
        request = { media.access(mediaId.toCanonicalUuid()) },
        transform = { it.url to it.expiresAt },
    )

    private suspend fun <Network, Domain> map(
        request: suspend () -> RemoteResult<Network>,
        transform: (Network) -> Domain,
    ): RepositoryResult<Domain> = when (val result = request()) {
        is RemoteResult.Success -> RepositoryResult.Success(transform(result.value))
        is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
    }
}

private fun ConversationEntryDto.flatten(ownerUserId: String, parentId: String? = null): List<ConversationEntryEntity> {
    val current = ConversationEntryEntity(
        ownerUserId = ownerUserId, id = id, experienceId = experienceId,
        parentEntryId = parentId, type = type, body = body,
        authorId = author.id, authorUsername = author.username,
        authorDisplayName = author.displayName, authorAvatarUrl = author.avatarUrl,
        createdAt = createdAt, updatedAt = updatedAt, edited = edited,
        experienceAuthor = experienceAuthor, ownedByViewer = ownedByViewer,
        reportableByViewer = reportableByViewer, syncState = ConversationSyncState.SYNCED.name,
        clientMutationId = null,
    )
    return listOf(current) + replies.flatMap { it.flatten(ownerUserId, id) }
}

private fun assembleConversation(rows: List<ConversationEntryEntity>): List<ConversationEntry> {
    val replies = rows.filter { it.parentEntryId != null }.groupBy { it.parentEntryId }
    return rows.filter { it.parentEntryId == null }
        .sortedWith(compareByDescending<ConversationEntryEntity> { it.createdAt }.thenByDescending { it.id })
        .map { root -> root.toDomain(replies[root.id].orEmpty().sortedWith(
            compareBy<ConversationEntryEntity> { it.createdAt }.thenBy { it.id },
        ).map { it.toDomain() }) }
}

private fun ConversationEntryEntity.toDomain(replies: List<ConversationEntry> = emptyList()) = ConversationEntry(
    id = id, experienceId = experienceId, type = ConversationEntryType.fromWire(type), body = body,
    author = ConversationAuthor(authorId, authorUsername, authorDisplayName, authorAvatarUrl),
    createdAt = createdAt, updatedAt = updatedAt, edited = edited,
    experienceAuthor = experienceAuthor, ownedByViewer = ownedByViewer,
    reportableByViewer = reportableByViewer, replies = replies,
    syncState = runCatching { ConversationSyncState.valueOf(syncState) }.getOrDefault(ConversationSyncState.FAILED),
    clientMutationId = clientMutationId,
)
