package com.emirrkls.travelagent.feature.secondary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.travelagent.core.data.TravelRepository
import com.emirrkls.travelagent.core.model.ActivityItem
import com.emirrkls.travelagent.core.model.Collection
import com.emirrkls.travelagent.core.model.Place
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SecondaryUiState(
    val collections: List<Collection> = emptyList(),
    val activity: List<ActivityItem> = emptyList(),
    val places: List<Place> = emptyList(),
    val savedPlaceIds: Set<String> = emptySet(),
)

@HiltViewModel
class SecondaryViewModel @Inject constructor(private val repository: TravelRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(SecondaryUiState())
    val uiState = _uiState.asStateFlow()
    init {
        viewModelScope.launch {
            combine(repository.observePlaces(), repository.observeSavedPlaceIds()) { places, saved -> places to saved }.collect { (places, saved) ->
                _uiState.value = _uiState.value.copy(
                    places = places,
                    savedPlaceIds = saved,
                    collections = repository.getCollections(),
                    activity = repository.getActivity(),
                )
            }
        }
    }
    fun toggleSaved(placeId: String) { viewModelScope.launch { repository.toggleSaved(placeId) } }
}
