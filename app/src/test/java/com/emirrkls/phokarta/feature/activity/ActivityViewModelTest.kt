package com.emirrkls.phokarta.feature.activity

import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.data.ActivityFeedInvalidator
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.model.ActivityAuthor
import com.emirrkls.phokarta.core.model.ActivityEvent
import com.emirrkls.phokarta.core.model.ActivityPlaceSummary
import com.emirrkls.phokarta.core.model.PlaceCategory
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads first page and appends next page without duplicates`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.activityItems.value = (1..25).map { event("v$it") }

        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        assertEquals(20, viewModel.uiState.value.items.size)
        assertTrue(viewModel.uiState.value.hasNext)

        viewModel.loadNextPage()
        advanceUntilIdle()

        assertEquals(25, viewModel.uiState.value.items.size)
        assertEquals(25, viewModel.uiState.value.items.map { it.visitId }.distinct().size)
        assertFalse(viewModel.uiState.value.hasNext)
        assertEquals(listOf(0, 1), repository.requestedActivityPages)
    }

    @Test
    fun `duplicate next page calls are ignored while loading`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.activityItems.value = (1..25).map { event("v$it") }
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.loadNextPage()
        viewModel.loadNextPage()
        advanceUntilIdle()

        assertEquals(25, viewModel.uiState.value.items.size)
        assertEquals(1, repository.requestedActivityPages.count { it == 1 })
    }

    @Test
    fun `next page failure preserves existing items`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.activityItems.value = (1..25).map { event("v$it") }
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        repository.activityLoadMoreError = TravelError.Offline()
        viewModel.loadNextPage()
        advanceUntilIdle()

        assertEquals(20, viewModel.uiState.value.items.size)
        assertFalse(viewModel.uiState.value.isLoadingMore)
        assertTrue(viewModel.uiState.value.loadMoreErrorMessage != null)
    }

    @Test
    fun `retry reloads first page after initial failure`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.activityError = TravelError.Offline()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.items.isEmpty())
        assertTrue(viewModel.uiState.value.errorMessage != null)

        repository.activityError = null
        repository.activityItems.value = listOf(event("v1"))
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.items.size)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `refresh replaces list and hasNext false stops loading`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.activityItems.value = (1..5).map { event("v$it") }
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        repository.activityItems.value = listOf(event("fresh"))
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf("fresh"), viewModel.uiState.value.items.map { it.visitId })
        assertFalse(viewModel.uiState.value.hasNext)

        viewModel.loadNextPage()
        advanceUntilIdle()
        assertEquals(0, repository.requestedActivityPages.count { it == 1 })
    }

    @Test
    fun `resume refreshes when feed was invalidated`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        val invalidator = ActivityFeedInvalidator()
        repository.activityItems.value = listOf(event("old"))
        val viewModel = createViewModel(repository, invalidator)
        advanceUntilIdle()

        repository.activityItems.value = listOf(event("new"))
        invalidator.markDirty()
        viewModel.onScreenResumed()
        advanceUntilIdle()

        assertEquals(listOf("new"), viewModel.uiState.value.items.map { it.visitId })
    }

    private fun TestScope.createViewModel(
        repository: TestTravelRepository,
        invalidator: ActivityFeedInvalidator = ActivityFeedInvalidator(),
    ): ActivityViewModel {
        val viewModel = ActivityViewModel(repository, invalidator)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect { } }
        return viewModel
    }

    private fun event(id: String) = ActivityEvent(
        visitId = id,
        author = ActivityAuthor("u1", "demo", "Demo User", null),
        place = ActivityPlaceSummary(
            id = "p1",
            name = "Test Place",
            category = PlaceCategory.BEACH,
            city = "Bodrum",
            coverImage = "",
        ),
        overallScore = 8.5,
        publicReview = "Review $id",
        visitDate = LocalDate.of(2026, 8, 1),
    )
}
