package com.emirrkls.phokarta.core.data

import android.net.Uri
import com.emirrkls.phokarta.core.auth.AuthState
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.database.dao.VisitDraftDao
import com.emirrkls.phokarta.core.time.EpochClock
import com.emirrkls.phokarta.feature.rating.VisitDraft
import com.emirrkls.phokarta.core.media.MediaFileMutationLock
import com.emirrkls.phokarta.core.media.MediaImportResult
import com.emirrkls.phokarta.core.media.VisitMediaStore
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
    private val mediaStore: VisitMediaStore,
    private val fileMutationLock: MediaFileMutationLock,
) : VisitDraftRepository {
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
            fileMutationLock.withLock {
                dao.getPhotos(userId, placeId).forEach {
                    mediaStore.deleteOwned(userId, it.localRelativePath)
                }
                dao.deleteDraft(userId, placeId)
            }
            return null
        }
        return entity.toDomain(dimensions).copy(
            photos = dao.getPhotos(userId, placeId).mapNotNull {
                it.localRelativePath.takeIf(String::isNotBlank) ?: it.legacyUrl
            },
        )
    }

    override suspend fun hasDraft(placeId: String): Boolean {
        val userId = sessionUserId() ?: return false
        val entity = dao.getDraft(userId, placeId) ?: return false
        if (isExpired(entity.updatedAtEpochMillis)) {
            fileMutationLock.withLock {
                dao.getPhotos(userId, placeId).forEach {
                    mediaStore.deleteOwned(userId, it.localRelativePath)
                }
                dao.deleteDraft(userId, placeId)
            }
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
    }

    override suspend fun deleteDraft(placeId: String, ownerUserId: String) {
        val sessionId = sessionUserId() ?: return
        if (sessionId != ownerUserId) return
        fileMutationLock.withLock {
            dao.getPhotos(ownerUserId, placeId).forEach {
                mediaStore.deleteOwned(ownerUserId, it.localRelativePath)
            }
            dao.deleteDraft(ownerUserId, placeId)
        }
    }

    override suspend fun attachSessionPhotos(placeId: String, photos: List<String>, ownerUserId: String) {
        // Kept for binary/source compatibility. Recovery now transfers Room photo rows atomically.
    }

    override suspend fun deleteExpiredDrafts() {
        fileMutationLock.withLock {
            val cutoff = clock.nowMillis() - VisitDraftRepository.EXPIRY_MS
            dao.getExpiredPhotos(cutoff).forEach {
                mediaStore.deleteOwned(it.ownerUserId, it.localRelativePath)
            }
            dao.deleteExpired(cutoff)
        }
    }

    override suspend fun importPhoto(
        placeId: String,
        uri: Uri,
        ownerUserId: String,
    ): MediaImportResult = fileMutationLock.withLock {
        if (sessionUserId() != ownerUserId) return@withLock MediaImportResult.Unreadable
        val existing = dao.getPhotos(ownerUserId, placeId)
        if (existing.size >= VisitMediaStore.MAX_PHOTOS) return@withLock MediaImportResult.MaxCount
        val result = mediaStore.import(ownerUserId, placeId, existing.size, uri)
        if (result is MediaImportResult.Success) dao.upsertPhotos(listOf(result.photo))
        result
    }

    override suspend fun removePhoto(placeId: String, relativePath: String, ownerUserId: String) {
        fileMutationLock.withLock {
            if (sessionUserId() != ownerUserId) return@withLock
            val existing = dao.getPhotos(ownerUserId, placeId)
            existing.firstOrNull {
                it.localRelativePath == relativePath || it.legacyUrl == relativePath
            }?.let {
                mediaStore.deleteOwned(ownerUserId, it.localRelativePath)
            }
            dao.replacePhotos(
                ownerUserId,
                placeId,
                existing.filterNot {
                    it.localRelativePath == relativePath || it.legacyUrl == relativePath
                }
                    .mapIndexed { index, photo -> photo.copy(position = index) },
            )
        }
    }

    private fun isExpired(updatedAtEpochMillis: Long): Boolean =
        updatedAtEpochMillis < clock.nowMillis() - VisitDraftRepository.EXPIRY_MS
}
