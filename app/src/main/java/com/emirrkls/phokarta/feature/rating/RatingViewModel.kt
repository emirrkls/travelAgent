package com.emirrkls.phokarta.feature.rating

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.data.VisitDraftRepository
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.model.VisitStateLogic
import com.emirrkls.phokarta.ui.presentation.toUserMessageRes
import com.emirrkls.phokarta.core.sync.NoOpOfflineMutationRepository
import com.emirrkls.phokarta.core.sync.OfflineMutationRepository
import com.emirrkls.phokarta.core.sync.MutationSyncEngine
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.media.MediaImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RatingUiState(
    val place: Place? = null,
    val draft: VisitDraft = VisitDraft(),
    val isPublishing: Boolean = false,
    val published: Boolean = false,
    val queuedForSync: Boolean = false,
    val publishError: Int? = null,
    val dateError: Int? = null,
    val isLoading: Boolean = true,
    val loadError: Int? = null,
    val isNotFound: Boolean = false,
    val hasExistingVisits: Boolean = false,
    val existingVisitCount: Int = 0,
    val isDraftInitializing: Boolean = true,
    val hasPersistedDraft: Boolean = false,
    val showDraftRestoredMessage: Boolean = false,
    val discarded: Boolean = false,
    val photoError: Int? = null,
) {
    val overall: Float get() = draft.overallScore
    val dimensions: Map<RatingDimension, Float> get() = draft.dimensions
    val review: String get() = draft.publicReview
    val note: String get() = draft.privateMemory
    val visitedAt: LocalDate get() = draft.visitDate
    val visibility: Visibility get() = draft.visibility
    val dimensionsExpanded: Boolean get() = draft.dimensionsExpanded
    val canPublish: Boolean get() =
        !isDraftInitializing && VisitDraftLogic.canPublish(draft) && !isPublishing
    val canDiscard: Boolean get() =
        !isDraftInitializing &&
            (VisitDraftLogic.hasMeaningfulContent(draft) || hasPersistedDraft)
}

@HiltViewModel
class RatingViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: TravelRepository,
    private val draftRepository: VisitDraftRepository,
    sessionManager: SessionManager,
    private val offlineMutations: OfflineMutationRepository = NoOpOfflineMutationRepository,
    private val immediateSyncEngine: MutationSyncEngine? = null,
) : ViewModel() {
    private val placeId: String = checkNotNull(savedStateHandle["placeId"])
    private val ownerUserId: String? = sessionManager.currentUserId()
    private val _uiState = MutableStateFlow(RatingUiState())
    val uiState = _uiState.asStateFlow()
    private var publishInFlight = false
    private var persistJob: Job? = null
    private var persistFrozen = false
    private var lastPersistedDraft: VisitDraft? = null
    private var hadPersistedDraft = false
    private var initialized = false
    private val photoMutationMutex = Mutex()

    init {
        load()
        viewModelScope.launch {
            repository.observeVisits().collect { visits ->
                val count = VisitStateLogic.visitCount(visits, placeId)
                _uiState.update {
                    it.copy(
                        hasExistingVisits = count > 0,
                        existingVisitCount = count,
                    )
                }
            }
        }
        viewModelScope.launch {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    flushDraftInternal()
                }
            }
        }
    }

    fun retryLoad() = load()

    private fun load() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isDraftInitializing = true,
                    loadError = null,
                    isNotFound = false,
                )
            }
            val persisted = draftRepository.getDraft(placeId)
            hadPersistedDraft = persisted != null
            lastPersistedDraft = persisted
            val restoredNoticePending = persisted != null &&
                savedStateHandle.get<Boolean>(KEY_RESTORED_NOTICE_SHOWN) != true
            when (val result = repository.refreshPlaceDetail(placeId)) {
                is RepositoryResult.Success -> {
                    _uiState.update {
                        it.copy(
                            place = result.value,
                            draft = persisted ?: VisitDraft(),
                            isLoading = false,
                            isDraftInitializing = false,
                            hasPersistedDraft = persisted != null,
                            showDraftRestoredMessage = restoredNoticePending,
                            dateError = VisitDraftLogic.validateDateRes(
                                (persisted ?: VisitDraft()).visitDate,
                            ),
                        )
                    }
                    initialized = true
                }
                is RepositoryResult.Failure -> {
                    val cached = repository.observePlaces().first().firstOrNull { it.id == placeId }
                    _uiState.update {
                        it.copy(
                            place = cached,
                            draft = persisted ?: VisitDraft(),
                            isLoading = false,
                            isDraftInitializing = false,
                            hasPersistedDraft = persisted != null,
                            showDraftRestoredMessage = restoredNoticePending && cached != null,
                            loadError = result.error.toUserMessageRes(),
                            isNotFound = result.error is TravelError.NotFound && cached == null,
                            dateError = VisitDraftLogic.validateDateRes(
                                (persisted ?: VisitDraft()).visitDate,
                            ),
                        )
                    }
                    initialized = true
                }
            }
        }
    }

    fun consumeDraftRestoredMessage() {
        savedStateHandle[KEY_RESTORED_NOTICE_SHOWN] = true
        _uiState.update { it.copy(showDraftRestoredMessage = false) }
    }

    fun setOverall(value: Float) = updateDraft { it.copy(overallScore = value.roundToTenth()) }

    fun toggleDimensionsExpanded() = updateDraft { it.copy(dimensionsExpanded = !it.dimensionsExpanded) }

    fun enableDimension(name: RatingDimension) = updateDraft { draft ->
        if (name in draft.dimensions) draft else draft.copy(dimensions = draft.dimensions + (name to draft.overallScore))
    }

    fun setDimension(name: RatingDimension, value: Float) = updateDraft {
        it.copy(dimensions = it.dimensions + (name to value.roundToTenth()))
    }

    fun removeDimension(name: RatingDimension) = updateDraft {
        it.copy(dimensions = it.dimensions - name)
    }

    fun setReview(value: String) = updateDraft { it.copy(publicReview = value) }

    fun setNote(value: String) = updateDraft { it.copy(privateMemory = value) }

    fun setVisibility(value: Visibility) = updateDraft { it.copy(visibility = value) }

    fun setVisitedAt(value: LocalDate) {
        val error = VisitDraftLogic.validateDateRes(value)
        updateDraft { it.copy(visitDate = value) }
        _uiState.update { it.copy(dateError = error) }
    }

    fun resetVisitedAtToToday() = setVisitedAt(LocalDate.now())

    fun addPhoto(uri: Uri) = addPhotos(listOf(uri))

    fun addPhotos(uris: List<Uri>) {
        val owner = ownerUserId ?: return
        if (uris.isEmpty() || _uiState.value.isPublishing || _uiState.value.isDraftInitializing) return
        viewModelScope.launch {
            photoMutationMutex.withLock {
                persistJob?.cancel()
                // The parent draft must exist before its FK-scoped photo rows.
                draftRepository.saveDraft(placeId, _uiState.value.draft, owner)
                for (uri in uris) {
                    when (val result = draftRepository.importPhoto(placeId, uri, owner)) {
                        is MediaImportResult.Success -> {
                            val next = _uiState.value.draft.copy(
                                photos = _uiState.value.draft.photos + result.photo.localRelativePath,
                            )
                            _uiState.update {
                                it.copy(draft = next, photoError = null, hasPersistedDraft = true)
                            }
                            lastPersistedDraft = next
                            hadPersistedDraft = true
                        }
                        MediaImportResult.MaxCount -> {
                            setPhotoError(R.string.photo_error_max_count)
                            break
                        }
                        MediaImportResult.UnsupportedType ->
                            setPhotoError(R.string.photo_error_unsupported_type)
                        MediaImportResult.TooLarge -> setPhotoError(R.string.photo_error_too_large)
                        MediaImportResult.Unreadable -> setPhotoError(R.string.photo_error_unreadable)
                    }
                }
            }
        }
    }

    fun removePhoto(relativePath: String) {
        val owner = ownerUserId ?: return
        viewModelScope.launch {
            photoMutationMutex.withLock {
                draftRepository.removePhoto(placeId, relativePath, owner)
                val next = _uiState.value.draft.copy(
                    photos = _uiState.value.draft.photos.filterNot { it == relativePath },
                )
                _uiState.update { it.copy(draft = next, photoError = null) }
                lastPersistedDraft = next
            }
        }
    }

    fun consumePhotoError() = _uiState.update { it.copy(photoError = null) }

    private fun setPhotoError(message: Int) = _uiState.update { it.copy(photoError = message) }

    fun flushDraft() {
        viewModelScope.launch { flushDraftInternal() }
    }

    fun discardDraft() {
        val owner = ownerUserId ?: return
        if (!_uiState.value.canDiscard) return
        persistFrozen = true
        persistJob?.cancel()
        persistJob = null
        viewModelScope.launch {
            draftRepository.deleteDraft(placeId, owner)
            lastPersistedDraft = null
            hadPersistedDraft = false
            savedStateHandle[KEY_RESTORED_NOTICE_SHOWN] = true
            _uiState.update {
                it.copy(
                    draft = VisitDraft(),
                    dateError = null,
                    publishError = null,
                    discarded = true,
                    hasPersistedDraft = false,
                    showDraftRestoredMessage = false,
                )
            }
            persistFrozen = false
        }
    }

    fun publish() {
        val state = _uiState.value
        if (state.place == null || !state.canPublish || publishInFlight) return
        publishInFlight = true
        persistFrozen = true
        persistJob?.cancel()
        persistJob = null
        val snapshot = state.draft
        _uiState.update { it.copy(isPublishing = true, publishError = null) }
        viewModelScope.launch {
            if (offlineMutations !== NoOpOfflineMutationRepository) {
                try {
                    val mutationId = offlineMutations.commitVisit(VisitDraftLogic.toVisit(
                        draft = snapshot,
                        placeId = placeId,
                        userId = repository.currentUser.id,
                    ))
                    lastPersistedDraft = null
                    hadPersistedDraft = false
                    withTimeoutOrNull(IMMEDIATE_SYNC_TIMEOUT_MS) {
                        immediateSyncEngine?.drain()
                    }
                    if (offlineMutations.mutationState(mutationId) == null) {
                        repository.refreshOwnerVisits()
                        _uiState.update { it.copy(published = true, hasPersistedDraft = false) }
                    } else {
                        _uiState.update { it.copy(queuedForSync = true, hasPersistedDraft = false) }
                    }
                } catch (_: Exception) {
                    persistFrozen = false
                    _uiState.update { it.copy(draft = snapshot, publishError = R.string.sync_queue_failed) }
                    schedulePersist(immediate = true)
                }
                publishInFlight = false
                _uiState.update { it.copy(isPublishing = false) }
                return@launch
            }
            val result = repository.publishVisit(
                VisitDraftLogic.toVisit(
                    draft = snapshot,
                    placeId = placeId,
                    userId = repository.currentUser.id,
                ),
            )
            when (result) {
                is RepositoryResult.Success -> {
                    repository.refreshOwnerVisits()
                    ownerUserId?.let { draftRepository.deleteDraft(placeId, it) }
                    lastPersistedDraft = null
                    hadPersistedDraft = false
                    _uiState.update { it.copy(published = true, hasPersistedDraft = false) }
                }
                is RepositoryResult.Failure -> {
                    persistFrozen = false
                    _uiState.update {
                        it.copy(
                            draft = snapshot,
                            publishError = result.error.toUserMessageRes(),
                        )
                    }
                    schedulePersist(immediate = true)
                }
            }
            publishInFlight = false
            _uiState.update { it.copy(isPublishing = false) }
        }
    }

    private fun updateDraft(transform: (VisitDraft) -> VisitDraft) {
        val current = _uiState.value
        if (current.isDraftInitializing || !initialized || current.isPublishing) return
        _uiState.update { state ->
            val nextDraft = transform(state.draft)
            state.copy(
                draft = nextDraft,
                dateError = VisitDraftLogic.validateDateRes(nextDraft.visitDate),
            )
        }
        schedulePersist(immediate = false)
    }

    private fun schedulePersist(immediate: Boolean) {
        if (persistFrozen || _uiState.value.isDraftInitializing || !initialized) return
        val owner = ownerUserId ?: return
        val draft = _uiState.value.draft
        if (draft == lastPersistedDraft) return
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            if (!immediate) delay(VisitDraftRepository.AUTOSAVE_DEBOUNCE_MS)
            persistNow(owner, draft = _uiState.value.draft)
        }
    }

    private suspend fun flushDraftInternal() {
        if (persistFrozen || _uiState.value.isDraftInitializing || !initialized) return
        val owner = ownerUserId ?: return
        persistJob?.cancel()
        persistJob = null
        persistNow(owner, draft = _uiState.value.draft)
    }

    private suspend fun persistNow(owner: String, draft: VisitDraft) {
        if (persistFrozen) return
        if (draft == lastPersistedDraft) return
        if (!VisitDraftLogic.hasMeaningfulContent(draft)) {
            if (lastPersistedDraft != null || hadPersistedDraft) {
                draftRepository.deleteDraft(placeId, owner)
                lastPersistedDraft = null
                hadPersistedDraft = false
                _uiState.update { it.copy(hasPersistedDraft = false) }
            }
            return
        }
        draftRepository.saveDraft(placeId, draft, owner)
        lastPersistedDraft = draft
        hadPersistedDraft = true
        _uiState.update { it.copy(hasPersistedDraft = true) }
    }

    companion object {
        private const val KEY_RESTORED_NOTICE_SHOWN = "draft_restored_notice_shown"
        private const val IMMEDIATE_SYNC_TIMEOUT_MS = 2_500L
    }
}
