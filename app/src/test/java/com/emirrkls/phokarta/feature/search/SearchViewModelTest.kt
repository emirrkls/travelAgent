package com.emirrkls.phokarta.feature.search

import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.data.PlacePage
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.model.PlaceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun queryIsDebouncedAndCategoryIsSentToBackend() = runTest(dispatcher) {
        val repository = RecordingSearchRepository()
        val viewModel = SearchViewModel(repository)
        runCurrent()
        viewModel.setQuery("bo")
        viewModel.setQuery("bodrum")
        viewModel.setCategory(PlaceCategory.BEACH)
        advanceTimeBy(299)
        runCurrent()
        assertTrue(repository.calls.isEmpty())
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf("bodrum" to PlaceCategory.BEACH), repository.calls)
        assertEquals(repository.places.value, viewModel.uiState.value.results)
    }

    @Test
    fun errorKeepsLastGoodResultsAndRetryQueriesAgain() = runTest(dispatcher) {
        val repository = RecordingSearchRepository()
        val viewModel = SearchViewModel(repository)
        advanceUntilIdle()
        val previous = viewModel.uiState.value.results
        repository.fail = true
        viewModel.setQuery("failed")
        advanceUntilIdle()

        assertEquals(previous, viewModel.uiState.value.results)
        assertNotNull(viewModel.uiState.value.errorMessage)
        val callsBeforeRetry = repository.calls.size
        viewModel.retry()
        advanceUntilIdle()
        assertEquals(callsBeforeRetry + 1, repository.calls.size)
    }

    @Test
    fun newerQueryCancelsObsoleteInFlightSearch() = runTest(dispatcher) {
        val repository = RecordingSearchRepository()
        val viewModel = SearchViewModel(repository)
        advanceUntilIdle()

        repository.slowQuery = "old"
        viewModel.setQuery("old")
        advanceTimeBy(300)
        runCurrent()
        viewModel.setQuery("new")
        advanceTimeBy(300)
        advanceUntilIdle()

        assertTrue("old" in repository.calls.mapNotNull { it.first })
        assertTrue("new" in repository.completed)
        assertTrue("old" !in repository.completed)
        assertEquals("new", viewModel.uiState.value.query)
    }
}

private class RecordingSearchRepository : TestTravelRepository() {
    val calls = mutableListOf<Pair<String?, PlaceCategory?>>()
    val completed = mutableListOf<String>()
    var fail = false
    var slowQuery: String? = null

    override suspend fun listPlaces(
        category: PlaceCategory?,
        city: String?,
        search: String?,
        minRating: Double?,
        sort: String,
        page: Int,
        size: Int,
    ): RepositoryResult<PlacePage> {
        calls += search to category
        if (search == slowQuery) delay(1_000)
        search?.let(completed::add)
        return if (fail) {
            RepositoryResult.Failure(TravelError.Offline())
        } else {
            RepositoryResult.Success(PlacePage(places.value, 0, 1, places.value.size.toLong(), false))
        }
    }
}
