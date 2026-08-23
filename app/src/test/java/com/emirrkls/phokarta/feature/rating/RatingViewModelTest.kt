package com.emirrkls.phokarta.feature.rating

import androidx.lifecycle.SavedStateHandle
import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.model.Visit
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RatingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.createViewModel(placeId: String, repository: TestTravelRepository): RatingViewModel {
        val viewModel = RatingViewModel(SavedStateHandle(mapOf("placeId" to placeId)), repository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect { } }
        return viewModel
    }

    @Test
    fun publishAppendsVisitAndMarksPublished() = runTest(dispatcher) {
        val placeId = com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource.mockPlaces.first().id
        val repository = TestTravelRepository()
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        viewModel.publish()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.published)
        assertEquals(1, repository.visits.value.size)
        assertEquals(placeId, repository.visits.value.single().placeId)
    }

    @Test
    fun publishFailurePreservesDraft() = runTest(dispatcher) {
        val placeId = com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource.mockPlaces.first().id
        val repository = FailingRepository()
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        viewModel.setReview("Keep this note")
        viewModel.publish()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.published)
        assertEquals("Keep this note", viewModel.uiState.value.review)
        assertTrue(viewModel.uiState.value.publishError != null)
    }

    @Test
    fun futureVisitDateBlocksPublish() = runTest(dispatcher) {
        val placeId = com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource.mockPlaces.first().id
        val repository = TestTravelRepository()
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        viewModel.setVisitedAt(LocalDate.now().plusDays(2))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canPublish)
        viewModel.publish()
        advanceUntilIdle()
        assertTrue(repository.visits.value.isEmpty())
    }

    private class FailingRepository : TestTravelRepository() {
        override suspend fun publishVisit(visit: Visit): RepositoryResult<Visit> =
            RepositoryResult.Failure(TravelError.Offline())
    }
}
