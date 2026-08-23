package com.emirrkls.phokarta.feature.place

import androidx.lifecycle.SavedStateHandle
import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.model.PublicReview
import com.emirrkls.phokarta.core.model.PublicReviewAuthor
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
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class PlaceReviewsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads first page and appends next page without duplicates`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = TestTravelRepository()
        val all = (1..25).map { review("r$it", placeId) }
        repository.publicReviewsByPlace.value = mapOf(placeId to all)

        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        assertEquals(20, viewModel.uiState.value.reviews.size)
        assertTrue(viewModel.uiState.value.hasNext)

        viewModel.loadNextPage()
        advanceUntilIdle()

        assertEquals(25, viewModel.uiState.value.reviews.size)
        assertEquals(25, viewModel.uiState.value.reviews.map { it.id }.distinct().size)
        assertFalse(viewModel.uiState.value.hasNext)
    }

    @Test
    fun `duplicate next page calls are ignored while loading`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = TestTravelRepository()
        repository.publicReviewsByPlace.value = mapOf(
            placeId to (1..25).map { review("r$it", placeId) },
        )
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        viewModel.loadNextPage()
        viewModel.loadNextPage()
        advanceUntilIdle()

        assertEquals(25, viewModel.uiState.value.reviews.size)
    }

    private fun TestScope.createViewModel(placeId: String, repository: TestTravelRepository): PlaceReviewsViewModel {
        val viewModel = PlaceReviewsViewModel(SavedStateHandle(mapOf("placeId" to placeId)), repository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect { } }
        return viewModel
    }

    private fun seedPlaceId(): String =
        com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource.mockPlaces.first().id

    private fun review(id: String, placeId: String) = PublicReview(
        id = id,
        placeId = placeId,
        author = PublicReviewAuthor("u1", "demo", "Demo User", null),
        overallScore = 8.5,
        publicReview = "Review $id",
        visitDate = LocalDate.of(2026, 8, 1),
    )
}
