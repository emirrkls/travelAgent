package com.emirrkls.phokarta.feature.place

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.model.Place
import dagger.hilt.android.lifecycle.HiltViewModel
import com.emirrkls.phokarta.ui.presentation.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaceDetailUiState(
    val place: Place? = null,
    val isSaved: Boolean = false,
    val currentUserAvatarUrl: String,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isNotFound: Boolean = false,
    val saveErrorMessage: String? = null,
)

@HiltViewModel
class PlaceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TravelRepository,
) : ViewModel() {
    private val place = MutableStateFlow<Place?>(null)
    private val status = MutableStateFlow(DetailStatus())
    private val placeId: String = checkNotNull(savedStateHandle["placeId"])
    val uiState = combine(place, repository.observeSavedPlaceIds(), status) { current, saved, detailStatus ->
        PlaceDetailUiState(
            current,
            placeId in saved,
            repository.currentUser.avatarUrl,
            detailStatus.isLoading,
            detailStatus.errorMessage,
            detailStatus.isNotFound,
            detailStatus.saveErrorMessage,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlaceDetailUiState(currentUserAvatarUrl = repository.currentUser.avatarUrl),
    )
    init { load() }
    fun retry() = load()
    fun toggleSaved() {
        viewModelScope.launch {
            status.value = status.value.copy(saveErrorMessage = null)
            val result = repository.toggleSaved(placeId)
            if (result is RepositoryResult.Failure) {
                status.value = status.value.copy(saveErrorMessage = result.error.toUserMessage())
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            status.value = status.value.copy(isLoading = true, errorMessage = null, isNotFound = false)
            when (val result = repository.refreshPlaceDetail(placeId)) {
                is RepositoryResult.Success -> {
                    place.value = result.value
                    status.value = status.value.copy(isLoading = false)
                }
                is RepositoryResult.Failure -> {
                    place.value = repository.observePlaces().first().firstOrNull { it.id == placeId }
                    status.value = status.value.copy(
                        isLoading = false,
                        errorMessage = result.error.toUserMessage(),
                        isNotFound = result.error is TravelError.NotFound && place.value == null,
                    )
                }
            }
        }
    }

    private data class DetailStatus(
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val isNotFound: Boolean = false,
        val saveErrorMessage: String? = null,
    )
}
