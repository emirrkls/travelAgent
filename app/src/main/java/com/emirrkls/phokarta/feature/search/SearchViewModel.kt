package com.emirrkls.phokarta.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.ui.presentation.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val category: PlaceCategory? = null,
    val results: List<Place> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val page: Int = 0,
    val totalPages: Int = 0,
    val totalElements: Long = 0,
    val hasNext: Boolean = false,
)

@HiltViewModel
@OptIn(kotlinx.coroutines.FlowPreview::class)
class SearchViewModel @Inject constructor(private val repository: TravelRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<PlaceCategory?>(null)
    private val retryToken = MutableStateFlow(0)
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(query, category, retryToken) { text, selected, _ -> text to selected }
                .debounce(300)
                .collectLatest { (text, selected) -> search(text, selected) }
        }
    }

    fun setQuery(value: String) {
        query.value = value
        _uiState.update { it.copy(query = value, errorMessage = null) }
    }

    fun setCategory(value: PlaceCategory?) {
        category.value = value
        _uiState.update { it.copy(category = value, errorMessage = null) }
    }

    fun retry() {
        retryToken.value += 1
    }

    private suspend fun search(text: String, selected: PlaceCategory?) {
        _uiState.update { it.copy(query = text, category = selected, isLoading = true, errorMessage = null) }
        when (val result = repository.listPlaces(search = text.trim().ifBlank { null }, category = selected, page = 0, size = 30)) {
            is RepositoryResult.Success -> _uiState.update {
                it.copy(
                    results = result.value.places,
                    isLoading = false,
                    page = result.value.page,
                    totalPages = result.value.totalPages,
                    totalElements = result.value.totalElements,
                    hasNext = result.value.hasNext,
                )
            }
            is RepositoryResult.Failure -> _uiState.update {
                it.copy(isLoading = false, errorMessage = result.error.toUserMessage())
            }
        }
    }
}
