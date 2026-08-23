package com.emirrkls.phokarta.feature.place

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.share.PhokartaShare
import com.emirrkls.phokarta.ui.presentation.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class PlaceDetailUiState(
    val place: Place? = null,
    val isSaved: Boolean = false,
    val visits: List<Visit> = emptyList(),
    val collections: List<Collection> = emptyList(),
    val membershipBusyIds: Set<String> = emptySet(),
    val currentUserAvatarUrl: String,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isNotFound: Boolean = false,
    val saveErrorMessage: String? = null,
    val membershipErrorMessage: String? = null,
    val isCreatingCollection: Boolean = false,
    val createCollectionError: String? = null,
    val shareText: String? = null,
)

@HiltViewModel
class PlaceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TravelRepository,
) : ViewModel() {
    private val placeId: String = checkNotNull(savedStateHandle["placeId"])
    private val place = MutableStateFlow<Place?>(null)
    private val status = MutableStateFlow(DetailStatus())

    val uiState = combine(
        place,
        repository.observeSavedPlaceIds(),
        repository.observeVisits(),
        repository.observeCollections(),
        status,
    ) { current, saved, visits, collections, detailStatus ->
        PlaceDetailUiState(
            place = current,
            isSaved = placeId in saved,
            visits = visits.filter { it.placeId == placeId }.sortedByDescending { it.visitedAt },
            collections = collections,
            membershipBusyIds = detailStatus.membershipBusyIds,
            currentUserAvatarUrl = repository.currentUser.avatarUrl,
            isLoading = detailStatus.isLoading,
            errorMessage = detailStatus.errorMessage,
            isNotFound = detailStatus.isNotFound,
            saveErrorMessage = detailStatus.saveErrorMessage,
            membershipErrorMessage = detailStatus.membershipErrorMessage,
            isCreatingCollection = detailStatus.isCreatingCollection,
            createCollectionError = detailStatus.createCollectionError,
            shareText = detailStatus.shareText,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlaceDetailUiState(currentUserAvatarUrl = repository.currentUser.avatarUrl),
    )

    init {
        load()
        viewModelScope.launch { repository.refreshCollections() }
        viewModelScope.launch { repository.refreshOwnerVisits() }
    }

    fun retry() = load()

    fun toggleSaved() {
        viewModelScope.launch {
            status.update { it.copy(saveErrorMessage = null) }
            when (val result = repository.toggleSaved(placeId)) {
                is RepositoryResult.Success -> Unit
                is RepositoryResult.Failure -> status.update {
                    it.copy(saveErrorMessage = result.error.toUserMessage())
                }
            }
        }
    }

    fun toggleCollectionMembership(collectionId: String) {
        viewModelScope.launch {
            val collection = uiState.value.collections.firstOrNull { it.id == collectionId }
                ?: repository.observeCollections().first().firstOrNull { it.id == collectionId }
                ?: return@launch
            val removing = placeId in collection.placeIds
            status.update {
                it.copy(
                    membershipBusyIds = it.membershipBusyIds + collectionId,
                    membershipErrorMessage = null,
                )
            }
            val result = if (removing) {
                repository.removePlaceFromCollection(collectionId, placeId)
            } else {
                repository.addPlaceToCollection(collectionId, placeId)
            }
            when (result) {
                is RepositoryResult.Success -> {
                    if (removing) {
                        repository.refreshCollectionDetail(collectionId)
                    }
                }
                is RepositoryResult.Failure -> {
                    if (!removing && result.error is TravelError.Conflict) {
                        repository.refreshCollectionDetail(collectionId)
                    } else {
                        status.update {
                            it.copy(membershipErrorMessage = result.error.toUserMessage())
                        }
                    }
                }
            }
            status.update {
                it.copy(membershipBusyIds = it.membershipBusyIds - collectionId)
            }
        }
    }

    fun createCollection(
        title: String,
        description: String,
        visibility: Visibility,
        autoSelect: Boolean = true,
    ) {
        val trimmed = title.trim()
        if (trimmed.isBlank() || status.value.isCreatingCollection) return
        status.update {
            it.copy(isCreatingCollection = true, createCollectionError = null)
        }
        viewModelScope.launch {
            val cover = place.value?.coverImage?.takeIf { it.isNotBlank() }
                ?: DEFAULT_COLLECTION_COVER
            val draft = Collection(
                id = UUID.randomUUID().toString(),
                userId = repository.currentUser.id,
                title = trimmed,
                description = description.trim(),
                placeIds = emptyList(),
                visibility = visibility,
                coverImage = cover,
            )
            when (val result = repository.saveCollection(draft)) {
                is RepositoryResult.Success -> {
                    if (autoSelect) {
                        when (val add = repository.addPlaceToCollection(result.value.id, placeId)) {
                            is RepositoryResult.Success -> Unit
                            is RepositoryResult.Failure -> {
                                if (add.error is TravelError.Conflict) {
                                    repository.refreshCollectionDetail(result.value.id)
                                } else {
                                    status.update {
                                        it.copy(
                                            isCreatingCollection = false,
                                            createCollectionError = add.error.toUserMessage(),
                                        )
                                    }
                                    return@launch
                                }
                            }
                        }
                    }
                    status.update {
                        it.copy(isCreatingCollection = false, createCollectionError = null)
                    }
                }
                is RepositoryResult.Failure -> status.update {
                    it.copy(
                        isCreatingCollection = false,
                        createCollectionError = result.error.toUserMessage(),
                    )
                }
            }
        }
    }

    fun clearCreateCollectionError() {
        status.update { it.copy(createCollectionError = null) }
    }

    fun prepareShare() {
        val current = place.value ?: return
        status.update { it.copy(shareText = PhokartaShare.placeText(current)) }
    }

    fun consumeShareText(): String? {
        val text = status.value.shareText
        status.update { it.copy(shareText = null) }
        return text
    }

    private fun load() {
        viewModelScope.launch {
            status.update {
                it.copy(isLoading = true, errorMessage = null, isNotFound = false)
            }
            when (val result = repository.refreshPlaceDetail(placeId)) {
                is RepositoryResult.Success -> {
                    place.value = result.value
                    status.update { it.copy(isLoading = false) }
                }
                is RepositoryResult.Failure -> {
                    place.value = repository.observePlaces().first().firstOrNull { it.id == placeId }
                    status.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.error.toUserMessage(),
                            isNotFound = result.error is TravelError.NotFound && place.value == null,
                        )
                    }
                }
            }
        }
    }

    private data class DetailStatus(
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val isNotFound: Boolean = false,
        val saveErrorMessage: String? = null,
        val membershipBusyIds: Set<String> = emptySet(),
        val membershipErrorMessage: String? = null,
        val isCreatingCollection: Boolean = false,
        val createCollectionError: String? = null,
        val shareText: String? = null,
    )

    companion object {
        private const val DEFAULT_COLLECTION_COVER =
            "https://images.unsplash.com/photo-1488646953014-85cb44e25828?w=1200"
    }
}
