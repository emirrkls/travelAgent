package com.emirrkls.phokarta.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.auth.AuthRepository
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.User
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.ui.presentation.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VisitedPlace(val visit: Visit, val place: Place)

enum class ProfilePlacesSegment { VISITS, SAVED }

data class ProfileUiState(
    val user: User,
    val visitedPlaces: List<VisitedPlace> = emptyList(),
    val savedPlaces: List<Place> = emptyList(),
    val savedPlaceIds: Set<String> = emptySet(),
    val collections: List<Collection> = emptyList(),
    val placesSegment: ProfilePlacesSegment = ProfilePlacesSegment.VISITS,
    val saveErrorMessage: String? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: TravelRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val placesSegment = MutableStateFlow(ProfilePlacesSegment.VISITS)
    private val saveError = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            repository.refreshCatalog()
            repository.refreshOwnerVisits()
            repository.refreshSaved()
            repository.refreshCollections()
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    fun setPlacesSegment(segment: ProfilePlacesSegment) {
        placesSegment.value = segment
    }

    fun toggleSaved(placeId: String) {
        viewModelScope.launch {
            when (val result = repository.toggleSaved(placeId)) {
                is RepositoryResult.Failure -> saveError.value = result.error.toUserMessage()
                is RepositoryResult.Success -> saveError.value = null
            }
        }
    }

    fun dismissSaveError() {
        saveError.value = null
    }

    private val catalogState = combine(
        repository.observeVisits(),
        repository.observePlaces(),
        repository.observeCollections(),
        repository.observeSavedPlaceIds(),
    ) { visits, places, collections, saved ->
        CatalogSnapshot(visits, places, collections, saved)
    }

    val uiState = combine(catalogState, placesSegment, saveError) { catalog, segment, error ->
        val byId = catalog.places.associateBy { it.id }
        ProfileUiState(
            user = repository.currentUser,
            visitedPlaces = catalog.visits.mapNotNull { visit ->
                byId[visit.placeId]?.let { VisitedPlace(visit, it) }
            },
            savedPlaces = catalog.saved.toList().mapNotNull { byId[it] },
            savedPlaceIds = catalog.saved,
            collections = catalog.collections,
            placesSegment = segment,
            saveErrorMessage = error,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ProfileUiState(user = repository.currentUser),
    )

    private data class CatalogSnapshot(
        val visits: List<Visit>,
        val places: List<Place>,
        val collections: List<Collection>,
        val saved: Set<String>,
    )
}
