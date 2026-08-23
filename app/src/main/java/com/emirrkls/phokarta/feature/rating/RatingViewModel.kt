package com.emirrkls.phokarta.feature.rating

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.VisitStateLogic
import com.emirrkls.phokarta.ui.presentation.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RatingUiState(
    val place: Place? = null,
    val draft: VisitDraft = VisitDraft(),
    val isPublishing: Boolean = false,
    val published: Boolean = false,
    val publishError: String? = null,
    val dateError: String? = null,
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val isNotFound: Boolean = false,
    val hasExistingVisits: Boolean = false,
    val existingVisitCount: Int = 0,
) {
    val overall: Float get() = draft.overallScore
    val dimensions: Map<RatingDimension, Float> get() = draft.dimensions
    val review: String get() = draft.publicReview
    val note: String get() = draft.privateMemory
    val visitedAt: LocalDate get() = draft.visitDate
    val dimensionsExpanded: Boolean get() = draft.dimensionsExpanded
    val canPublish: Boolean get() = VisitDraftLogic.canPublish(draft) && !isPublishing
}

@HiltViewModel
class RatingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TravelRepository,
) : ViewModel() {
    private val placeId: String = checkNotNull(savedStateHandle["placeId"])
    private val _uiState = MutableStateFlow(RatingUiState())
    val uiState = _uiState.asStateFlow()
    private var publishInFlight = false

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
    }

    fun retryLoad() = load()

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadError = null, isNotFound = false) }
            when (val result = repository.refreshPlaceDetail(placeId)) {
                is RepositoryResult.Success -> _uiState.update { it.copy(place = result.value, isLoading = false) }
                is RepositoryResult.Failure -> {
                    val cached = repository.observePlaces().first().firstOrNull { it.id == placeId }
                    _uiState.update {
                        it.copy(
                            place = cached,
                            isLoading = false,
                            loadError = result.error.toUserMessage(),
                            isNotFound = result.error is TravelError.NotFound && cached == null,
                        )
                    }
                }
            }
        }
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

    fun setVisitedAt(value: LocalDate) {
        val error = VisitDraftLogic.validateDate(value)
        updateDraft { it.copy(visitDate = value) }
        _uiState.update { it.copy(dateError = error) }
    }

    fun resetVisitedAtToToday() = setVisitedAt(LocalDate.now())

    fun publish() {
        val state = _uiState.value
        if (state.place == null || !state.canPublish || publishInFlight) return
        publishInFlight = true
        _uiState.update { it.copy(isPublishing = true, publishError = null) }
        viewModelScope.launch {
            val result = repository.publishVisit(
                VisitDraftLogic.toVisit(
                    draft = state.draft,
                    placeId = placeId,
                    userId = repository.currentUser.id,
                ),
            )
            when (result) {
                is RepositoryResult.Success -> {
                    repository.refreshOwnerVisits()
                    _uiState.update { it.copy(published = true) }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(publishError = result.error.toUserMessage()) }
                }
            }
            publishInFlight = false
            _uiState.update { it.copy(isPublishing = false) }
        }
    }

    private fun updateDraft(transform: (VisitDraft) -> VisitDraft) {
        _uiState.update { state ->
            val nextDraft = transform(state.draft)
            state.copy(
                draft = nextDraft,
                dateError = VisitDraftLogic.validateDate(nextDraft.visitDate),
            )
        }
    }
}
