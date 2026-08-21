package com.emirrkls.travelagent.feature.rating

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.travelagent.core.data.MockTravelRepository
import com.emirrkls.travelagent.core.data.TravelRepository
import com.emirrkls.travelagent.core.model.Place
import com.emirrkls.travelagent.core.model.Visit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

data class RatingUiState(
    val place: Place? = null,
    val overall: Float = 8f,
    val dimensions: Map<String, Float> = emptyMap(),
    val review: String = "",
    val note: String = "",
    val visitedAt: LocalDate = LocalDate.now(),
    val isPublishing: Boolean = false,
    val published: Boolean = false,
)

@HiltViewModel
class RatingViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val repository: TravelRepository) : ViewModel() {
    private val placeId: String = checkNotNull(savedStateHandle["placeId"])
    private val _uiState = MutableStateFlow(RatingUiState())
    val uiState = _uiState.asStateFlow()
    init {
        viewModelScope.launch {
            repository.getPlace(placeId)?.let { place -> _uiState.update { it.copy(place = place) } }
        }
    }
    fun setOverall(value: Float) = _uiState.update { it.copy(overall = value.roundToTenth()) }
    fun enableDimension(name: String) = _uiState.update { state ->
        if (name in state.dimensions) state else state.copy(dimensions = state.dimensions + (name to state.overall))
    }
    fun setDimension(name: String, value: Float) = _uiState.update { it.copy(dimensions = it.dimensions + (name to value.roundToTenth())) }
    fun removeDimension(name: String) = _uiState.update { it.copy(dimensions = it.dimensions - name) }
    fun setReview(value: String) = _uiState.update { it.copy(review = value) }
    fun setNote(value: String) = _uiState.update { it.copy(note = value) }
    fun setVisitedAt(value: LocalDate) = _uiState.update { it.copy(visitedAt = value) }
    fun publish() {
        val state = _uiState.value
        if (state.place == null || state.isPublishing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPublishing = true) }
            repository.publishVisit(
                Visit(UUID.randomUUID().toString(), MockTravelRepository.CURRENT_USER_ID, placeId, state.visitedAt,
                    state.overall.toDouble(), state.dimensions.mapValues { it.value.toDouble() }, state.review.trim(), state.note.trim())
            )
            _uiState.update { it.copy(isPublishing = false, published = true) }
        }
    }
}

private fun Float.roundToTenth(): Float = (this * 10).roundToInt() / 10f
