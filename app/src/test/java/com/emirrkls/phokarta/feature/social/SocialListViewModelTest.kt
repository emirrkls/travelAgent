package com.emirrkls.phokarta.feature.social

import androidx.lifecycle.SavedStateHandle
import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.model.RelationshipState
import com.emirrkls.phokarta.core.model.UserSummary
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
class SocialListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val otherId = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsFollowersAndUpdatesFollowState() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.followers += UserSummary(
            otherId,
            "Ahmet",
            "ahmetgoes",
            "",
            RelationshipState(false, true, false),
        )
        val viewModel = SocialListViewModel(
            SavedStateHandle(mapOf("kind" to "followers")),
            repository,
        )
        advanceUntilIdle()
        assertEquals(com.emirrkls.phokarta.R.string.followers, viewModel.uiState.value.title)
        assertEquals(1, viewModel.uiState.value.items.size)

        viewModel.toggleFollow(otherId)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.items.first().relationship!!.isFriend)
        assertEquals(listOf(otherId), repository.followCalls)
    }

    @Test
    fun friendsListUsesFriendsSource() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.friends += UserSummary(
            otherId,
            "Ahmet",
            "ahmetgoes",
            "",
            RelationshipState(true, true, true),
        )
        val viewModel = SocialListViewModel(
            SavedStateHandle(mapOf("kind" to "friends")),
            repository,
        )
        advanceUntilIdle()
        assertEquals(com.emirrkls.phokarta.R.string.friends, viewModel.uiState.value.title)
        assertEquals(otherId, viewModel.uiState.value.items.single().id)
    }
}
