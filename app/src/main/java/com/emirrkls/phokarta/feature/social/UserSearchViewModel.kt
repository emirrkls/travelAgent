package com.emirrkls.phokarta.feature.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.UserSummary
import com.emirrkls.phokarta.ui.presentation.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UserSearchUiState(
    val query: String = "",
    val items: List<UserSummary> = emptyList(),
    val initialLoading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val hasNext: Boolean = false,
    val page: Int = 0,
)

@HiltViewModel
@OptIn(FlowPreview::class)
class UserSearchViewModel @Inject constructor(
    private val repository: TravelRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val retryToken = MutableStateFlow(0)
    private val _uiState = MutableStateFlow(UserSearchUiState())
    val uiState = _uiState.asStateFlow()
    private var loadingMore = false

    init {
        viewModelScope.launch {
            combine(query.debounce(300), retryToken) { text, _ -> text.trim() }
                .collectLatest { text -> loadInitial(text) }
        }
    }

    fun setQuery(value: String) {
        query.value = value
        _uiState.update { it.copy(query = value, error = null) }
    }

    fun retry() {
        retryToken.update { it + 1 }
    }

    fun loadNextPage() {
        val state = _uiState.value
        if (!state.hasNext || state.loadingMore || state.initialLoading || loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true, error = null) }
            when (val result = repository.searchUsers(state.query.trim(), state.page + 1)) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(loadingMore = false, error = result.error.toUserMessage())
                }
                is RepositoryResult.Success -> _uiState.update {
                    it.copy(
                        loadingMore = false,
                        items = it.items + result.value.items,
                        page = result.value.page,
                        hasNext = result.value.hasNext,
                    )
                }
            }
            loadingMore = false
        }
    }

    private suspend fun loadInitial(text: String) {
        if (text.isEmpty()) {
            _uiState.update {
                it.copy(
                    items = emptyList(),
                    initialLoading = false,
                    loadingMore = false,
                    error = null,
                    hasNext = false,
                    page = 0,
                )
            }
            return
        }
        _uiState.update {
            it.copy(initialLoading = true, loadingMore = false, error = null, items = emptyList(), page = 0)
        }
        when (val result = repository.searchUsers(text, 0)) {
            is RepositoryResult.Failure -> _uiState.update {
                it.copy(initialLoading = false, error = result.error.toUserMessage(), items = emptyList())
            }
            is RepositoryResult.Success -> _uiState.update {
                it.copy(
                    initialLoading = false,
                    items = result.value.items,
                    page = result.value.page,
                    hasNext = result.value.hasNext,
                    error = null,
                )
            }
        }
    }
}
