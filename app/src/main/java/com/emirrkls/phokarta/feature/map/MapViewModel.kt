package com.emirrkls.phokarta.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.model.NearbyPlace
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.SavedFriendMetrics
import com.emirrkls.phokarta.ui.presentation.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.hypot

data class MapViewport(
    val north: Double,
    val east: Double,
    val south: Double,
    val west: Double,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val zoom: Float,
) {
    fun contains(place: Place): Boolean {
        val longitudeIsVisible = if (west <= east) {
            place.longitude in west..east
        } else {
            place.longitude >= west || place.longitude <= east
        }
        return place.latitude in south..north && longitudeIsVisible
    }
}

data class MapFilters(
    val category: PlaceCategory? = null,
    val highlyRatedOnly: Boolean = false,
    val friendsVisitedOnly: Boolean = false,
    val visitedOnly: Boolean = false,
    val wantToGoOnly: Boolean = false,
) {
    val activeCount: Int
        get() = listOfNotNull(category).size + listOf(
            highlyRatedOnly,
            friendsVisitedOnly,
            visitedOnly,
            wantToGoOnly,
        ).count { it }
}

data class MapCameraRequest(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val zoom: Float,
)

data class MapUiState(
    val allPlaces: List<Place> = emptyList(),
    val visiblePlaces: List<Place> = emptyList(),
    val savedPlaceIds: Set<String> = emptySet(),
    val visitedPlaceIds: Set<String> = emptySet(),
    val filters: MapFilters = MapFilters(),
    val selectedPlaceId: String? = null,
    val appliedViewport: MapViewport? = null,
    val cameraViewport: MapViewport? = null,
    val showSearchThisArea: Boolean = false,
    val userLocation: Pair<Double, Double>? = null,
    val locationMessage: String? = null,
    val cameraRequest: MapCameraRequest? = null,
    val isLoading: Boolean = false,
    val boundsErrorMessage: String? = null,
    val friendMetricsByPlaceId: Map<String, SavedFriendMetrics> = emptyMap(),
    val friendMetricsLoading: Boolean = false,
    val friendMetricsErrorMessage: String? = null,
    val saveErrorMessage: String? = null,
    val nearbyPlaces: List<NearbyPlace> = emptyList(),
)

internal fun filterMapPlaces(
    places: List<Place>,
    filters: MapFilters,
    visitedPlaceIds: Set<String>,
    savedPlaceIds: Set<String>,
    viewport: MapViewport?,
    friendMetrics: Map<String, SavedFriendMetrics> = emptyMap(),
): List<Place> = places.filter { place ->
    (filters.category == null || place.category == filters.category) &&
        (!filters.highlyRatedOnly || (place.communityScore ?: Double.NEGATIVE_INFINITY) >= 9.0) &&
        (!filters.friendsVisitedOnly || (friendMetrics[place.id]?.friendsVisitedCount ?: 0) > 0) &&
        (!filters.visitedOnly || place.id in visitedPlaceIds) &&
        (!filters.wantToGoOnly || place.id in savedPlaceIds) &&
        (viewport == null || viewport.contains(place))
}

internal fun mapFriendSignal(metrics: SavedFriendMetrics?): MapFriendSignal {
    val count = metrics?.friendsVisitedCount ?: 0
    return MapFriendSignal(
        friendsVisitedCount = count,
        friendAverageScore = if (count == 0) null else metrics?.averageScore,
    )
}

data class MapFriendSignal(
    val friendsVisitedCount: Int = 0,
    val friendAverageScore: Double? = null,
) {
    val hasSignal: Boolean get() = friendsVisitedCount > 0
    val showScore: Boolean get() = hasSignal && friendAverageScore != null
}

internal fun viewportMovedEnough(applied: MapViewport, candidate: MapViewport): Boolean {
    if (abs(applied.zoom - candidate.zoom) >= 0.55f) return true
    val latitudeSpan = (applied.north - applied.south).coerceAtLeast(0.0001)
    val longitudeSpan = if (applied.west <= applied.east) {
        (applied.east - applied.west).coerceAtLeast(0.0001)
    } else {
        (360.0 - applied.west + applied.east).coerceAtLeast(0.0001)
    }
    val normalizedDistance = hypot(
        (candidate.centerLatitude - applied.centerLatitude) / latitudeSpan,
        (candidate.centerLongitude - applied.centerLongitude) / longitudeSpan,
    )
    return normalizedDistance >= 0.18
}

@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: TravelRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val restoredAppliedViewport = savedStateHandle.readViewport("map.applied")
    private val filters = MutableStateFlow(
        MapFilters(
            category = savedStateHandle.get<String>("map.category")?.let(PlaceCategory::valueOf),
            highlyRatedOnly = savedStateHandle["map.highlyRated"] ?: false,
            friendsVisitedOnly = savedStateHandle["map.friendsVisited"] ?: false,
            visitedOnly = savedStateHandle["map.visited"] ?: false,
            wantToGoOnly = savedStateHandle["map.wantToGo"] ?: false,
        ),
    )
    private val appliedViewport = MutableStateFlow(restoredAppliedViewport)
    private val boundsPlaces = MutableStateFlow<List<Place>>(emptyList())
    private val friendMetricsSnapshot = MutableStateFlow(FriendMetricsSnapshot())
    private var hasFetchedBounds = false
    private val requestIds = AtomicLong()
    private var boundsRequestId = 0L
    private var boundsRequestJob: Job? = null
    private val _uiState = MutableStateFlow(
        MapUiState(
            filters = filters.value,
            selectedPlaceId = savedStateHandle["map.selectedPlaceId"],
            appliedViewport = restoredAppliedViewport,
            cameraViewport = savedStateHandle.readViewport("map.camera") ?: restoredAppliedViewport,
        ),
    )
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                combine(
                    boundsPlaces,
                    repository.observeVisitedPlaceIds(),
                    repository.observeSavedPlaceIds(),
                    filters,
                    appliedViewport,
                ) { places, visited, saved, currentFilters, viewport ->
                    MapCore(places, visited, saved, currentFilters, viewport)
                },
                friendMetricsSnapshot,
            ) { core, metrics ->
                val applyFriendsFilter = core.filters.friendsVisitedOnly &&
                    !metrics.loading &&
                    metrics.errorMessage == null
                val visiblePlaces = if (core.filters.friendsVisitedOnly &&
                    (metrics.loading || metrics.errorMessage != null)
                ) {
                    null
                } else {
                    filterMapPlaces(
                        places = core.places,
                        filters = core.filters.copy(
                            friendsVisitedOnly = applyFriendsFilter && core.filters.friendsVisitedOnly,
                        ),
                        visitedPlaceIds = core.visited,
                        savedPlaceIds = core.saved,
                        viewport = core.viewport,
                        friendMetrics = metrics.byPlaceId,
                    )
                }
                MapData(
                    places = core.places,
                    visiblePlaces = visiblePlaces,
                    savedPlaceIds = core.saved,
                    visitedPlaceIds = core.visited,
                    filters = core.filters,
                    viewport = core.viewport,
                    friendMetrics = metrics,
                )
            }.collect { data ->
                _uiState.update { current ->
                    val visiblePlaces = data.visiblePlaces ?: current.visiblePlaces
                    val selectedPlaceId = current.selectedPlaceId?.takeIf { selected ->
                        visiblePlaces.any { it.id == selected }
                    }
                    if (selectedPlaceId != current.selectedPlaceId) {
                        savedStateHandle["map.selectedPlaceId"] = selectedPlaceId
                    }
                    current.copy(
                        allPlaces = data.places,
                        visiblePlaces = visiblePlaces,
                        savedPlaceIds = data.savedPlaceIds,
                        visitedPlaceIds = data.visitedPlaceIds,
                        filters = data.filters,
                        selectedPlaceId = selectedPlaceId,
                        appliedViewport = data.viewport,
                        friendMetricsByPlaceId = data.friendMetrics.byPlaceId,
                        friendMetricsLoading = data.friendMetrics.loading,
                        friendMetricsErrorMessage = data.friendMetrics.errorMessage,
                    )
                }
            }
        }
    }

    fun selectPlace(placeId: String) {
        _uiState.update { state ->
            if (state.visiblePlaces.any { it.id == placeId }) {
                savedStateHandle["map.selectedPlaceId"] = placeId
                state.copy(selectedPlaceId = placeId)
            } else state
        }
    }

    fun clearSelection() {
        savedStateHandle["map.selectedPlaceId"] = null
        _uiState.update { it.copy(selectedPlaceId = null) }
    }

    fun selectCategory(category: PlaceCategory?) = updateFilters { it.copy(category = category) }
    fun toggleHighlyRated() = updateFilters { it.copy(highlyRatedOnly = !it.highlyRatedOnly) }
    fun toggleFriendsVisited() = updateFilters { it.copy(friendsVisitedOnly = !it.friendsVisitedOnly) }
    fun toggleVisited() = updateFilters { it.copy(visitedOnly = !it.visitedOnly) }
    fun toggleWantToGo() = updateFilters { it.copy(wantToGoOnly = !it.wantToGoOnly) }
    fun clearFilters() { updateFilters { MapFilters() } }

    fun toggleSaved(placeId: String) {
        viewModelScope.launch {
            when (val result = repository.toggleSaved(placeId)) {
                is RepositoryResult.Success -> _uiState.update { it.copy(saveErrorMessage = null) }
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(saveErrorMessage = result.error.toUserMessage())
                }
            }
        }
    }

    fun dismissSaveError() {
        _uiState.update { it.copy(saveErrorMessage = null) }
    }

    fun onCameraIdle(viewport: MapViewport) {
        if (!hasFetchedBounds) {
            appliedViewport.value = viewport
            savedStateHandle.writeViewport("map.applied", viewport)
            savedStateHandle.writeViewport("map.camera", viewport)
            _uiState.update { it.copy(cameraViewport = viewport, showSearchThisArea = false) }
            fetchBounds(viewport)
            return
        }
        val shouldSearch = viewportMovedEnough(checkNotNull(appliedViewport.value), viewport)
        savedStateHandle.writeViewport("map.camera", viewport)
        _uiState.update {
            it.copy(cameraViewport = viewport, showSearchThisArea = shouldSearch)
        }
    }

    fun searchThisArea() {
        val viewport = _uiState.value.cameraViewport ?: return
        appliedViewport.value = viewport
        savedStateHandle.writeViewport("map.applied", viewport)
        _uiState.update { it.copy(showSearchThisArea = false) }
        fetchBounds(viewport)
    }

    /** Loads a default area when the map camera has not produced an idle viewport yet. */
    fun bootstrapDefaultAreaIfNeeded(viewport: MapViewport) {
        if (hasFetchedBounds) return
        appliedViewport.value = viewport
        savedStateHandle.writeViewport("map.applied", viewport)
        savedStateHandle.writeViewport("map.camera", viewport)
        _uiState.update { it.copy(cameraViewport = viewport, showSearchThisArea = false) }
        fetchBounds(viewport)
    }

    fun onLocationFound(latitude: Double, longitude: Double) {
        _uiState.update {
            it.copy(
                userLocation = latitude to longitude,
                locationMessage = null,
                cameraRequest = MapCameraRequest(
                    id = requestIds.incrementAndGet(),
                    latitude = latitude,
                    longitude = longitude,
                    zoom = 14.5f,
                ),
            )
        }
        viewModelScope.launch {
            when (val result = repository.nearby(
                latitude,
                longitude,
                category = filters.value.category,
                minRating = if (filters.value.highlyRatedOnly) 9.0 else null,
            )) {
                is RepositoryResult.Success -> _uiState.update { it.copy(nearbyPlaces = result.value) }
                is RepositoryResult.Failure -> _uiState.update { it.copy(locationMessage = result.error.toUserMessage()) }
            }
        }
    }

    fun onLocationUnavailable(message: String) {
        _uiState.update { it.copy(locationMessage = message) }
    }

    fun dismissLocationMessage() {
        _uiState.update { it.copy(locationMessage = null) }
    }

    fun consumeCameraRequest(id: Long) {
        _uiState.update { state ->
            if (state.cameraRequest?.id == id) state.copy(cameraRequest = null) else state
        }
    }

    fun retryBounds() {
        appliedViewport.value?.let(::fetchBounds)
    }

    fun retryFriendMetrics() {
        val places = boundsPlaces.value
        if (places.isEmpty()) {
            retryBounds()
            return
        }
        val requestId = boundsRequestId
        viewModelScope.launch {
            enrichFriendMetrics(requestId, places, holdPlacesUntilReady = filters.value.friendsVisitedOnly)
        }
    }

    private fun updateFilters(transform: (MapFilters) -> MapFilters) {
        val previous = filters.value
        val updated = transform(previous)
        filters.value = updated
        savedStateHandle["map.category"] = updated.category?.name
        savedStateHandle["map.highlyRated"] = updated.highlyRatedOnly
        savedStateHandle["map.friendsVisited"] = updated.friendsVisitedOnly
        savedStateHandle["map.visited"] = updated.visitedOnly
        savedStateHandle["map.wantToGo"] = updated.wantToGoOnly
        if (updated.category != previous.category ||
            updated.highlyRatedOnly != previous.highlyRatedOnly
        ) {
            appliedViewport.value?.let(::fetchBounds)
        }
    }

    private fun fetchBounds(viewport: MapViewport) {
        hasFetchedBounds = true
        boundsRequestJob?.cancel()
        val requestId = ++boundsRequestId
        val requestFilters = filters.value
        boundsRequestJob = viewModelScope.launch {
            val friendsFilter = requestFilters.friendsVisitedOnly
            _uiState.update { it.copy(isLoading = true, boundsErrorMessage = null) }
            if (friendsFilter) {
                friendMetricsSnapshot.update { it.copy(loading = true, errorMessage = null) }
            }
            when (val result = repository.refreshBounds(
                west = viewport.west,
                south = viewport.south,
                east = viewport.east,
                north = viewport.north,
                category = requestFilters.category,
                minRating = if (requestFilters.highlyRatedOnly) 9.0 else null,
            )) {
                is RepositoryResult.Success -> {
                    if (requestId != boundsRequestId) return@launch
                    val places = result.value
                    if (!friendsFilter) {
                        boundsPlaces.value = places
                        _uiState.update { it.copy(isLoading = false) }
                    }
                    enrichFriendMetrics(requestId, places, holdPlacesUntilReady = friendsFilter)
                }
                is RepositoryResult.Failure -> {
                    if (requestId != boundsRequestId) return@launch
                    friendMetricsSnapshot.update { it.copy(loading = false) }
                    _uiState.update {
                        it.copy(isLoading = false, boundsErrorMessage = result.error.toUserMessage())
                    }
                }
            }
        }
    }

    private suspend fun enrichFriendMetrics(
        requestId: Long,
        places: List<Place>,
        holdPlacesUntilReady: Boolean,
    ) {
        if (places.isEmpty()) {
            if (requestId != boundsRequestId) return
            if (holdPlacesUntilReady) {
                boundsPlaces.value = places
            }
            friendMetricsSnapshot.value = FriendMetricsSnapshot()
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        friendMetricsSnapshot.update { it.copy(loading = true, errorMessage = null) }
        when (val metrics = repository.loadFriendMetrics(places.map { it.id })) {
            is RepositoryResult.Success -> {
                if (requestId != boundsRequestId) return
                if (holdPlacesUntilReady) {
                    boundsPlaces.value = places
                }
                friendMetricsSnapshot.value = FriendMetricsSnapshot(byPlaceId = metrics.value)
                _uiState.update { it.copy(isLoading = false) }
            }
            is RepositoryResult.Failure -> {
                if (requestId != boundsRequestId) return
                friendMetricsSnapshot.update {
                    it.copy(loading = false, errorMessage = metrics.error.toUserMessage())
                }
                if (!holdPlacesUntilReady) {
                    friendMetricsSnapshot.update { snapshot ->
                        snapshot.copy(byPlaceId = emptyMap())
                    }
                }
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private data class MapCore(
        val places: List<Place>,
        val visited: Set<String>,
        val saved: Set<String>,
        val filters: MapFilters,
        val viewport: MapViewport?,
    )

    private data class FriendMetricsSnapshot(
        val byPlaceId: Map<String, SavedFriendMetrics> = emptyMap(),
        val loading: Boolean = false,
        val errorMessage: String? = null,
    )

    private data class MapData(
        val places: List<Place>,
        val visiblePlaces: List<Place>?,
        val savedPlaceIds: Set<String>,
        val visitedPlaceIds: Set<String>,
        val filters: MapFilters,
        val viewport: MapViewport?,
        val friendMetrics: FriendMetricsSnapshot,
    )
}

private fun SavedStateHandle.readViewport(prefix: String): MapViewport? {
    val north = get<Double>("$prefix.north") ?: return null
    val east = get<Double>("$prefix.east") ?: return null
    val south = get<Double>("$prefix.south") ?: return null
    val west = get<Double>("$prefix.west") ?: return null
    val centerLatitude = get<Double>("$prefix.centerLatitude") ?: return null
    val centerLongitude = get<Double>("$prefix.centerLongitude") ?: return null
    val zoom = get<Float>("$prefix.zoom") ?: return null
    return MapViewport(north, east, south, west, centerLatitude, centerLongitude, zoom)
}

private fun SavedStateHandle.writeViewport(prefix: String, viewport: MapViewport) {
    this["$prefix.north"] = viewport.north
    this["$prefix.east"] = viewport.east
    this["$prefix.south"] = viewport.south
    this["$prefix.west"] = viewport.west
    this["$prefix.centerLatitude"] = viewport.centerLatitude
    this["$prefix.centerLongitude"] = viewport.centerLongitude
    this["$prefix.zoom"] = viewport.zoom
}
