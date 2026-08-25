package com.emirrkls.phokarta.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.auth.AuthRepository
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.OwnerSocialCounts
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.User
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.model.VisitStateLogic
import com.emirrkls.phokarta.ui.presentation.toUserMessageRes
import com.emirrkls.phokarta.core.sync.NoOpOfflineMutationRepository
import com.emirrkls.phokarta.core.sync.OfflineMutationRepository
import com.emirrkls.phokarta.core.sync.PendingVisit
import com.emirrkls.phokarta.core.sync.PendingVisitRecoveryCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VisitedPlace(val visit: Visit, val place: Place)
data class PendingVisitedPlace(val pending: PendingVisit, val place: Place)

enum class ProfilePlacesSegment { VISITS, SAVED }

data class ProfileUiState(
    val user: User,
    val followerCount: Long = 0,
    val followingCount: Long = 0,
    val friendCount: Long = 0,
    val visitedPlaces: List<VisitedPlace> = emptyList(),
    val pendingVisits: List<PendingVisitedPlace> = emptyList(),
    val visitSummary: VisitStateLogic.ProfileVisitSummary = VisitStateLogic.ProfileVisitSummary(0, 0, null),
    val placeVisitCounts: Map<String, Int> = emptyMap(),
    val savedPlaces: List<Place> = emptyList(),
    val savedPlaceIds: Set<String> = emptySet(),
    val visitedPlaceIds: Set<String> = emptySet(),
    val collections: List<Collection> = emptyList(),
    val placesSegment: ProfilePlacesSegment = ProfilePlacesSegment.VISITS,
    val saveErrorMessage: Int? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: TravelRepository,
    private val authRepository: AuthRepository,
    private val offlineMutations: OfflineMutationRepository = NoOpOfflineMutationRepository,
) : ViewModel() {
    private val recoveryCoordinator = PendingVisitRecoveryCoordinator(offlineMutations)
    val recoveryEvents = recoveryCoordinator.events
    private val placesSegment = MutableStateFlow(ProfilePlacesSegment.VISITS)
    private val saveError = MutableStateFlow<Int?>(null)
    private val socialCounts = MutableStateFlow(OwnerSocialCounts(0, 0, 0))

    init {
        viewModelScope.launch {
            repository.refreshCatalog()
            repository.refreshOwnerVisits()
            repository.refreshSaved()
            repository.refreshCollections()
        }
        refreshSocialCounts()
    }

    fun refreshSocialCounts() {
        viewModelScope.launch {
            when (val result = repository.loadOwnerSocialCounts()) {
                is RepositoryResult.Success -> socialCounts.value = result.value
                is RepositoryResult.Failure -> Unit
            }
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
                is RepositoryResult.Failure -> saveError.value = result.error.toUserMessageRes()
                is RepositoryResult.Success -> saveError.value = null
            }
        }
    }

    fun dismissSaveError() {
        saveError.value = null
    }

    fun retryMutation(mutationId: String) {
        viewModelScope.launch { recoveryCoordinator.retry(mutationId) }
    }

    fun editAndRetryFailedVisit(mutationId: String, placeId: String) {
        viewModelScope.launch { recoveryCoordinator.editAndRetry(mutationId, placeId) }
    }

    fun confirmReplaceDraftAndRecover(mutationId: String, placeId: String) {
        viewModelScope.launch { recoveryCoordinator.confirmReplaceDraft(mutationId, placeId) }
    }

    fun removeFailedVisit(mutationId: String) {
        viewModelScope.launch { recoveryCoordinator.removeFailedVisit(mutationId) }
    }

    private val canonicalCatalogState = combine(
        repository.observeVisits(),
        repository.observePlaces(),
        repository.observeCollections(),
        repository.observeSavedPlaceIds(),
        repository.observeVisitedPlaceIds(),
    ) { visits, places, collections, saved, visited ->
        CatalogSnapshot(visits, places, collections, saved, visited)
    }
    private val catalogState = combine(canonicalCatalogState, offlineMutations.observePendingVisits()) { catalog, pending ->
        catalog.copy(pending = pending)
    }

    val uiState = combine(catalogState, placesSegment, saveError, socialCounts) { catalog, segment, error, counts ->
        val byId = catalog.places.associateBy { it.id }
        val sortedVisits = VisitStateLogic.sortedNewestFirst(catalog.visits)
        val visitCounts = sortedVisits.groupingBy { it.placeId }.eachCount()
        ProfileUiState(
            user = repository.currentUser,
            followerCount = counts.followerCount,
            followingCount = counts.followingCount,
            friendCount = counts.friendCount,
            visitedPlaces = sortedVisits.mapNotNull { visit ->
                byId[visit.placeId]?.let { VisitedPlace(visit, it) }
            },
            pendingVisits = catalog.pending.mapNotNull { pending ->
                byId[pending.visit.placeId]?.let { PendingVisitedPlace(pending, it) }
            },
            visitSummary = VisitStateLogic.profileSummary(catalog.visits),
            placeVisitCounts = visitCounts,
            savedPlaces = catalog.saved.toList().mapNotNull { byId[it] },
            savedPlaceIds = catalog.saved,
            visitedPlaceIds = catalog.visited,
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
        val visited: Set<String>,
        val pending: List<PendingVisit> = emptyList(),
    )
}
