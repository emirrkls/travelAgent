package com.emirrkls.phokarta.feature.saved

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.feature.search.SearchSort
import com.emirrkls.phokarta.ui.presentation.toUserMessageRes
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
    val friendsVisitedOnly: Boolean = false,
    val sort: SearchSort = SearchSort.RECENTLY_SAVED,
    val places: List<WantToGoItem> = emptyList(),
    val destinations: List<String> = emptyList(),
    val totalCount: Int = 0,
    val friendCount: Long? = null,
    val saveErrorMessage: Int? = null,
)

private data class WantToGoFilterState(
    val query: String,
    val category: PlaceCategory?,
    val destination: String?,
    val highlyRatedOnly: Boolean,
    val friendsVisitedOnly: Boolean,
    val sort: SearchSort,
    val saveErrorMessage: Int?,
    val friendCount: Long?,
)

@HiltViewModel
class WantToGoViewModel @Inject constructor(
    private val repository: TravelRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<PlaceCategory?>(null)
    private val destination = MutableStateFlow<String?>(null)
    private val highlyRatedOnly = MutableStateFlow(false)
    private val friendsVisitedOnly = MutableStateFlow(false)
    private val sort = MutableStateFlow(SearchSort.RECENTLY_SAVED)
    private val saveError = MutableStateFlow<Int?>(null)
    private val friendCount = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            repository.refreshSaved()
            repository.refreshCatalog()
            friendCount.value = when (val result = repository.loadOwnerSocialCounts()) {
                is RepositoryResult.Success -> result.value.friendCount
                is RepositoryResult.Failure -> null
            }
        }
    }

    private val extras = combine(saveError, friendCount, sort) { error, friends, selectedSort ->
        Triple(error, friends, selectedSort)
    }

    private val filterState = combine(query, category, destination, highlyRatedOnly, friendsVisitedOnly) {
            q, cat, dest, rated, friendsOnly ->
        WantToGoFilterState(
            query = q,
            category = cat,
            destination = dest,
            highlyRatedOnly = rated,
            friendsVisitedOnly = friendsOnly,
            sort = SearchSort.RECENTLY_SAVED,
            saveErrorMessage = null,
            friendCount = null,
        )
    }.combine(extras) { filters, extra ->
        filters.copy(
            sort = extra.third,
            saveErrorMessage = extra.first,
            friendCount = extra.second,
        )
    }

    val uiState = combine(
        repository.observeSavedPlaceIds(),
        repository.observePlaces(),
        repository.observeSavedFriendMetrics(),
        filterState,
    ) { savedIds, catalog, metrics, filters ->
        val savedOrder = savedIds.toList()
        val savedPlaces = savedOrder.mapNotNull { id -> catalog.firstOrNull { it.id == id } }
        val destinations = savedPlaces.map { it.city }.distinct().sorted()
        val filtered = WantToGoLogic.filterAndSort(
            savedPlaces = savedPlaces,
            savedOrder = savedOrder,
            friendMetrics = metrics,
            query = filters.query,
            category = filters.category,
            destination = filters.destination,
            highlyRatedOnly = filters.highlyRatedOnly,
            friendsVisitedOnly = filters.friendsVisitedOnly,
            sort = filters.sort,
        )
        WantToGoUiState(
            query = filters.query,
            category = filters.category,
            destination = filters.destination,
            highlyRatedOnly = filters.highlyRatedOnly,
            friendsVisitedOnly = filters.friendsVisitedOnly,
            sort = filters.sort,
            places = filtered,
            destinations = destinations,
            totalCount = savedPlaces.size,
            friendCount = filters.friendCount,
            saveErrorMessage = filters.saveErrorMessage,
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

    fun toggleFriendsVisited() {
        friendsVisitedOnly.update { !it }
    }

    fun setSort(value: SearchSort) {
        sort.value = value
    }

    fun clearFilters() {
        category.value = null
        destination.value = null
        highlyRatedOnly.value = false
        friendsVisitedOnly.value = false
        sort.value = SearchSort.RECENTLY_SAVED
        query.value = ""
    }

    fun toggleSaved(placeId: String) {
        viewModelScope.launch {
            when (val result = repository.toggleSaved(placeId)) {
                is RepositoryResult.Failure -> saveError.value = result.error.toUserMessageRes()
                is RepositoryResult.Success -> saveError.value = null
            }
        }
    }

    fun dismissSaveError() {
        saveError.value = null
    }
}
