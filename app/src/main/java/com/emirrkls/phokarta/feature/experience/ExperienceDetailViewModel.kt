package com.emirrkls.phokarta.feature.experience

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.ExperienceRepository
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.model.Experience
import com.emirrkls.phokarta.core.model.ExperienceMediaKind
import com.emirrkls.phokarta.core.model.RelationshipActionState
import com.emirrkls.phokarta.ui.presentation.toUserMessageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.OffsetDateTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExperienceDetailUiState(
    val experience: Experience? = null,
    val isLoading: Boolean = true,
    val relationshipBusy: Boolean = false,
    val errorMessage: Int? = null,
)

@HiltViewModel
class ExperienceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ExperienceRepository,
) : ViewModel() {
    private val experienceId: String = checkNotNull(savedStateHandle["experienceId"])
    private val _uiState = MutableStateFlow(ExperienceDetailUiState())
    val uiState: StateFlow<ExperienceDetailUiState> = _uiState.asStateFlow()

    init { load() }

    fun retry() = load()

    fun toggleRelationship() {
        val experience = _uiState.value.experience ?: return
        val relationship = experience.author.relationship ?: return
        if (_uiState.value.relationshipBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(relationshipBusy = true) }
            val result = when (relationship.state) {
                RelationshipActionState.NONE -> repository.follow(experience.author.id)
                RelationshipActionState.REQUEST_PENDING -> repository.cancelFollowRequest(experience.author.id)
                RelationshipActionState.FOLLOWING,
                RelationshipActionState.FRIENDS -> repository.unfollow(experience.author.id)
                else -> null
            }
            when (result) {
                is RepositoryResult.Success -> _uiState.update { state ->
                    state.copy(experience = state.experience?.copy(
                        author = state.experience.author.copy(relationship = result.value),
                    ))
                }
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(errorMessage = result.error.toUserMessageRes())
                }
                null -> Unit
            }
            _uiState.update { it.copy(relationshipBusy = false) }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = repository.detail(experienceId)) {
                is RepositoryResult.Success -> {
                    _uiState.value = ExperienceDetailUiState(experience = result.value, isLoading = false)
                    renewExpiringMedia(result.value)
                }
                is RepositoryResult.Failure -> _uiState.value = ExperienceDetailUiState(
                    isLoading = false,
                    errorMessage = result.error.toUserMessageRes(),
                )
            }
        }
    }

    private suspend fun renewExpiringMedia(experience: Experience) {
        val threshold = OffsetDateTime.now().plusMinutes(1)
        val renewed = experience.media.map { item ->
            val expiry = item.accessExpiresAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            if (item.kind == ExperienceMediaKind.MANAGED && item.id != null &&
                (expiry == null || expiry.isBefore(threshold))
            ) {
                when (val result = repository.renewMediaUrl(item.id)) {
                    is RepositoryResult.Success -> item.copy(
                        url = result.value.first,
                        accessExpiresAt = result.value.second,
                    )
                    is RepositoryResult.Failure -> item
                }
            } else item
        }
        _uiState.update { state ->
            state.copy(experience = state.experience?.copy(media = renewed))
        }
    }
}
