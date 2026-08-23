package com.emirrkls.phokarta.feature.secondary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.ActivityItem
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.ui.presentation.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class SecondaryUiState(
    val collections: List<Collection> = emptyList(),
    val activity: List<ActivityItem> = emptyList(),
    val places: List<Place> = emptyList(),
    val savedPlaceIds: Set<String> = emptySet(),
    val collectionsError: String? = null,
    val detailError: String? = null,
    val detailNotFound: Boolean = false,
    val isCreatingCollection: Boolean = false,
    val createCollectionError: String? = null,
    val createdCollectionId: String? = null,
    val membershipError: String? = null,
)

@HiltViewModel
class SecondaryViewModel @Inject constructor(private val repository: TravelRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(SecondaryUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(activity = repository.getActivity()) }
        }
        viewModelScope.launch {
            repository.refreshCatalog()
            when (val result = repository.refreshCollections()) {
                is RepositoryResult.Success -> _uiState.update { it.copy(collectionsError = null) }
                is RepositoryResult.Failure -> _uiState.update { it.copy(collectionsError = result.error.toUserMessage()) }
            }
        }
        viewModelScope.launch {
            combine(
                repository.observePlaces(),
                repository.observeSavedPlaceIds(),
                repository.observeCollections(),
            ) { places, saved, collections -> Triple(places, saved, collections) }
                .collect { (places, saved, collections) ->
                    _uiState.update {
                        it.copy(
                            places = places,
                            savedPlaceIds = saved,
                            collections = collections,
                        )
                    }
                }
        }
    }

    fun toggleSaved(placeId: String) {
        viewModelScope.launch { repository.toggleSaved(placeId) }
    }

    fun refreshCollectionDetail(collectionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(detailError = null, detailNotFound = false) }
            when (val result = repository.refreshCollectionDetail(collectionId)) {
                is RepositoryResult.Success -> _uiState.update { it.copy(detailError = null, detailNotFound = false) }
                is RepositoryResult.Failure -> {
                    val cached = repository.getCollection(collectionId)
                    _uiState.update {
                        it.copy(
                            detailError = result.error.toUserMessage(),
                            detailNotFound = result.error is TravelError.NotFound && cached == null,
                        )
                    }
                }
            }
        }
    }

    fun createCollection(
        title: String,
        description: String,
        visibility: Visibility,
        coverImage: String = DEFAULT_COLLECTION_COVER,
    ) {
        val trimmed = title.trim()
        if (trimmed.isBlank() || _uiState.value.isCreatingCollection) return
        _uiState.update {
            it.copy(isCreatingCollection = true, createCollectionError = null, createdCollectionId = null)
        }
        viewModelScope.launch {
            val draft = Collection(
                id = UUID.randomUUID().toString(),
                userId = repository.currentUser.id,
                title = trimmed,
                description = description.trim(),
                placeIds = emptyList(),
                visibility = visibility,
                coverImage = coverImage.ifBlank { DEFAULT_COLLECTION_COVER },
            )
            when (val result = repository.saveCollection(draft)) {
                is RepositoryResult.Success -> _uiState.update {
                    it.copy(
                        isCreatingCollection = false,
                        createdCollectionId = result.value.id,
                        createCollectionError = null,
                    )
                }
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(
                        isCreatingCollection = false,
                        createCollectionError = result.error.toUserMessage(),
                    )
                }
            }
        }
    }

    fun clearCreateCollectionError() {
        _uiState.update { it.copy(createCollectionError = null) }
    }

    fun clearCreatedCollectionId() {
        _uiState.update { it.copy(createdCollectionId = null) }
    }

    fun removePlaceFromCollection(collectionId: String, placeId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(membershipError = null) }
            when (val result = repository.removePlaceFromCollection(collectionId, placeId)) {
                is RepositoryResult.Success -> {
                    repository.refreshCollectionDetail(collectionId)
                }
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(membershipError = result.error.toUserMessage())
                }
            }
        }
    }

    companion object {
        const val DEFAULT_COLLECTION_COVER =
            "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=1200"
    }
}
