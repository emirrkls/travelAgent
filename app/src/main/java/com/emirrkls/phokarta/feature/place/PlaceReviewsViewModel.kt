package com.emirrkls.phokarta.feature.place

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PublicReview
import com.emirrkls.phokarta.ui.presentation.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaceReviewsUiState(
    val place: Place? = null,
    val reviews: List<PublicReview> = emptyList(),
    val totalElements: Long = 0,
    val hasNext: Boolean = false,
    val isLoadingInitial: Boolean = true,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val loadMoreErrorMessage: String? = null,
    val currentUserId: String,
    val expandedReviewIds: Set<String> = emptySet(),
)

@HiltViewModel
class PlaceReviewsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TravelRepository,
) : ViewModel() {
    private val placeId: String = checkNotNull(savedStateHandle["placeId"])
    private val place = MutableStateFlow<Place?>(null)
    private val status = MutableStateFlow(ReviewsStatus())

    val uiState = combine(place, status) { currentPlace, reviewsStatus ->
        PlaceReviewsUiState(
            place = currentPlace,
            reviews = reviewsStatus.reviews,
            totalElements = reviewsStatus.totalElements,
            hasNext = reviewsStatus.hasNext,
            isLoadingInitial = reviewsStatus.isLoadingInitial,
            isLoadingMore = reviewsStatus.isLoadingMore,
            errorMessage = reviewsStatus.errorMessage,
            loadMoreErrorMessage = reviewsStatus.loadMoreErrorMessage,
            currentUserId = repository.currentUser.id,
            expandedReviewIds = reviewsStatus.expandedReviewIds,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlaceReviewsUiState(currentUserId = repository.currentUser.id),
    )

    init {
        loadPlace()
        loadInitialReviews()
    }

    fun retry() {
        if (place.value == null) loadPlace()
        loadInitialReviews()
    }

    fun retryLoadMore() = loadNextPage()

    fun loadNextPage() {
        val current = status.value
        if (current.isLoadingInitial || current.isLoadingMore || !current.hasNext) return
        viewModelScope.launch {
            status.update { it.copy(isLoadingMore = true, loadMoreErrorMessage = null) }
            val nextPage = current.nextPage
            when (val result = repository.refreshPublicReviews(placeId, page = nextPage, size = PAGE_SIZE)) {
                is RepositoryResult.Success -> {
                    val existingIds = current.reviews.map { it.id }.toSet()
                    val merged = current.reviews + result.value.reviews.filter { it.id !in existingIds }
                    status.update {
                        it.copy(
                            reviews = merged,
                            totalElements = result.value.totalElements,
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

    fun toggleReviewExpanded(reviewId: String) {
        status.update { state ->
            val expanded = reviewId in state.expandedReviewIds
            state.copy(
                expandedReviewIds = if (expanded) state.expandedReviewIds - reviewId else state.expandedReviewIds + reviewId,
            )
        }
    }

    private fun loadPlace() {
        viewModelScope.launch {
            when (val result = repository.refreshPlaceDetail(placeId)) {
                is RepositoryResult.Success -> place.value = result.value
                is RepositoryResult.Failure -> place.value = repository.getPlace(placeId)
            }
        }
    }

    private fun loadInitialReviews() {
        if (status.value.isLoadingInitial && status.value.reviews.isNotEmpty()) return
        viewModelScope.launch {
            status.update {
                ReviewsStatus(isLoadingInitial = true, errorMessage = null, nextPage = 0)
            }
            when (val result = repository.refreshPublicReviews(placeId, page = 0, size = PAGE_SIZE)) {
                is RepositoryResult.Success -> status.update {
                    ReviewsStatus(
                        reviews = result.value.reviews,
                        totalElements = result.value.totalElements,
                        hasNext = result.value.hasNext,
                        nextPage = 1,
                        isLoadingInitial = false,
                    )
                }
                is RepositoryResult.Failure -> status.update {
                    ReviewsStatus(
                        isLoadingInitial = false,
                        errorMessage = result.error.toUserMessage(),
                    )
                }
            }
        }
    }

    private data class ReviewsStatus(
        val reviews: List<PublicReview> = emptyList(),
        val totalElements: Long = 0,
        val hasNext: Boolean = false,
        val nextPage: Int = 0,
        val isLoadingInitial: Boolean = false,
        val isLoadingMore: Boolean = false,
        val errorMessage: String? = null,
        val loadMoreErrorMessage: String? = null,
        val expandedReviewIds: Set<String> = emptySet(),
    )

    companion object {
        const val PAGE_SIZE = 20
    }
}
