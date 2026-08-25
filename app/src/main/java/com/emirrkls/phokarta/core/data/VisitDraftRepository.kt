package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.feature.rating.VisitDraft
import kotlinx.coroutines.flow.Flow

/**
 * Local-only unfinished visit drafts. Never syncs to backend.
 * Ownership is always the authenticated session user.
 */
interface VisitDraftRepository {
    fun observeHasDraft(placeId: String): Flow<Boolean>
    suspend fun getDraft(placeId: String): VisitDraft?
    suspend fun hasDraft(placeId: String): Boolean

    /**
     * Persists when [ownerUserId] matches the current session.
     * No-ops on logout / account switch mismatch.
     */
    suspend fun saveDraft(placeId: String, draft: VisitDraft, ownerUserId: String)

    suspend fun deleteDraft(placeId: String, ownerUserId: String)
    suspend fun deleteExpiredDrafts()

    /** Session-only photos for drafts recovered from pending payloads (not in Room). */
    suspend fun attachSessionPhotos(placeId: String, photos: List<String>, ownerUserId: String)

    companion object {
        const val EXPIRY_MS: Long = 30L * 24 * 60 * 60 * 1000
        const val AUTOSAVE_DEBOUNCE_MS: Long = 400L
    }
}
