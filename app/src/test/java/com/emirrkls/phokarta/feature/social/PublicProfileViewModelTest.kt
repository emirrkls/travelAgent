package com.emirrkls.phokarta.feature.social

import androidx.lifecycle.SavedStateHandle
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.model.PublicUserProfile
import com.emirrkls.phokarta.core.model.RelationshipState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
class PublicProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val userId = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun followTransitionsFollowsYouToFriends() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.publicProfiles[userId] = profile(
            RelationshipState(isFollowing = false, followsYou = true, isFriend = false),
        )
        val viewModel = PublicProfileViewModel(SavedStateHandle(mapOf("userId" to userId)), repository)
        advanceUntilIdle()

        viewModel.toggleFollow()
        advanceUntilIdle()

        val relationship = viewModel.uiState.value.profile!!.relationship!!
        assertTrue(relationship.isFollowing)
        assertTrue(relationship.followsYou)
        assertTrue(relationship.isFriend)
        assertEquals(listOf(userId), repository.followCalls)
    }

    @Test
    fun unfollowFriendLeavesFollowsYou() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.publicProfiles[userId] = profile(
            RelationshipState(isFollowing = true, followsYou = true, isFriend = true),
        )
        val viewModel = PublicProfileViewModel(SavedStateHandle(mapOf("userId" to userId)), repository)
        advanceUntilIdle()

        viewModel.toggleFollow()
        advanceUntilIdle()

        val relationship = viewModel.uiState.value.profile!!.relationship!!
        assertFalse(relationship.isFollowing)
        assertTrue(relationship.followsYou)
        assertFalse(relationship.isFriend)
        assertEquals(listOf(userId), repository.unfollowCalls)
    }

    @Test
    fun duplicateTapWhileMutatingIsIgnored() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.publicProfiles[userId] = profile(
            RelationshipState(isFollowing = false, followsYou = false, isFriend = false),
        )
        val viewModel = PublicProfileViewModel(SavedStateHandle(mapOf("userId" to userId)), repository)
        advanceUntilIdle()

        viewModel.toggleFollow()
        viewModel.toggleFollow()
        advanceUntilIdle()

        assertEquals(1, repository.followCalls.size)
    }

    @Test
    fun rollbackOnFailure() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        val initial = RelationshipState(false, true, false)
        repository.publicProfiles[userId] = profile(initial)
        repository.followError = TravelError.Offline()
        val viewModel = PublicProfileViewModel(SavedStateHandle(mapOf("userId" to userId)), repository)
        advanceUntilIdle()

        viewModel.toggleFollow()
        advanceUntilIdle()

        assertEquals(initial, viewModel.uiState.value.profile!!.relationship)
        assertTrue(viewModel.uiState.value.actionErrorMessage != null)
    }

    @Test
    fun followDoesNotReplacePublicProfileCountSource() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.publicProfiles[userId] = profile(
            RelationshipState(isFollowing = false, followsYou = true, isFriend = false),
        )
        val viewModel = PublicProfileViewModel(SavedStateHandle(mapOf("userId" to userId)), repository)
        advanceUntilIdle()

        assertEquals(10L, viewModel.uiState.value.profile!!.followerCount)
        assertEquals(2L, viewModel.uiState.value.profile!!.followingCount)

        viewModel.toggleFollow()
        advanceUntilIdle()

        // Public profile still mutates its own loaded counters, not owner /me overlay.
        assertEquals(11L, viewModel.uiState.value.profile!!.followerCount)
        assertEquals(2L, viewModel.uiState.value.profile!!.followingCount)
    }

    @Test
    fun followBlockedRelationshipShowsGenericUnavailable() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.publicProfiles[userId] = profile(
            RelationshipState(isFollowing = false, followsYou = false, isFriend = false),
        )
        repository.followError = TravelError.Conflict()
        val viewModel = PublicProfileViewModel(SavedStateHandle(mapOf("userId" to userId)), repository)
        advanceUntilIdle()

        viewModel.toggleFollow()
        advanceUntilIdle()

        assertEquals(R.string.relationship_unavailable, viewModel.uiState.value.actionErrorMessage)
        assertFalse(viewModel.uiState.value.profile!!.relationship!!.isFollowing)
    }

    private fun profile(relationship: RelationshipState) = PublicUserProfile(
        id = userId,
        username = "ahmetgoes",
        displayName = "Ahmet",
        avatarUrl = "",
        bio = "",
        cityCount = 1,
        countryCount = 1,
        followerCount = 10,
        followingCount = 2,
        friendCount = if (relationship.isFriend) 1 else 0,
        relationship = relationship,
    )
}
