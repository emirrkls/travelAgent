package com.emirrkls.phokarta.feature.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.ActivityFeedInvalidator
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.ActivityEvent
import com.emirrkls.phokarta.core.model.ActivityScope
import com.emirrkls.phokarta.ui.presentation.toUserMessageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FriendsEmptyReason {
    NONE,
    NO_FRIENDS,
    NO_ACTIVITY,
}

data class ScopeFeedUiState(
    val items: List<ActivityEvent> = emptyList(),
    val isLoadingInitial: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: Int? = null,
    val loadMoreErrorMessage: Int? = null,
    val hasNext: Boolean = false,
    val expandedReviewIds: Set<String> = emptySet(),
    val friendsEmptyReason: FriendsEmptyReason = FriendsEmptyReason.NONE,
    val hasLoaded: Boolean = false,
)

data class ActivityUiState(
    val activeScope: ActivityScope = ActivityScope.COMMUNITY,
    val community: ScopeFeedUiState = ScopeFeedUiState(isLoadingInitial = true),
    val friends: ScopeFeedUiState = ScopeFeedUiState(),
    val currentUserId: String,
) {
    val activeFeed: ScopeFeedUiState
        get() = if (activeScope == ActivityScope.FRIENDS) friends else community

    val items: List<ActivityEvent> get() = activeFeed.items
    val isLoadingInitial: Boolean get() = activeFeed.isLoadingInitial
    val isLoadingMore: Boolean get() = activeFeed.isLoadingMore
    val isRefreshing: Boolean get() = activeFeed.isRefreshing
    val errorMessage: Int? get() = activeFeed.errorMessage
    val loadMoreErrorMessage: Int? get() = activeFeed.loadMoreErrorMessage
    val hasNext: Boolean get() = activeFeed.hasNext
    val expandedReviewIds: Set<String> get() = activeFeed.expandedReviewIds
    val friendsEmptyReason: FriendsEmptyReason get() = activeFeed.friendsEmptyReason
}

@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val repository: TravelRepository,
    private val feedInvalidator: ActivityFeedInvalidator,
) : ViewModel() {
    private val status = MutableStateFlow(
        FeedBundle(
            community = FeedStatus(isLoadingInitial = true),
        ),
    )

    val uiState = status.map { bundle ->
        ActivityUiState(
            activeScope = bundle.activeScope,
            community = bundle.community.toUi(),
            friends = bundle.friends.toUi(),
            currentUserId = repository.currentUser.id,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ActivityUiState(currentUserId = repository.currentUser.id),
    )

    init {
        loadInitial(ActivityScope.COMMUNITY)
    }

    fun selectScope(scope: ActivityScope) {
        if (status.value.activeScope == scope) return
        status.update { it.copy(activeScope = scope) }
        val feed = status.value.feed(scope)
        if (!feed.hasLoaded && !feed.isLoadingInitial) {
            loadInitial(scope)
        }
    }

    fun onScreenResumed() {
        if (feedInvalidator.consume()) {
            refresh(ActivityScope.COMMUNITY)
        }
    }

    fun retry() = loadInitial(status.value.activeScope)

    fun refresh() = refresh(status.value.activeScope)

    fun loadNextPage() {
        val scope = status.value.activeScope
        val current = status.value.feed(scope)
        if (current.isLoadingInitial || current.isLoadingMore || current.isRefreshing || !current.hasNext) {
            return
        }
        val nextPage = current.nextPage
        updateFeed(scope) { it.copy(isLoadingMore = true, loadMoreErrorMessage = null) }
        viewModelScope.launch {
            when (val result = repository.loadActivityPage(scope = scope, page = nextPage, size = PAGE_SIZE)) {
                is RepositoryResult.Success -> {
                    val existingIds = current.items.map { it.visitId }.toSet()
                    val merged = current.items + result.value.items.filter { it.visitId !in existingIds }
                    updateFeed(scope) {
                        it.copy(
                            items = merged,
                            hasNext = result.value.hasNext,
                            nextPage = nextPage + 1,
                            isLoadingMore = false,
                            loadMoreErrorMessage = null,
                        )
                    }
                }
                is RepositoryResult.Failure -> updateFeed(scope) {
                    it.copy(isLoadingMore = false, loadMoreErrorMessage = result.error.toUserMessageRes())
                }
            }
        }
    }

    fun retryLoadMore() = loadNextPage()

    fun toggleReviewExpanded(visitId: String) {
        val scope = status.value.activeScope
        updateFeed(scope) { state ->
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

    private fun refresh(scope: ActivityScope) {
        val current = status.value.feed(scope)
        if (current.isLoadingInitial || current.isRefreshing) return
        viewModelScope.launch {
            updateFeed(scope) {
                it.copy(isRefreshing = true, errorMessage = null, loadMoreErrorMessage = null)
            }
            when (val result = repository.loadActivityPage(scope = scope, page = 0, size = PAGE_SIZE)) {
                is RepositoryResult.Success -> {
                    val emptyReason = resolveFriendsEmptyReason(scope, result.value.items.isEmpty())
                    updateFeed(scope) {
                        FeedStatus(
                            items = result.value.items,
                            hasNext = result.value.hasNext,
                            nextPage = 1,
                            isRefreshing = false,
                            hasLoaded = true,
                            expandedReviewIds = it.expandedReviewIds,
                            friendsEmptyReason = emptyReason,
                        )
                    }
                }
                is RepositoryResult.Failure -> updateFeed(scope) {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = if (it.items.isEmpty()) result.error.toUserMessageRes() else null,
                        loadMoreErrorMessage = if (it.items.isNotEmpty()) result.error.toUserMessageRes() else null,
                    )
                }
            }
        }
    }

    private fun loadInitial(scope: ActivityScope) {
        viewModelScope.launch {
            updateFeed(scope) {
                FeedStatus(
                    isLoadingInitial = true,
                    errorMessage = null,
                    expandedReviewIds = it.expandedReviewIds,
                    hasLoaded = it.hasLoaded,
                )
            }
            when (val result = repository.loadActivityPage(scope = scope, page = 0, size = PAGE_SIZE)) {
                is RepositoryResult.Success -> {
                    val emptyReason = resolveFriendsEmptyReason(scope, result.value.items.isEmpty())
                    updateFeed(scope) {
                        FeedStatus(
                            items = result.value.items,
                            hasNext = result.value.hasNext,
                            nextPage = 1,
                            isLoadingInitial = false,
                            hasLoaded = true,
                            expandedReviewIds = it.expandedReviewIds,
                            friendsEmptyReason = emptyReason,
                        )
                    }
                }
                is RepositoryResult.Failure -> updateFeed(scope) {
                    FeedStatus(
                        isLoadingInitial = false,
                        errorMessage = result.error.toUserMessageRes(),
                        hasLoaded = true,
                        expandedReviewIds = it.expandedReviewIds,
                    )
                }
            }
        }
    }

    private suspend fun resolveFriendsEmptyReason(
        scope: ActivityScope,
        isEmpty: Boolean,
    ): FriendsEmptyReason {
        if (scope != ActivityScope.FRIENDS || !isEmpty) return FriendsEmptyReason.NONE
        return when (val counts = repository.loadOwnerSocialCounts()) {
            is RepositoryResult.Success -> {
                if (counts.value.friendCount <= 0) FriendsEmptyReason.NO_FRIENDS
                else FriendsEmptyReason.NO_ACTIVITY
            }
            is RepositoryResult.Failure -> {
                when (val friends = repository.loadFriends(page = 0, size = 1)) {
                    is RepositoryResult.Success -> {
                        if (friends.value.totalElements <= 0) FriendsEmptyReason.NO_FRIENDS
                        else FriendsEmptyReason.NO_ACTIVITY
                    }
                    is RepositoryResult.Failure -> FriendsEmptyReason.NO_ACTIVITY
                }
            }
        }
    }

    private fun updateFeed(scope: ActivityScope, transform: (FeedStatus) -> FeedStatus) {
        status.update { bundle ->
            when (scope) {
                ActivityScope.COMMUNITY -> bundle.copy(community = transform(bundle.community))
                ActivityScope.FRIENDS -> bundle.copy(friends = transform(bundle.friends))
            }
        }
    }

    private data class FeedBundle(
        val activeScope: ActivityScope = ActivityScope.COMMUNITY,
        val community: FeedStatus = FeedStatus(),
        val friends: FeedStatus = FeedStatus(),
    ) {
        fun feed(scope: ActivityScope): FeedStatus =
            if (scope == ActivityScope.FRIENDS) friends else community
    }

    private data class FeedStatus(
        val items: List<ActivityEvent> = emptyList(),
        val hasNext: Boolean = false,
        val nextPage: Int = 0,
        val isLoadingInitial: Boolean = false,
        val isLoadingMore: Boolean = false,
        val isRefreshing: Boolean = false,
        val errorMessage: Int? = null,
        val loadMoreErrorMessage: Int? = null,
        val expandedReviewIds: Set<String> = emptySet(),
        val friendsEmptyReason: FriendsEmptyReason = FriendsEmptyReason.NONE,
        val hasLoaded: Boolean = false,
    ) {
        fun toUi() = ScopeFeedUiState(
            items = items,
            isLoadingInitial = isLoadingInitial,
            isLoadingMore = isLoadingMore,
            isRefreshing = isRefreshing,
            errorMessage = errorMessage,
            loadMoreErrorMessage = loadMoreErrorMessage,
            hasNext = hasNext,
            expandedReviewIds = expandedReviewIds,
            friendsEmptyReason = friendsEmptyReason,
            hasLoaded = hasLoaded,
        )
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
