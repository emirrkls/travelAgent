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

        assertEquals(1, repository.calls.size)
        assertEquals("bodrum", repository.calls.single().search)
        assertEquals(PlaceCategory.BEACH, repository.calls.single().category)
        assertEquals(repository.places.value, viewModel.uiState.value.results)
    }

    @Test
    fun highlyRatedSendsMinRatingAndResetsToPageZero() = runTest(dispatcher) {
        val repository = RecordingSearchRepository()
        val viewModel = SearchViewModel(repository)
        advanceUntilIdle()
        viewModel.toggleHighlyRated()
        advanceUntilIdle()

        assertEquals(9.0, repository.calls.last().minRating)
        assertEquals(0, repository.calls.last().page)
        assertTrue(viewModel.uiState.value.filters.highlyRatedOnly)
    }

    @Test
    fun savedFilterUsesLocalCatalogWithoutRemoteCallBurst() = runTest(dispatcher) {
        val repository = RecordingSearchRepository()
        val place = repository.places.value.first()
        repository.saved.value = linkedSetOf(place.id)
        val viewModel = SearchViewModel(repository)
        advanceUntilIdle()
        val remoteBefore = repository.calls.size
        viewModel.toggleSavedOnly()
        advanceUntilIdle()

        assertEquals(remoteBefore, repository.calls.size)
        assertEquals(listOf(place.id), viewModel.uiState.value.results.map { it.id })
        assertEquals(SearchEmptyReason.NOTHING_SAVED.takeIf { false }, viewModel.uiState.value.emptyReason)
    }

    @Test
    fun savedFilterEmptyWhenNothingSaved() = runTest(dispatcher) {
        val repository = RecordingSearchRepository()
        val viewModel = SearchViewModel(repository)
        advanceUntilIdle()
        viewModel.toggleSavedOnly()
        advanceUntilIdle()
        assertEquals(SearchEmptyReason.NOTHING_SAVED, viewModel.uiState.value.emptyReason)
    }

    @Test
    fun clearFiltersRestoresDefaultRemoteSearch() = runTest(dispatcher) {
        val repository = RecordingSearchRepository()
        val viewModel = SearchViewModel(repository)
        advanceUntilIdle()
        viewModel.toggleSavedOnly()
        viewModel.setCategory(PlaceCategory.HOTEL)
        advanceUntilIdle()
        viewModel.clearFilters()
        advanceUntilIdle()

        assertEquals(SearchFilters(), viewModel.uiState.value.filters)
        assertTrue(repository.calls.isNotEmpty())
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

        assertTrue("old" in repository.calls.mapNotNull { it.search })
        assertTrue("new" in repository.completed)
        assertTrue("old" !in repository.completed)
        assertEquals("new", viewModel.uiState.value.query)
    }

    @Test
    fun saveTogglePropagatesThroughRepository() = runTest(dispatcher) {
        val repository = RecordingSearchRepository()
        val placeId = repository.places.value.first().id
        val viewModel = SearchViewModel(repository)
        advanceUntilIdle()
        viewModel.toggleSaved(placeId)
        advanceUntilIdle()
        assertTrue(placeId in repository.saved.value)
        assertTrue(placeId in viewModel.uiState.value.savedPlaceIds)
    }
}

private data class SearchCall(
    val search: String?,
    val category: PlaceCategory?,
    val minRating: Double?,
    val page: Int,
)

private class RecordingSearchRepository : TestTravelRepository() {
    val calls = mutableListOf<SearchCall>()
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
        calls += SearchCall(search, category, minRating, page)
        if (search == slowQuery) delay(1_000)
        search?.let(completed::add)
        return if (fail) {
            RepositoryResult.Failure(TravelError.Offline())
        } else {
            RepositoryResult.Success(PlacePage(places.value, page, 1, places.value.size.toLong(), false))
        }
    }
}
