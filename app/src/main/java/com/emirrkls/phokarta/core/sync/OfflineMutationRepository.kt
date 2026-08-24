package com.emirrkls.phokarta.core.sync

import androidx.room.withTransaction
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.database.dao.PendingMutationDao
import com.emirrkls.phokarta.core.database.dao.SavedPlaceDao
import com.emirrkls.phokarta.core.database.dao.VisitDraftDao
import com.emirrkls.phokarta.core.database.entity.MutationStateValue
import com.emirrkls.phokarta.core.database.entity.MutationTypeValue
import com.emirrkls.phokarta.core.database.entity.PendingMutationEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitPayloadEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitPhotoEntity
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
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import java.time.LocalDate

data class PendingVisit(val mutationId: String, val visit: Visit, val state: String) {
    val failed: Boolean get() = state == MutationStateValue.FAILED_PERMANENT ||
        state == MutationStateValue.FAILED_RETRYABLE
}

interface OfflineMutationRepository {
    suspend fun commitVisit(visit: Visit): String
    suspend fun toggleSaved(placeId: String): Boolean
    suspend fun retry(mutationId: String)
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
    private val saved: SavedPlaceDao,
    private val session: SessionManager,
    private val clock: EpochClock,
    private val scheduler: MutationSyncScheduler,
) : OfflineMutationRepository {
    override suspend fun commitVisit(visit: Visit): String {
        val userId = requireNotNull(session.currentUserId()) { "Authenticated user required" }
        val mutationId = UUID.randomUUID().toString()
        val now = clock.nowMillis()
        database.withTransaction {
            mutations.insertMutation(PendingMutationEntity(
                mutationId, userId, MutationTypeValue.PUBLISH_VISIT, visit.placeId,
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
            if (visit.photos.isNotEmpty()) {
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
                            photos = item.photos.sortedBy { it.position }.map { it.url },
                            visibility = Visibility.valueOf(item.payload.visibility),
                        ),
                    )
                }
            }
        }
}
