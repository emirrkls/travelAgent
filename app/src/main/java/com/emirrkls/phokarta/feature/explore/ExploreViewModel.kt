package com.emirrkls.phokarta.feature.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.ExperienceFeedGateway
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.model.ExperienceFeedLens
import com.emirrkls.phokarta.core.model.ExperienceSummary
import com.emirrkls.phokarta.core.model.RelationshipActionState
import com.emirrkls.phokarta.core.model.RelationshipV2
import com.emirrkls.phokarta.ui.presentation.toUserMessageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExperienceDiscoveryFilter {
    CALM,
    SUNSET,
    FOOD,
    SEA,
    NATURE,
}

data class ExploreUiState(
    val lens: ExperienceFeedLens = ExperienceFeedLens.FOR_YOU,
    val query: String = "",
    val discoveryFilter: ExperienceDiscoveryFilter? = null,
    val items: List<ExperienceSummary> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val errorMessage: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val relationshipInFlight: Set<String> = emptySet(),
) {
    val needsNearbyLocation: Boolean
        get() = lens == ExperienceFeedLens.NEARBY && (latitude == null || longitude == null)
}

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val repository: ExperienceFeedGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()
    private var requestGeneration = 0L
    private var searchJob: Job? = null

    init { refresh() }

    fun selectLens(lens: ExperienceFeedLens) {
        if (lens == _uiState.value.lens) return
        _uiState.update { it.copy(lens = lens, items = emptyList(), nextCursor = null, hasMore = false) }
        refresh()
    }

    fun setQuery(value: String) {
        _uiState.update { it.copy(query = value) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            refresh()
        }
    }

    fun selectDiscoveryFilter(filter: ExperienceDiscoveryFilter) {
        _uiState.update {
            it.copy(
                discoveryFilter = filter.takeUnless { selected -> selected == it.discoveryFilter },
                items = emptyList(),
                nextCursor = null,
                hasMore = false,
            )
        }
        refresh()
    }

    fun setNearbyLocation(latitude: Double, longitude: Double) {
        _uiState.update { it.copy(latitude = latitude, longitude = longitude) }
        if (_uiState.value.lens == ExperienceFeedLens.NEARBY) refresh()
    }

    fun clearNearbyLocation() {
        requestGeneration++
        _uiState.update {
            it.copy(latitude = null, longitude = null, items = emptyList(), isLoading = false)
        }
    }

    fun retry() = refresh()

    fun loadMore() {
        val snapshot = _uiState.value
        if (!snapshot.hasMore || snapshot.isLoading || snapshot.isLoadingMore) return
        load(reset = false)
    }

    fun toggleRelationship(authorId: String) {
        val item = _uiState.value.items.firstOrNull { it.author.id == authorId } ?: return
        val relationship = item.author.relationship ?: return
        if (authorId in _uiState.value.relationshipInFlight) return
        viewModelScope.launch {
            _uiState.update { it.copy(relationshipInFlight = it.relationshipInFlight + authorId) }
            val result = when (relationship.state) {
                RelationshipActionState.NONE -> repository.follow(authorId)
                RelationshipActionState.REQUEST_PENDING -> repository.cancelFollowRequest(authorId)
                RelationshipActionState.FOLLOWING,
                RelationshipActionState.FRIENDS -> repository.unfollow(authorId)
                else -> null
            }
            when (result) {
                is RepositoryResult.Success -> replaceRelationship(authorId, result.value)
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(errorMessage = result.error.toUserMessageRes())
                }
                null -> Unit
            }
            _uiState.update { it.copy(relationshipInFlight = it.relationshipInFlight - authorId) }
        }
    }

    private fun replaceRelationship(authorId: String, relationship: RelationshipV2) {
        _uiState.update { state ->
            state.copy(items = state.items.map { item ->
                if (item.author.id == authorId) {
                    item.copy(author = item.author.copy(relationship = relationship))
                } else item
            })
        }
    }

    private fun refresh() {
        searchJob?.cancel()
        load(reset = true)
    }

    private fun load(reset: Boolean) {
        val snapshot = _uiState.value
        if (snapshot.lens == ExperienceFeedLens.NEARBY && snapshot.needsNearbyLocation) {
            requestGeneration++
            _uiState.update { it.copy(items = emptyList(), isLoading = false, isLoadingMore = false, errorMessage = null) }
            return
        }
        val generation = ++requestGeneration
        val cursor = if (reset) null else snapshot.nextCursor
        _uiState.update {
            if (reset) it.copy(isLoading = true, isLoadingMore = false, errorMessage = null)
            else it.copy(isLoadingMore = true, errorMessage = null)
        }
        viewModelScope.launch {
            val result = repository.feed(
                lens = snapshot.lens,
                cursor = cursor,
                search = snapshot.query,
                primary = when (snapshot.discoveryFilter) {
                    ExperienceDiscoveryFilter.SUNSET -> "GUN_BATIMI"
                    ExperienceDiscoveryFilter.FOOD -> "KAHVALTI"
                    ExperienceDiscoveryFilter.SEA -> "DENIZ_YUZME"
                    ExperienceDiscoveryFilter.NATURE -> "DOGA_YURUYUSU"
                    else -> null
                },
                vibe = if (snapshot.discoveryFilter == ExperienceDiscoveryFilter.CALM) "CALM" else null,
                latitude = snapshot.latitude,
                longitude = snapshot.longitude,
                radiusMeters = if (snapshot.lens == ExperienceFeedLens.NEARBY) 25_000.0 else null,
            )
            if (generation != requestGeneration) return@launch
            when (result) {
                is RepositoryResult.Success -> _uiState.update { current ->
                    current.copy(
                        items = if (reset) result.value.items else (current.items + result.value.items).distinctBy { it.id },
                        nextCursor = result.value.nextCursor,
                        hasMore = result.value.hasMore,
                        isLoading = false,
                        isLoadingMore = false,
                        errorMessage = null,
                    )
                }
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        errorMessage = result.error.toUserMessageRes(),
                    )
                }
            }
        }
    }
}
