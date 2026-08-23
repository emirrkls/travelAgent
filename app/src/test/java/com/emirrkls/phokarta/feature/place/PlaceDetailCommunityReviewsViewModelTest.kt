package com.emirrkls.phokarta.feature.place

import androidx.lifecycle.SavedStateHandle
import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.data.TravelError
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class PlaceDetailCommunityReviewsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads preview community reviews`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = TestTravelRepository().apply {
            publicReviewsByPlace.value = mapOf(
                placeId to listOf(review("r1", placeId), review("r2", placeId)),
            )
        }
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.communityReviews.reviews.size)
        assertEquals(2L, viewModel.uiState.value.communityReviews.totalElements)
        assertNull(viewModel.uiState.value.communityReviews.errorMessage)
    }

    @Test
    fun `community review failure does not clear place`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = TestTravelRepository().apply {
            publicReviewsError = TravelError.Offline()
        }
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.place)
        assertNotNull(viewModel.uiState.value.communityReviews.errorMessage)
        assertTrue(viewModel.uiState.value.communityReviews.reviews.isEmpty())
    }

    @Test
    fun `refresh reloads community reviews`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = TestTravelRepository().apply {
            publicReviewsByPlace.value = mapOf(placeId to listOf(review("r1", placeId)))
        }
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        repository.publicReviewsByPlace.value = mapOf(
            placeId to listOf(review("r1", placeId), review("r2", placeId)),
        )
        viewModel.refreshCommunityReviews()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.communityReviews.reviews.size)
    }

    private fun TestScope.createViewModel(placeId: String, repository: TestTravelRepository): PlaceDetailViewModel {
        val viewModel = PlaceDetailViewModel(SavedStateHandle(mapOf("placeId" to placeId)), repository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect { } }
        return viewModel
    }

    private fun seedPlaceId(): String =
        com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource.mockPlaces.first().id

    private fun review(id: String, placeId: String) = PublicReview(
        id = id,
        placeId = placeId,
        author = PublicReviewAuthor("u1", "demo", "Demo User", null),
        overallScore = 9.0,
        publicReview = "Lovely spot.",
        visitDate = LocalDate.of(2026, 8, 1),
    )
}
