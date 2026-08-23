package com.emirrkls.phokarta.feature.social

import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.model.UserSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class UserSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun debounceAndLoadsResultsExcludingSelf() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.searchableUsers += listOf(
            UserSummary(repository.currentUser.id, "Me", "me", "", null),
            UserSummary("22222222-2222-2222-2222-222222222222", "Ahmet", "ahmetgoes", "", null),
        )
        val viewModel = UserSearchViewModel(repository)

        viewModel.setQuery("ahm")
        advanceTimeBy(299)
        assertTrue(viewModel.uiState.value.items.isEmpty())
        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.items.size)
        assertEquals("ahmetgoes", viewModel.uiState.value.items.first().username)
        assertFalse(viewModel.uiState.value.items.any { it.id == repository.currentUser.id })
    }

    @Test
    fun staleQueryIsCancelledByNewerQuery() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.searchableUsers += UserSummary(
            "22222222-2222-2222-2222-222222222222",
            "Ahmet",
            "ahmetgoes",
            "",
            null,
        )
        repository.searchableUsers += UserSummary(
            "33333333-3333-3333-3333-333333333333",
            "Selin",
            "selinmaps",
            "",
            null,
        )
        val viewModel = UserSearchViewModel(repository)
        viewModel.setQuery("ahm")
        advanceTimeBy(100)
        viewModel.setQuery("sel")
        advanceTimeBy(300)
        advanceUntilIdle()
        assertEquals("selinmaps", viewModel.uiState.value.items.single().username)
    }

    @Test
    fun emptyQueryClearsResults() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.searchableUsers += UserSummary(
            "22222222-2222-2222-2222-222222222222",
            "Ahmet",
            "ahmetgoes",
            "",
            null,
        )
        val viewModel = UserSearchViewModel(repository)
        viewModel.setQuery("ahm")
        advanceTimeBy(300)
        advanceUntilIdle()
        viewModel.setQuery("")
        advanceTimeBy(300)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.items.isEmpty())
    }

    @Test
    fun retryAfterError() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.searchableUsers += UserSummary(
            "22222222-2222-2222-2222-222222222222",
            "Ahmet",
            "ahmetgoes",
            "",
            null,
        )
        repository.searchError = TravelError.Offline()
        val viewModel = UserSearchViewModel(repository)
        viewModel.setQuery("ahm")
        advanceTimeBy(300)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.error != null)
        repository.searchError = null
        viewModel.retry()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.items.size)
    }
}
