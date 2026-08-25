package com.emirrkls.phokarta

import com.emirrkls.phokarta.core.data.VisitDraftRepository
import com.emirrkls.phokarta.feature.rating.VisitDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [VisitDraftRepository] for JVM ViewModel tests.
 */
class FakeVisitDraftRepository(
    var activeUserId: String = "11111111-1111-1111-1111-111111111111",
    var nowMillis: Long = 1_000L,
) : VisitDraftRepository {
    data class Record(
        val draft: VisitDraft,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private val store = MutableStateFlow<Map<Pair<String, String>, Record>>(emptyMap())
    val saveCalls = mutableListOf<Pair<String, VisitDraft>>()
    val deleteCalls = mutableListOf<String>()

    override fun observeHasDraft(placeId: String): Flow<Boolean> =
        store.map { map ->
            val record = map[activeUserId to placeId] ?: return@map false
            !isExpired(record.updatedAt)
        }

    override suspend fun getDraft(placeId: String): VisitDraft? {
        val record = store.value[activeUserId to placeId] ?: return null
        if (isExpired(record.updatedAt)) {
            deleteRecord(activeUserId, placeId)
            return null
        }
        return record.draft
    }

    override suspend fun hasDraft(placeId: String): Boolean = getDraft(placeId) != null

    override suspend fun saveDraft(placeId: String, draft: VisitDraft, ownerUserId: String) {
        if (ownerUserId != activeUserId) return
        saveCalls += placeId to draft
        val key = ownerUserId to placeId
        val existing = store.value[key]
        val created = existing?.createdAt ?: nowMillis
        store.value = store.value + (key to Record(draft, created, nowMillis))
    }

    override suspend fun deleteDraft(placeId: String, ownerUserId: String) {
        if (ownerUserId != activeUserId) return
        deleteCalls += placeId
        deleteRecord(ownerUserId, placeId)
    }

    override suspend fun deleteExpiredDrafts() {
        val cutoff = nowMillis - VisitDraftRepository.EXPIRY_MS
        store.value = store.value.filterValues { it.updatedAt >= cutoff }
    }

    override suspend fun attachSessionPhotos(placeId: String, photos: List<String>, ownerUserId: String) = Unit

    fun seed(
        userId: String,
        placeId: String,
        draft: VisitDraft,
        updatedAt: Long = nowMillis,
        createdAt: Long = updatedAt,
    ) {
        store.value = store.value + ((userId to placeId) to Record(draft, createdAt, updatedAt))
    }

    fun records(): Map<Pair<String, String>, Record> = store.value

    private fun deleteRecord(userId: String, placeId: String) {
        store.value = store.value - (userId to placeId)
    }

    private fun isExpired(updatedAt: Long): Boolean =
        updatedAt < nowMillis - VisitDraftRepository.EXPIRY_MS
}
