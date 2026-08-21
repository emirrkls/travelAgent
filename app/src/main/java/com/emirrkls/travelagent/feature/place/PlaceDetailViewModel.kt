package com.emirrkls.travelagent.feature.place

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.travelagent.core.data.TravelRepository
import com.emirrkls.travelagent.core.model.Place
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaceDetailUiState(val place: Place? = null, val isSaved: Boolean = false)

@HiltViewModel
class PlaceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TravelRepository,
) : ViewModel() {
    private val place = MutableStateFlow<Place?>(null)
    private val placeId: String = checkNotNull(savedStateHandle["placeId"])
    val uiState = combine(place, repository.observeSavedPlaceIds()) { current, saved -> PlaceDetailUiState(current, placeId in saved) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaceDetailUiState())
    init { viewModelScope.launch { place.value = repository.getPlace(placeId) } }
    fun toggleSaved() { viewModelScope.launch { repository.toggleSaved(placeId) } }
}
