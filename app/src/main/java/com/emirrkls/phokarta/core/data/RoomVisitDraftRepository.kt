package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.auth.AuthState
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.database.dao.VisitDraftDao
import com.emirrkls.phokarta.core.time.EpochClock
import com.emirrkls.phokarta.feature.rating.VisitDraft
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

@Singleton
class RoomVisitDraftRepository @Inject constructor(
    private val dao: VisitDraftDao,
    private val sessionManager: SessionManager,
    private val clock: EpochClock,
) : VisitDraftRepository {
    private val sessionPhotos = mutableMapOf<Pair<String, String>, List<String>>()
    private fun sessionUserId(): String? = sessionManager.currentUserId()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeHasDraft(placeId: String): Flow<Boolean> =
        sessionManager.state.flatMapLatest { state ->
            val userId = (state as? AuthState.Authenticated)?.user?.id
            if (userId.isNullOrBlank()) {
                flowOf(false)
            } else {
                dao.observeHasDraft(userId, placeId)
            }
        }

    override suspend fun getDraft(placeId: String): VisitDraft? {
        val userId = sessionUserId() ?: return null
        val stored = dao.getDraftWithDimensions(userId, placeId) ?: return null
        val (entity, dimensions) = stored
        if (isExpired(entity.updatedAtEpochMillis)) {
            dao.deleteDraft(userId, placeId)
            return null
        }
        return entity.toDomain(dimensions).withSessionPhotos(userId, placeId)
    }

    private fun VisitDraft.withSessionPhotos(userId: String, placeId: String): VisitDraft =
        copy(photos = sessionPhotos[userId to placeId].orEmpty().ifEmpty { photos })

    override suspend fun hasDraft(placeId: String): Boolean {
        val userId = sessionUserId() ?: return false
        val entity = dao.getDraft(userId, placeId) ?: return false
        if (isExpired(entity.updatedAtEpochMillis)) {
            dao.deleteDraft(userId, placeId)
            return false
        }
        return true
    }

    override suspend fun saveDraft(placeId: String, draft: VisitDraft, ownerUserId: String) {
        val sessionId = sessionUserId() ?: return
        if (sessionId != ownerUserId) return
        val now = clock.nowMillis()
        val existing = dao.getDraft(ownerUserId, placeId)
        val createdAt = existing?.createdAtEpochMillis ?: now
        dao.upsertDraftWithDimensions(
            draft = draft.toDraftEntity(
                userId = ownerUserId,
                placeId = placeId,
                createdAtEpochMillis = createdAt,
                updatedAtEpochMillis = now,
            ),
            scores = draft.toDraftDimensionEntities(ownerUserId, placeId),
        )
        if (draft.photos.isEmpty()) {
            sessionPhotos.remove(ownerUserId to placeId)
        } else {
            sessionPhotos[ownerUserId to placeId] = draft.photos.toList()
        }
    }

    override suspend fun deleteDraft(placeId: String, ownerUserId: String) {
        val sessionId = sessionUserId() ?: return
        if (sessionId != ownerUserId) return
        dao.deleteDraft(ownerUserId, placeId)
        sessionPhotos.remove(ownerUserId to placeId)
    }

    override suspend fun attachSessionPhotos(placeId: String, photos: List<String>, ownerUserId: String) {
        val sessionId = sessionUserId() ?: return
        if (sessionId != ownerUserId) return
        if (photos.isEmpty()) {
            sessionPhotos.remove(ownerUserId to placeId)
        } else {
            sessionPhotos[ownerUserId to placeId] = photos.toList()
        }
    }

    override suspend fun deleteExpiredDrafts() {
        dao.deleteExpired(clock.nowMillis() - VisitDraftRepository.EXPIRY_MS)
    }

    private fun isExpired(updatedAtEpochMillis: Long): Boolean =
        updatedAtEpochMillis < clock.nowMillis() - VisitDraftRepository.EXPIRY_MS
}
