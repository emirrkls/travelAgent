package com.emirrkls.phokarta.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.ui.presentation.toUserMessageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    val results: List<Place> = emptyList(),
    val savedPlaceIds: Set<String> = emptySet(),
    val visitedPlaceIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: Int? = null,
    val saveErrorMessage: Int? = null,
    val page: Int = 0,
    val totalPages: Int = 0,
    val totalElements: Long = 0,
    val hasNext: Boolean = false,
    val emptyReason: SearchEmptyReason? = null,
) {
    val category: PlaceCategory? get() = filters.category
}

private data class RemoteSearchKey(
    val query: String,
    val category: PlaceCategory?,
    val highlyRatedOnly: Boolean,
    val sort: SearchSort,
    val retry: Int,
)

@HiltViewModel
@OptIn(FlowPreview::class)
class SearchViewModel @Inject constructor(private val repository: TravelRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filters = MutableStateFlow(SearchFilters())
    private val retryToken = MutableStateFlow(0)
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState = _uiState.asStateFlow()

    private var lastRemoteKey: RemoteSearchKey? = null
    private var lastRemoteResults: List<Place> = emptyList()

    init {
        viewModelScope.launch {
            val remoteInputs = combine(query.debounce(300), filters, retryToken) { text, selected, retry ->
                Triple(text, selected, retry)
            }
            val localInputs = combine(
                repository.observeSavedPlaceIds(),
                repository.observeVisits().map { visits -> visits.map { it.placeId }.toSet() },
                repository.observePlaces(),
            ) { saved, visited, catalog ->
                Triple(saved, visited, catalog)
            }
            combine(remoteInputs, localInputs) { remote, local ->
                SearchSnapshot(
                    query = remote.first,
                    filters = remote.second,
                    retry = remote.third,
                    savedOrder = local.first.toList(),
                    savedIds = local.first,
                    visitedIds = local.second,
                    catalog = local.third,
                )
            }.collectLatest { snapshot ->
                render(snapshot)
            }
        }
        viewModelScope.launch {
            repository.refreshSaved()
            repository.refreshOwnerVisits()
        }
    }

    fun setQuery(value: String) {
        query.value = value
        _uiState.update { it.copy(query = value, errorMessage = null) }
    }

    fun setCategory(value: PlaceCategory?) {
        filters.update { it.copy(category = value) }
        _uiState.update { it.copy(filters = filters.value, errorMessage = null) }
    }

    fun toggleSavedOnly() {
        filters.update { it.copy(savedOnly = !it.savedOnly) }
        _uiState.update { it.copy(filters = filters.value, errorMessage = null) }
    }

    fun toggleVisitedOnly() {
        filters.update { it.copy(visitedOnly = !it.visitedOnly) }
        _uiState.update { it.copy(filters = filters.value, errorMessage = null) }
    }

    fun toggleHighlyRated() {
        filters.update { it.copy(highlyRatedOnly = !it.highlyRatedOnly) }
        _uiState.update { it.copy(filters = filters.value, errorMessage = null) }
    }

    fun setSort(sort: SearchSort) {
        filters.update { it.copy(sort = sort) }
        _uiState.update { it.copy(filters = filters.value, errorMessage = null) }
    }

    fun clearFilters() {
        filters.value = SearchFilters()
        _uiState.update { it.copy(filters = SearchFilters(), errorMessage = null) }
    }

    fun retry() {
        retryToken.value += 1
    }

    fun toggleSaved(placeId: String) {
        viewModelScope.launch {
            when (val result = repository.toggleSaved(placeId)) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(saveErrorMessage = result.error.toUserMessageRes())
                }
                is RepositoryResult.Success -> _uiState.update { it.copy(saveErrorMessage = null) }
            }
        }
    }

    fun dismissSaveError() {
        _uiState.update { it.copy(saveErrorMessage = null) }
    }

    private suspend fun render(snapshot: SearchSnapshot) {
        val keepLayout = _uiState.value.results.isNotEmpty()
        _uiState.update {
            it.copy(
                query = snapshot.query,
                filters = snapshot.filters,
                savedPlaceIds = snapshot.savedIds,
                visitedPlaceIds = snapshot.visitedIds,
                errorMessage = null,
                isLoading = !keepLayout && !SearchLogic.usesLocalSource(snapshot.filters),
            )
        }

        if (SearchLogic.usesLocalSource(snapshot.filters)) {
            lastRemoteKey = null
            val results = SearchLogic.filterAndSort(
                places = snapshot.catalog,
                query = snapshot.query,
                filters = snapshot.filters,
                savedOrder = snapshot.savedOrder,
                visitedPlaceIds = snapshot.visitedIds,
            )
            publishResults(
                results = results,
                page = 0,
                totalPages = 1,
                totalElements = results.size.toLong(),
                hasNext = false,
                errorMessage = null,
                filters = snapshot.filters,
                savedCount = snapshot.savedOrder.size,
                visitedCount = snapshot.visitedIds.size,
            )
            return
        }

        val remoteKey = RemoteSearchKey(
            query = snapshot.query.trim(),
            category = snapshot.filters.category,
            highlyRatedOnly = snapshot.filters.highlyRatedOnly,
            sort = snapshot.filters.sort,
            retry = snapshot.retry,
        )
        if (remoteKey != lastRemoteKey) {
            lastRemoteKey = remoteKey
            _uiState.update { it.copy(isLoading = !keepLayout) }
            when (
                val result = repository.listPlaces(
                    search = snapshot.query.trim().ifBlank { null },
                    category = snapshot.filters.category,
                    minRating = SearchLogic.serverMinRating(snapshot.filters),
                    sort = SearchLogic.serverSort(snapshot.filters.sort),
                    page = 0,
                    size = 30,
                )
            ) {
                is RepositoryResult.Success -> {
                    lastRemoteResults = result.value.places
                    val sorted = SearchLogic.sort(
                        lastRemoteResults,
                        snapshot.filters.sort,
                        snapshot.savedOrder,
                    )
                    publishResults(
                        results = sorted,
                        page = result.value.page,
                        totalPages = result.value.totalPages,
                        totalElements = result.value.totalElements,
                        hasNext = result.value.hasNext,
                        errorMessage = null,
                        filters = snapshot.filters,
                        savedCount = snapshot.savedOrder.size,
                        visitedCount = snapshot.visitedIds.size,
                    )
                }
                is RepositoryResult.Failure -> {
                    publishResults(
                        results = _uiState.value.results,
                        page = _uiState.value.page,
                        totalPages = _uiState.value.totalPages,
                        totalElements = _uiState.value.totalElements,
                        hasNext = _uiState.value.hasNext,
                        errorMessage = result.error.toUserMessageRes(),
                        filters = snapshot.filters,
                        savedCount = snapshot.savedOrder.size,
                        visitedCount = snapshot.visitedIds.size,
                    )
                }
            }
        } else {
            val sorted = SearchLogic.sort(lastRemoteResults, snapshot.filters.sort, snapshot.savedOrder)
            publishResults(
                results = sorted,
                page = _uiState.value.page,
                totalPages = _uiState.value.totalPages,
                totalElements = _uiState.value.totalElements,
                hasNext = _uiState.value.hasNext,
                errorMessage = _uiState.value.errorMessage,
                filters = snapshot.filters,
                savedCount = snapshot.savedOrder.size,
                visitedCount = snapshot.visitedIds.size,
            )
        }
    }

    private fun publishResults(
        results: List<Place>,
        page: Int,
        totalPages: Int,
        totalElements: Long,
        hasNext: Boolean,
        errorMessage: Int?,
        filters: SearchFilters,
        savedCount: Int,
        visitedCount: Int,
    ) {
        _uiState.update {
            it.copy(
                results = results,
                isLoading = false,
                page = page,
                totalPages = totalPages,
                totalElements = totalElements,
                hasNext = hasNext,
                errorMessage = errorMessage,
                emptyReason = SearchLogic.emptyReason(
                    results = results,
                    filters = filters,
                    savedCount = savedCount,
                    visitedCount = visitedCount,
                    isLoading = false,
                    hasError = errorMessage != null,
                ),
            )
        }
    }

    private data class SearchSnapshot(
        val query: String,
        val filters: SearchFilters,
        val retry: Int,
        val savedOrder: List<String>,
        val savedIds: Set<String>,
        val visitedIds: Set<String>,
        val catalog: List<Place>,
    )
}
