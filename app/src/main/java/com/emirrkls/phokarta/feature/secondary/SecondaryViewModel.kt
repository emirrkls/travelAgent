package com.emirrkls.phokarta.feature.secondary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.ui.presentation.toUserMessageRes
import com.emirrkls.phokarta.feature.policy.PolicyAcceptanceUi
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
    val places: List<Place> = emptyList(),
    val savedPlaceIds: Set<String> = emptySet(),
    val collectionsError: Int? = null,
    val detailError: Int? = null,
    val detailNotFound: Boolean = false,
    val isCreatingCollection: Boolean = false,
    val createCollectionError: Int? = null,
    val createdCollectionId: String? = null,
    val membershipError: Int? = null,
    val policy: PolicyAcceptanceUi = PolicyAcceptanceUi(),
)

@HiltViewModel
class SecondaryViewModel @Inject constructor(private val repository: TravelRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(SecondaryUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.refreshCatalog()
            when (val result = repository.refreshCollections()) {
                is RepositoryResult.Success -> _uiState.update { it.copy(collectionsError = null) }
                is RepositoryResult.Failure -> _uiState.update { it.copy(collectionsError = result.error.toUserMessageRes()) }
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
                    val inaccessible = result.error is TravelError.NotFound ||
                        result.error is TravelError.Forbidden
                    _uiState.update {
                        it.copy(
                            detailError = result.error.toUserMessageRes(),
                            detailNotFound = inaccessible,
                            collections = if (inaccessible) {
                                it.collections.filterNot { collection -> collection.id == collectionId }
                            } else {
                                it.collections
                            },
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
                coverImage = coverImage,
            )
            when (val result = repository.saveCollection(draft)) {
                is RepositoryResult.Success -> _uiState.update {
                    it.copy(
                        isCreatingCollection = false,
                        createdCollectionId = result.value.id,
                        createCollectionError = null,
                    )
                }
                is RepositoryResult.Failure -> if (result.error is TravelError.PolicyAcceptanceRequired) {
                    _uiState.update {
                        it.copy(
                            isCreatingCollection = false,
                            createCollectionError = null,
                            policy = PolicyAcceptanceUi(
                                visible = true,
                                requiredVersion = result.error.requiredVersion,
                            ),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isCreatingCollection = false,
                            createCollectionError = result.error.toUserMessageRes(),
                        )
                    }
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

    fun setPolicyChecked(checked: Boolean) {
        _uiState.update { it.copy(policy = it.policy.copy(checked = checked, error = null)) }
    }

    fun dismissPolicy() {
        _uiState.update { it.copy(policy = PolicyAcceptanceUi()) }
    }

    fun acceptCurrentPolicy() {
        val policy = _uiState.value.policy
        if (!policy.visible || !policy.checked || policy.accepting) return
        viewModelScope.launch {
            _uiState.update { it.copy(policy = it.policy.copy(accepting = true, error = null)) }
            when (val result = repository.acceptPolicy(policy.requiredVersion)) {
                is RepositoryResult.Success -> dismissPolicy()
                is RepositoryResult.Failure -> {
                    val error = if (result.error is TravelError.Offline || result.error is TravelError.Timeout) {
                        com.emirrkls.phokarta.R.string.error_offline
                    } else {
                        result.error.toUserMessageRes()
                    }
                    _uiState.update {
                        it.copy(policy = it.policy.copy(accepting = false, error = error, checked = false))
                    }
                }
            }
        }
    }

    fun removePlaceFromCollection(collectionId: String, placeId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(membershipError = null) }
            when (val result = repository.removePlaceFromCollection(collectionId, placeId)) {
                is RepositoryResult.Success -> {
                    repository.refreshCollectionDetail(collectionId)
                }
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(membershipError = result.error.toUserMessageRes())
                }
            }
        }
    }

    companion object {
        const val DEFAULT_COLLECTION_COVER = ""
    }
}
