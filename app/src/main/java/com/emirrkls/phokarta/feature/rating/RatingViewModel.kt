package com.emirrkls.phokarta.feature.rating

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.ui.presentation.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

data class RatingUiState(
    val place: Place? = null,
    val overall: Float = 8f,
    val dimensions: Map<RatingDimension, Float> = emptyMap(),
    val review: String = "",
    val note: String = "",
    val visitedAt: LocalDate = LocalDate.now(),
    val isPublishing: Boolean = false,
    val published: Boolean = false,
    val publishError: String? = null,
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val isNotFound: Boolean = false,
)

@HiltViewModel
class RatingViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val repository: TravelRepository) : ViewModel() {
    private val placeId: String = checkNotNull(savedStateHandle["placeId"])
    private val _uiState = MutableStateFlow(RatingUiState())
    val uiState = _uiState.asStateFlow()
    init { load() }
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
    fun setOverall(value: Float) = _uiState.update { it.copy(overall = value.roundToTenth()) }
    fun enableDimension(name: RatingDimension) = _uiState.update { state ->
        if (name in state.dimensions) state else state.copy(dimensions = state.dimensions + (name to state.overall))
    }
    fun setDimension(name: RatingDimension, value: Float) = _uiState.update { it.copy(dimensions = it.dimensions + (name to value.roundToTenth())) }
    fun removeDimension(name: RatingDimension) = _uiState.update { it.copy(dimensions = it.dimensions - name) }
    fun setReview(value: String) = _uiState.update { it.copy(review = value) }
    fun setNote(value: String) = _uiState.update { it.copy(note = value) }
    fun setVisitedAt(value: LocalDate) = _uiState.update { it.copy(visitedAt = value) }
    fun publish() {
        val state = _uiState.value
        if (state.place == null || state.isPublishing) return
        _uiState.update { it.copy(isPublishing = true, publishError = null) }
        viewModelScope.launch {
            val result = repository.publishVisit(
                Visit(
                    UUID.randomUUID().toString(),
                    repository.currentUser.id,
                    placeId,
                    state.visitedAt,
                    state.overall.toDouble(),
                    state.dimensions.mapValues { it.value.toDouble() },
                    state.review.trim(),
                    state.note.trim(),
                ),
            )
            when (result) {
                is RepositoryResult.Success -> _uiState.update { it.copy(published = true) }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(publishError = result.error.toUserMessage()) }
                }
            }
            _uiState.update { it.copy(isPublishing = false) }
        }
    }
}

private fun Float.roundToTenth(): Float = (this * 10).roundToInt() / 10f
