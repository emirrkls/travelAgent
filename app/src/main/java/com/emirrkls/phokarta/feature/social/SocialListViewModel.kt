package com.emirrkls.phokarta.feature.social

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.RelationshipState
import com.emirrkls.phokarta.core.model.SocialListKind
import com.emirrkls.phokarta.core.model.UserSummary
import com.emirrkls.phokarta.ui.presentation.toUserMessageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SocialListUiState(
    val kind: SocialListKind,
    @StringRes val title: Int,
    val items: List<UserSummary> = emptyList(),
    val isLoading: Boolean = true,
    val loadingMore: Boolean = false,
    val mutatingUserIds: Set<String> = emptySet(),
    @StringRes val error: Int? = null,
    val hasNext: Boolean = false,
    val page: Int = 0,
)

@HiltViewModel
class SocialListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TravelRepository,
) : ViewModel() {
    private val kind = SocialListKind.fromRoute(checkNotNull(savedStateHandle["kind"]))
    private val _uiState = MutableStateFlow(
        SocialListUiState(kind = kind, title = titleFor(kind)),
    )
    val uiState = _uiState.asStateFlow()
    private var loadingMore = false

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = loadPage(0)) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, error = result.error.toUserMessageRes())
                }
                is RepositoryResult.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = result.value.items,
                        page = result.value.page,
                        hasNext = result.value.hasNext,
                        error = null,
                    )
                }
            }
        }
    }

    fun loadNextPage() {
        val state = _uiState.value
        if (!state.hasNext || state.loadingMore || state.isLoading || loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true, error = null) }
            when (val result = loadPage(state.page + 1)) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(loadingMore = false, error = result.error.toUserMessageRes())
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

    fun toggleFollow(userId: String) {
        val state = _uiState.value
        if (userId in state.mutatingUserIds) return
        val index = state.items.indexOfFirst { it.id == userId }
        if (index < 0) return
        val current = state.items[index]
        val relationship = current.relationship ?: RelationshipState(false, false)
        val wasFollowing = relationship.isFollowing
        val optimistic = current.copy(
            relationship = relationship.copy(
                isFollowing = !wasFollowing,
                isFriend = !wasFollowing && relationship.followsYou,
            ),
        )
        _uiState.update {
            it.copy(
                mutatingUserIds = it.mutatingUserIds + userId,
                items = it.items.toMutableList().also { list -> list[index] = optimistic },
                error = null,
            )
        }
        viewModelScope.launch {
            val result = if (wasFollowing) {
                repository.unfollowUser(userId)
            } else {
                repository.followUser(userId)
            }
            when (result) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(
                        mutatingUserIds = it.mutatingUserIds - userId,
                        items = it.items.toMutableList().also { list ->
                            val i = list.indexOfFirst { row -> row.id == userId }
                            if (i >= 0) list[i] = current
                        },
                        error = result.error.toUserMessageRes(),
                    )
                }
                is RepositoryResult.Success -> _uiState.update {
                    it.copy(mutatingUserIds = it.mutatingUserIds - userId)
                }
            }
        }
    }

    private suspend fun loadPage(page: Int) = when (kind) {
        SocialListKind.FOLLOWERS -> repository.loadFollowers(page)
        SocialListKind.FOLLOWING -> repository.loadFollowing(page)
        SocialListKind.FRIENDS -> repository.loadFriends(page)
    }

    @StringRes
    private fun titleFor(kind: SocialListKind): Int = when (kind) {
        SocialListKind.FOLLOWERS -> R.string.followers
        SocialListKind.FOLLOWING -> R.string.following
        SocialListKind.FRIENDS -> R.string.friends
    }
}
