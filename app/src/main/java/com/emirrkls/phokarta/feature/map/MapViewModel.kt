package com.emirrkls.phokarta.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val trustedOnly: Boolean = false,
    val visitedOnly: Boolean = false,
    val wantToGoOnly: Boolean = false,
) {
    val activeCount: Int
        get() = listOfNotNull(category).size + listOf(
            highlyRatedOnly,
            trustedOnly,
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
)

internal fun filterMapPlaces(
    places: List<Place>,
    filters: MapFilters,
    visitedPlaceIds: Set<String>,
    savedPlaceIds: Set<String>,
    viewport: MapViewport?,
): List<Place> = places.filter { place ->
    (filters.category == null || place.category == filters.category) &&
        (!filters.highlyRatedOnly || place.communityScore >= 9.0) &&
        (!filters.trustedOnly || place.friendsScore >= 9.0) &&
        (!filters.visitedOnly || place.id in visitedPlaceIds) &&
        (!filters.wantToGoOnly || place.id in savedPlaceIds) &&
        (viewport == null || viewport.contains(place))
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
            trustedOnly = savedStateHandle["map.trusted"] ?: false,
            visitedOnly = savedStateHandle["map.visited"] ?: false,
            wantToGoOnly = savedStateHandle["map.wantToGo"] ?: false,
        ),
    )
    private val appliedViewport = MutableStateFlow(restoredAppliedViewport)
    private val requestIds = AtomicLong()
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
                repository.observePlaces(),
                repository.observeVisits(),
                repository.observeSavedPlaceIds(),
                filters,
                appliedViewport,
            ) { places, visits, saved, currentFilters, viewport ->
                val visited = visits.mapTo(mutableSetOf()) { it.placeId }
                MapData(
                    places = places,
                    visiblePlaces = filterMapPlaces(places, currentFilters, visited, saved, viewport),
                    savedPlaceIds = saved,
                    visitedPlaceIds = visited,
                    filters = currentFilters,
                    viewport = viewport,
                )
            }.collect { data ->
                _uiState.update { current ->
                    current.copy(
                        allPlaces = data.places,
                        visiblePlaces = data.visiblePlaces,
                        savedPlaceIds = data.savedPlaceIds,
                        visitedPlaceIds = data.visitedPlaceIds,
                        filters = data.filters,
                        selectedPlaceId = current.selectedPlaceId?.takeIf { selected ->
                            data.visiblePlaces.any { it.id == selected }
                        },
                        appliedViewport = data.viewport,
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
    fun toggleTrusted() = updateFilters { it.copy(trustedOnly = !it.trustedOnly) }
    fun toggleVisited() = updateFilters { it.copy(visitedOnly = !it.visitedOnly) }
    fun toggleWantToGo() = updateFilters { it.copy(wantToGoOnly = !it.wantToGoOnly) }
    fun clearFilters() { updateFilters { MapFilters() } }

    fun toggleSaved(placeId: String) {
        viewModelScope.launch { repository.toggleSaved(placeId) }
    }

    fun onCameraIdle(viewport: MapViewport) {
        if (appliedViewport.value == null) {
            appliedViewport.value = viewport
            savedStateHandle.writeViewport("map.applied", viewport)
            savedStateHandle.writeViewport("map.camera", viewport)
            _uiState.update { it.copy(cameraViewport = viewport, showSearchThisArea = false) }
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

    private fun updateFilters(transform: (MapFilters) -> MapFilters) {
        val updated = transform(filters.value)
        filters.value = updated
        savedStateHandle["map.category"] = updated.category?.name
        savedStateHandle["map.highlyRated"] = updated.highlyRatedOnly
        savedStateHandle["map.trusted"] = updated.trustedOnly
        savedStateHandle["map.visited"] = updated.visitedOnly
        savedStateHandle["map.wantToGo"] = updated.wantToGoOnly
    }

    private data class MapData(
        val places: List<Place>,
        val visiblePlaces: List<Place>,
        val savedPlaceIds: Set<String>,
        val visitedPlaceIds: Set<String>,
        val filters: MapFilters,
        val viewport: MapViewport?,
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
