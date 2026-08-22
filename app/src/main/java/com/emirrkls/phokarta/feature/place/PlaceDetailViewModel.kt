package com.emirrkls.phokarta.feature.place

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.Place
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaceDetailUiState(
    val place: Place? = null,
    val isSaved: Boolean = false,
    val currentUserAvatarUrl: String,
)

@HiltViewModel
class PlaceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TravelRepository,
) : ViewModel() {
    private val place = MutableStateFlow<Place?>(null)
    private val placeId: String = checkNotNull(savedStateHandle["placeId"])
    val uiState = combine(place, repository.observeSavedPlaceIds()) { current, saved ->
        PlaceDetailUiState(current, placeId in saved, repository.currentUser.avatarUrl)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlaceDetailUiState(currentUserAvatarUrl = repository.currentUser.avatarUrl),
    )
    init { viewModelScope.launch { place.value = repository.getPlace(placeId) } }
    fun toggleSaved() { viewModelScope.launch { repository.toggleSaved(placeId) } }
}
