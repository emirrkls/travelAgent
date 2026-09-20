package com.emirrkls.phokarta.feature.experience

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.ExperienceRepository
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.model.Experience
import com.emirrkls.phokarta.core.model.ExperienceMediaKind
import com.emirrkls.phokarta.core.model.ConversationEntry
import com.emirrkls.phokarta.core.model.ConversationEntryType
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
    val planBusy: Boolean = false,
    val acknowledgementBusy: Boolean = false,
    val collections: List<Collection> = emptyList(),
    val collectionBusy: Boolean = false,
    val conversation: List<ConversationEntry> = emptyList(),
    val conversationLoading: Boolean = true,
    val conversationBusy: Boolean = false,
    val errorMessage: Int? = null,
)

@HiltViewModel
class ExperienceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ExperienceRepository,
    private val travelRepository: TravelRepository,
) : ViewModel() {
    private val experienceId: String = checkNotNull(savedStateHandle["experienceId"])
    private val _uiState = MutableStateFlow(ExperienceDetailUiState())
    val uiState: StateFlow<ExperienceDetailUiState> = _uiState.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            travelRepository.observeCollections().collect { values ->
                _uiState.update { it.copy(collections = values) }
            }
        }
        viewModelScope.launch { travelRepository.refreshCollections() }
        viewModelScope.launch {
            repository.conversation(experienceId).collect { entries ->
                _uiState.update { state ->
                    state.copy(
                        conversation = entries,
                        experience = state.experience?.copy(conversationCount = entries.size.toLong()),
                    )
                }
            }
        }
    }

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

    fun togglePlan() {
        val experience = _uiState.value.experience ?: return
        if (_uiState.value.planBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(planBusy = true) }
            when (val result = repository.togglePlan(experience.id)) {
                is RepositoryResult.Success -> _uiState.update { state ->
                    state.copy(experience = state.experience?.copy(plannedByViewer = result.value))
                }
                is RepositoryResult.Failure -> _uiState.update { it.copy(errorMessage = result.error.toUserMessageRes()) }
            }
            _uiState.update { it.copy(planBusy = false) }
        }
    }

    fun acknowledge() {
        val experience = _uiState.value.experience ?: return
        if (_uiState.value.acknowledgementBusy || experience.acknowledgedByViewer) return
        viewModelScope.launch {
            _uiState.update { it.copy(acknowledgementBusy = true) }
            when (val result = repository.acknowledge(experience.id)) {
                is RepositoryResult.Success -> _uiState.update { state -> state.copy(
                    experience = state.experience?.copy(
                        acknowledgedByViewer = true,
                        acknowledgementCount = state.experience.acknowledgementCount + 1,
                    ),
                ) }
                is RepositoryResult.Failure -> _uiState.update { it.copy(errorMessage = result.error.toUserMessageRes()) }
            }
            _uiState.update { it.copy(acknowledgementBusy = false) }
        }
    }

    fun addToCollection(collectionId: String) {
        if (_uiState.value.collectionBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(collectionBusy = true) }
            when (val result = repository.addToCollection(collectionId, experienceId)) {
                is RepositoryResult.Failure -> _uiState.update { it.copy(errorMessage = result.error.toUserMessageRes()) }
                is RepositoryResult.Success -> Unit
            }
            _uiState.update { it.copy(collectionBusy = false) }
        }
    }

    fun refreshConversation() {
        viewModelScope.launch {
            _uiState.update { it.copy(conversationLoading = true) }
            when (val result = repository.refreshConversation(experienceId)) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(conversationLoading = false, errorMessage = result.error.toUserMessageRes())
                }
                is RepositoryResult.Success -> _uiState.update { it.copy(conversationLoading = false) }
            }
        }
    }

    fun createConversation(type: ConversationEntryType, body: String) {
        val experience = _uiState.value.experience ?: return
        if (_uiState.value.conversationBusy || body.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(conversationBusy = true, errorMessage = null) }
            when (val result = repository.createConversationRoot(experience, type, body)) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(errorMessage = result.error.toUserMessageRes())
                }
                is RepositoryResult.Success -> Unit
            }
            _uiState.update { it.copy(conversationBusy = false) }
        }
    }

    fun reply(root: ConversationEntry, body: String) {
        val experience = _uiState.value.experience ?: return
        if (_uiState.value.conversationBusy || body.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(conversationBusy = true, errorMessage = null) }
            when (val result = repository.createConversationReply(experience, root, body)) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(errorMessage = result.error.toUserMessageRes())
                }
                is RepositoryResult.Success -> Unit
            }
            _uiState.update { it.copy(conversationBusy = false) }
        }
    }

    fun editConversation(entry: ConversationEntry, body: String) {
        if (_uiState.value.conversationBusy || body.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(conversationBusy = true, errorMessage = null) }
            when (val result = repository.editConversationEntry(entry, body)) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(errorMessage = result.error.toUserMessageRes())
                }
                is RepositoryResult.Success -> Unit
            }
            _uiState.update { it.copy(conversationBusy = false) }
        }
    }

    fun deleteConversation(entry: ConversationEntry) {
        if (_uiState.value.conversationBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(conversationBusy = true, errorMessage = null) }
            when (val result = repository.deleteConversationEntry(entry)) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(errorMessage = result.error.toUserMessageRes())
                }
                is RepositoryResult.Success -> Unit
            }
            _uiState.update { it.copy(conversationBusy = false) }
        }
    }

    fun retryConversation(entry: ConversationEntry) {
        val mutationId = entry.clientMutationId ?: return
        viewModelScope.launch {
            when (val result = repository.retryConversation(mutationId)) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(errorMessage = result.error.toUserMessageRes())
                }
                is RepositoryResult.Success -> Unit
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = repository.detail(experienceId)) {
                is RepositoryResult.Success -> {
                    _uiState.value = ExperienceDetailUiState(experience = result.value, isLoading = false)
                    renewExpiringMedia(result.value)
                    refreshConversation()
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
