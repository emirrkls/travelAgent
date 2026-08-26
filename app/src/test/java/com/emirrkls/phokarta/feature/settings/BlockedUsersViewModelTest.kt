package com.emirrkls.phokarta.feature.settings

import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.model.BlockedUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockedUsersViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val blocked = BlockedUser(
        userId = "22222222-2222-2222-2222-222222222222",
        username = "ahmetgoes",
        displayName = "Ahmet Deniz",
        avatarUrl = null,
        blockedAt = "2026-08-22T10:00:00Z",
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsBlockedUsers() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.blockedUsers += blocked
        val viewModel = BlockedUsersViewModel(repository)
        advanceUntilIdle()

        assertEquals(listOf(blocked), viewModel.uiState.value.items)
    }

    @Test
    fun unblockRemovesUserWithoutRefollow() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.blockedUsers += blocked
        val viewModel = BlockedUsersViewModel(repository)
        advanceUntilIdle()

        viewModel.unblock(blocked.userId)
        advanceUntilIdle()

        assertEquals(listOf(blocked.userId), repository.unblockCalls)
        assertTrue(viewModel.uiState.value.items.isEmpty())
        assertTrue(repository.followCalls.isEmpty())
    }

    @Test
    fun loadFailureShowsRetryableError() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.blockError = TravelError.Offline()
        val viewModel = BlockedUsersViewModel(repository)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.items.isEmpty())
        assertTrue(viewModel.uiState.value.error != null)
    }
}
