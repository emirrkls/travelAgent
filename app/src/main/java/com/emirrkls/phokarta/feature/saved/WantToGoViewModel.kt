package com.emirrkls.phokarta.feature.saved

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.feature.search.SearchLogic
import com.emirrkls.phokarta.feature.search.SearchSort
import com.emirrkls.phokarta.ui.presentation.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WantToGoUiState(
    val query: String = "",
    val category: PlaceCategory? = null,
    val destination: String? = null,
    val highlyRatedOnly: Boolean = false,
    val sort: SearchSort = SearchSort.RECENTLY_SAVED,
    val places: List<Place> = emptyList(),
    val destinations: List<String> = emptyList(),
    val totalCount: Int = 0,
    val saveErrorMessage: String? = null,
)

private data class WantToGoFilterState(
    val query: String,
    val category: PlaceCategory?,
    val destination: String?,
    val highlyRatedOnly: Boolean,
    val sort: SearchSort,
)

@HiltViewModel
class WantToGoViewModel @Inject constructor(
    private val repository: TravelRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<PlaceCategory?>(null)
    private val destination = MutableStateFlow<String?>(null)
    private val highlyRatedOnly = MutableStateFlow(false)
    private val sort = MutableStateFlow(SearchSort.RECENTLY_SAVED)
    private val saveError = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            repository.refreshSaved()
            repository.refreshCatalog()
        }
    }

    private val filterState = combine(query, category, destination, highlyRatedOnly, sort) {
            q, cat, dest, rated, selectedSort ->
        WantToGoFilterState(q, cat, dest, rated, selectedSort)
    }

    val uiState = combine(
        repository.observeSavedPlaceIds(),
        repository.observePlaces(),
        filterState,
        saveError,
    ) { savedIds, catalog, filters, error ->
        val savedOrder = savedIds.toList()
        val savedPlaces = savedOrder.mapNotNull { id -> catalog.firstOrNull { it.id == id } }
        val destinations = savedPlaces.map { it.city }.distinct().sorted()
        var filtered = savedPlaces
        filters.category?.let { cat -> filtered = filtered.filter { it.category == cat } }
        filters.destination?.let { city -> filtered = filtered.filter { it.city.equals(city, ignoreCase = true) } }
        if (filters.highlyRatedOnly) {
            filtered = filtered.filter {
                (it.communityScore ?: Double.NEGATIVE_INFINITY) >= SearchLogic.HIGHLY_RATED_MIN
            }
        }
        val needle = filters.query.trim()
        if (needle.isNotEmpty()) {
            filtered = filtered.filter { SearchLogic.matchesQuery(it, needle) }
        }
        filtered = SearchLogic.sort(filtered, filters.sort, savedOrder)

        WantToGoUiState(
            query = filters.query,
            category = filters.category,
            destination = filters.destination,
            highlyRatedOnly = filters.highlyRatedOnly,
            sort = filters.sort,
            places = filtered,
            destinations = destinations,
            totalCount = savedPlaces.size,
            saveErrorMessage = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WantToGoUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun setCategory(value: PlaceCategory?) {
        category.value = value
    }

    fun setDestination(value: String?) {
        destination.value = value
    }

    fun toggleHighlyRated() {
        highlyRatedOnly.update { !it }
    }

    fun setSort(value: SearchSort) {
        sort.value = value
    }

    fun clearFilters() {
        category.value = null
        destination.value = null
        highlyRatedOnly.value = false
        sort.value = SearchSort.RECENTLY_SAVED
        query.value = ""
    }

    fun toggleSaved(placeId: String) {
        viewModelScope.launch {
            when (val result = repository.toggleSaved(placeId)) {
                is RepositoryResult.Failure -> saveError.value = result.error.toUserMessage()
                is RepositoryResult.Success -> saveError.value = null
            }
        }
    }

    fun dismissSaveError() {
        saveError.value = null
    }
}
