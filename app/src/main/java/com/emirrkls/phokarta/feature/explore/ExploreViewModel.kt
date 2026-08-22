package com.emirrkls.phokarta.feature.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExploreUiState(
    val places: List<Place> = emptyList(),
    val selectedCategory: PlaceCategory? = null,
    val savedPlaceIds: Set<String> = emptySet(),
) {
    val filteredPlaces: List<Place> get() = selectedCategory?.let { category -> places.filter { it.category == category } } ?: places
}

@HiltViewModel
class ExploreViewModel @Inject constructor(private val repository: TravelRepository) : ViewModel() {
    private val selectedCategory = MutableStateFlow<PlaceCategory?>(null)
    val uiState = combine(repository.observePlaces(), selectedCategory, repository.observeSavedPlaceIds()) { places, category, saved ->
        ExploreUiState(places, category, saved)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExploreUiState())

    fun selectCategory(category: PlaceCategory?) { selectedCategory.value = category }
    fun toggleSaved(placeId: String) { viewModelScope.launch { repository.toggleSaved(placeId) } }
}
