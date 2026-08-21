package com.emirrkls.travelagent.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.travelagent.core.data.TravelRepository
import com.emirrkls.travelagent.core.model.Collection
import com.emirrkls.travelagent.core.model.Place
import com.emirrkls.travelagent.core.model.User
import com.emirrkls.travelagent.core.model.Visit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class VisitedPlace(val visit: Visit, val place: Place)
data class ProfileUiState(
    val user: User,
    val visitedPlaces: List<VisitedPlace> = emptyList(),
    val collections: List<Collection> = emptyList(),
)

@HiltViewModel
class ProfileViewModel @Inject constructor(private val repository: TravelRepository) : ViewModel() {
    val uiState = combine(
        repository.observeVisits(),
        repository.observePlaces(),
        repository.observeCollections(),
    ) { visits, places, collections ->
        ProfileUiState(
            user = repository.currentUser,
            visitedPlaces = visits.mapNotNull { visit ->
                places.firstOrNull { it.id == visit.placeId }?.let { VisitedPlace(visit, it) }
            },
            collections = collections,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ProfileUiState(user = repository.currentUser),
    )
}
