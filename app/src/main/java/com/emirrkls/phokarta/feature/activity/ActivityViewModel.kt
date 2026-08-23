package com.emirrkls.phokarta.feature.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.ActivityFeedInvalidator
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.ActivityEvent
import com.emirrkls.phokarta.ui.presentation.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ActivityUiState(
    val items: List<ActivityEvent> = emptyList(),
    val isLoadingInitial: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val loadMoreErrorMessage: String? = null,
    val hasNext: Boolean = false,
    val currentUserId: String,
    val expandedReviewIds: Set<String> = emptySet(),
)

@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val repository: TravelRepository,
    private val feedInvalidator: ActivityFeedInvalidator,
) : ViewModel() {
    private val status = MutableStateFlow(FeedStatus(isLoadingInitial = true))

    val uiState = status.map { feed ->
        ActivityUiState(
            items = feed.items,
            isLoadingInitial = feed.isLoadingInitial,
            isLoadingMore = feed.isLoadingMore,
            isRefreshing = feed.isRefreshing,
            errorMessage = feed.errorMessage,
            loadMoreErrorMessage = feed.loadMoreErrorMessage,
            hasNext = feed.hasNext,
            currentUserId = repository.currentUser.id,
            expandedReviewIds = feed.expandedReviewIds,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ActivityUiState(currentUserId = repository.currentUser.id),
    )

    init {
        loadInitial()
    }

    fun onScreenResumed() {
        if (feedInvalidator.consume()) {
            refresh()
        }
    }

    fun retry() = loadInitial()

    fun refresh() {
        val current = status.value
        if (current.isLoadingInitial || current.isRefreshing) return
        viewModelScope.launch {
            status.update {
                it.copy(isRefreshing = true, errorMessage = null, loadMoreErrorMessage = null)
            }
            when (val result = repository.loadActivityPage(page = 0, size = PAGE_SIZE)) {
                is RepositoryResult.Success -> status.update {
                    FeedStatus(
                        items = result.value.items,
                        hasNext = result.value.hasNext,
                        nextPage = 1,
                        isRefreshing = false,
                        expandedReviewIds = it.expandedReviewIds,
                    )
                }
                is RepositoryResult.Failure -> status.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = if (it.items.isEmpty()) result.error.toUserMessage() else null,
                        loadMoreErrorMessage = if (it.items.isNotEmpty()) result.error.toUserMessage() else null,
                    )
                }
            }
        }
    }

    fun loadNextPage() {
        val current = status.value
        if (current.isLoadingInitial || current.isLoadingMore || current.isRefreshing || !current.hasNext) {
            return
        }
        val nextPage = current.nextPage
        status.update { it.copy(isLoadingMore = true, loadMoreErrorMessage = null) }
        viewModelScope.launch {
            when (val result = repository.loadActivityPage(page = nextPage, size = PAGE_SIZE)) {
                is RepositoryResult.Success -> {
                    val existingIds = current.items.map { it.visitId }.toSet()
                    val merged = current.items + result.value.items.filter { it.visitId !in existingIds }
                    status.update {
                        it.copy(
                            items = merged,
                            hasNext = result.value.hasNext,
                            nextPage = nextPage + 1,
                            isLoadingMore = false,
                            loadMoreErrorMessage = null,
                        )
                    }
                }
                is RepositoryResult.Failure -> status.update {
                    it.copy(isLoadingMore = false, loadMoreErrorMessage = result.error.toUserMessage())
                }
            }
        }
    }

    fun retryLoadMore() = loadNextPage()

    fun toggleReviewExpanded(visitId: String) {
        status.update { state ->
            val expanded = visitId in state.expandedReviewIds
            state.copy(
                expandedReviewIds = if (expanded) {
                    state.expandedReviewIds - visitId
                } else {
                    state.expandedReviewIds + visitId
                },
            )
        }
    }

    private fun loadInitial() {
        viewModelScope.launch {
            status.update {
                FeedStatus(isLoadingInitial = true, errorMessage = null, expandedReviewIds = it.expandedReviewIds)
            }
            when (val result = repository.loadActivityPage(page = 0, size = PAGE_SIZE)) {
                is RepositoryResult.Success -> status.update {
                    FeedStatus(
                        items = result.value.items,
                        hasNext = result.value.hasNext,
                        nextPage = 1,
                        isLoadingInitial = false,
                        expandedReviewIds = it.expandedReviewIds,
                    )
                }
                is RepositoryResult.Failure -> status.update {
                    FeedStatus(
                        isLoadingInitial = false,
                        errorMessage = result.error.toUserMessage(),
                        expandedReviewIds = it.expandedReviewIds,
                    )
                }
            }
        }
    }

    private data class FeedStatus(
        val items: List<ActivityEvent> = emptyList(),
        val hasNext: Boolean = false,
        val nextPage: Int = 0,
        val isLoadingInitial: Boolean = false,
        val isLoadingMore: Boolean = false,
        val isRefreshing: Boolean = false,
        val errorMessage: String? = null,
        val loadMoreErrorMessage: String? = null,
        val expandedReviewIds: Set<String> = emptySet(),
    )

    companion object {
        const val PAGE_SIZE = 20
    }
}
