package com.emirrkls.phokarta.feature.map

import androidx.lifecycle.SavedStateHandle
import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.Visit
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun initialBoundsPropagatesFiltersAndMergesLocalState() = runTest(dispatcher) {
        val repository = RecordingMapRepository()
        val first = repository.places.value.first()
        repository.saved.value = setOf(first.id)
        repository.visits.value = listOf(visit(first.id))
        val viewModel = MapViewModel(repository, SavedStateHandle())
        viewModel.selectCategory(first.category)
        viewModel.toggleHighlyRated()
        viewModel.onCameraIdle(viewport())
        advanceUntilIdle()

        assertEquals(1, repository.boundsCalls.size)
        assertEquals(first.category, repository.boundsCalls.single().category)
        assertEquals(9.0, repository.boundsCalls.single().minRating)
        assertTrue(first.id in viewModel.uiState.value.savedPlaceIds)
        assertTrue(first.id in viewModel.uiState.value.visitedPlaceIds)
    }

    @Test
    fun searchAreaFetchesAndSelectionSurvivesRefresh() = runTest(dispatcher) {
        val repository = RecordingMapRepository()
        val viewModel = MapViewModel(repository, SavedStateHandle())
        viewModel.onCameraIdle(viewport())
        advanceUntilIdle()
        val selected = viewModel.uiState.value.visiblePlaces.first()
        viewModel.selectPlace(selected.id)
        viewModel.onCameraIdle(viewport().copy(centerLatitude = 38.0, centerLongitude = 29.0))
        assertTrue(viewModel.uiState.value.showSearchThisArea)
        viewModel.searchThisArea()
        advanceUntilIdle()

        assertEquals(2, repository.boundsCalls.size)
        assertEquals(selected.id, viewModel.uiState.value.selectedPlaceId)
    }

    @Test
    fun remoteErrorRetainsLastPlaces() = runTest(dispatcher) {
        val repository = RecordingMapRepository()
        val viewModel = MapViewModel(repository, SavedStateHandle())
        viewModel.onCameraIdle(viewport())
        advanceUntilIdle()
        val before = viewModel.uiState.value.visiblePlaces
        repository.failBounds = true
        viewModel.onCameraIdle(viewport().copy(centerLatitude = 38.0, centerLongitude = 29.0))
        viewModel.searchThisArea()
        advanceUntilIdle()

        assertEquals(before, viewModel.uiState.value.visiblePlaces)
        assertFalse(viewModel.uiState.value.boundsErrorMessage.isNullOrBlank())
    }

    @Test
    fun newerBoundsRequestCancelsObsoleteResponse() = runTest(dispatcher) {
        val repository = RecordingMapRepository().apply { slowFirstBounds = true }
        val viewModel = MapViewModel(repository, SavedStateHandle())
        viewModel.onCameraIdle(viewport())
        runCurrent()

        viewModel.selectCategory(PlaceCategory.BEACH)
        advanceUntilIdle()

        assertEquals(2, repository.boundsCalls.size)
        assertEquals(listOf(PlaceCategory.BEACH), repository.completedCategories)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun filteredSelectionIsRemovedFromSavedState() = runTest(dispatcher) {
        val repository = RecordingMapRepository()
        val handle = SavedStateHandle()
        val viewModel = MapViewModel(repository, handle)
        viewModel.onCameraIdle(viewport())
        advanceUntilIdle()
        val selected = viewModel.uiState.value.visiblePlaces.first()
        viewModel.selectPlace(selected.id)

        viewModel.selectCategory(PlaceCategory.entries.first { it != selected.category })
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.selectedPlaceId)
        assertEquals(null, handle.get<String>("map.selectedPlaceId"))
    }

    private fun viewport() = MapViewport(40.0, 32.0, 34.0, 24.0, 37.0, 28.0, 9f)
    private fun visit(placeId: String) = Visit("v1", "u1", placeId, LocalDate.now(), 8.0, emptyMap(), "", "")
}

private class RecordingMapRepository : TestTravelRepository() {
    data class Call(val category: PlaceCategory?, val minRating: Double?)
    val boundsCalls = mutableListOf<Call>()
    val completedCategories = mutableListOf<PlaceCategory?>()
    var failBounds = false
    var slowFirstBounds = false

    override suspend fun refreshBounds(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        category: PlaceCategory?,
        minRating: Double?,
    ): RepositoryResult<List<Place>> {
        boundsCalls += Call(category, minRating)
        if (slowFirstBounds && boundsCalls.size == 1) delay(1_000)
        completedCategories += category
        return if (failBounds) RepositoryResult.Failure(TravelError.Offline()) else RepositoryResult.Success(places.value)
    }
}
