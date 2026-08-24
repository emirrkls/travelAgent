package com.emirrkls.phokarta.feature.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.User
import com.emirrkls.phokarta.ui.presentation.toUserMessageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExploreUiState(
    val places: List<Place> = emptyList(),
    val savedPlaces: List<Place> = emptyList(),
    val selectedCategory: PlaceCategory? = null,
    val savedPlaceIds: Set<String> = emptySet(),
    val visitedPlaceIds: Set<String> = emptySet(),
    val currentUser: User? = null,
    val isLoading: Boolean = true,
    val errorMessage: Int? = null,
) {
    val filteredPlaces: List<Place> get() = selectedCategory?.let { category -> places.filter { it.category == category } } ?: places
}

@HiltViewModel
class ExploreViewModel @Inject constructor(private val repository: TravelRepository) : ViewModel() {
    private val selectedCategory = MutableStateFlow<PlaceCategory?>(null)
    private val refreshState = MutableStateFlow(true to null as Int?)
    val uiState = combine(
        repository.observePlaces(),
        selectedCategory,
        repository.observeSavedPlaceIds(),
        repository.observeVisitedPlaceIds(),
        refreshState,
    ) { places, category, saved, visited, refresh ->
        val savedOrder = saved.toList()
        val byId = places.associateBy { it.id }
        ExploreUiState(
            places = places,
            savedPlaces = savedOrder.mapNotNull { byId[it] },
            selectedCategory = category,
            savedPlaceIds = saved,
            visitedPlaceIds = visited,
            currentUser = repository.currentUser,
            isLoading = refresh.first,
            errorMessage = refresh.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExploreUiState())

    init {
        refresh()
    }

    fun selectCategory(category: PlaceCategory?) { selectedCategory.value = category }
    fun toggleSaved(placeId: String) {
        viewModelScope.launch {
            val result = repository.toggleSaved(placeId)
            if (result is RepositoryResult.Failure) {
                refreshState.update { (loading, _) -> loading to result.error.toUserMessageRes() }
            }
        }
    }
    fun retry() = refresh()

    private fun refresh() {
        viewModelScope.launch {
            refreshState.value = true to null
            val result = repository.refreshCatalog()
            repository.refreshSaved()
            repository.refreshOwnerVisits()
            repository.refreshCollections()
            refreshState.value = false to ((result as? RepositoryResult.Failure)?.error?.toUserMessageRes())
        }
    }
}
