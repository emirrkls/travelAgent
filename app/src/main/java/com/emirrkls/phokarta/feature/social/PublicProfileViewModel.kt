package com.emirrkls.phokarta.feature.social

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.PublicUserProfile
import com.emirrkls.phokarta.core.model.RelationshipState
import com.emirrkls.phokarta.ui.presentation.toUserMessageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PublicProfileUiState(
    val profile: PublicUserProfile? = null,
    val isOwnProfile: Boolean = false,
    val isLoading: Boolean = true,
    val isMutating: Boolean = false,
    val notFound: Boolean = false,
    val errorMessage: Int? = null,
    val actionErrorMessage: Int? = null,
)

@HiltViewModel
class PublicProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TravelRepository,
) : ViewModel() {
    private val userId: String = checkNotNull(savedStateHandle["userId"])
    private val _uiState = MutableStateFlow(PublicProfileUiState())
    val uiState = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, errorMessage = null, notFound = false, actionErrorMessage = null)
            }
            when (val result = repository.loadPublicProfile(userId)) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        notFound = result.error is com.emirrkls.phokarta.core.data.TravelError.NotFound,
                        errorMessage = result.error.toUserMessageRes(),
                    )
                }
                is RepositoryResult.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        profile = result.value,
                        isOwnProfile = result.value.id == repository.currentUser.id,
                        errorMessage = null,
                        notFound = false,
                    )
                }
            }
        }
    }

    fun toggleFollow() {
        val current = _uiState.value
        val profile = current.profile ?: return
        if (current.isOwnProfile || current.isMutating) return
        val relationship = profile.relationship ?: RelationshipState(false, false)
        val wasFollowing = relationship.isFollowing
        val optimistic = relationship.copy(
            isFollowing = !wasFollowing,
            isFriend = !wasFollowing && relationship.followsYou,
        )
        val followerDelta = if (wasFollowing) -1L else 1L
        _uiState.update {
            it.copy(
                isMutating = true,
                actionErrorMessage = null,
                profile = profile.copy(
                    relationship = optimistic,
                    followerCount = (profile.followerCount + followerDelta).coerceAtLeast(0),
                ),
            )
        }
        viewModelScope.launch {
            val result = if (wasFollowing) {
                repository.unfollowUser(profile.id)
            } else {
                repository.followUser(profile.id)
            }
            when (result) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(
                        isMutating = false,
                        actionErrorMessage = result.error.toUserMessageRes(),
                        profile = profile,
                    )
                }
                is RepositoryResult.Success -> {
                    when (val refreshed = repository.loadPublicProfile(profile.id)) {
                        is RepositoryResult.Success -> _uiState.update {
                            it.copy(
                                isMutating = false,
                                profile = refreshed.value,
                                isOwnProfile = refreshed.value.id == repository.currentUser.id,
                            )
                        }
                        is RepositoryResult.Failure -> _uiState.update { it.copy(isMutating = false) }
                    }
                }
            }
        }
    }
}
