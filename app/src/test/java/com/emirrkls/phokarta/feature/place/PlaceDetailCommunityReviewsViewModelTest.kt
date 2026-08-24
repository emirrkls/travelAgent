package com.emirrkls.phokarta.feature.place

import androidx.lifecycle.SavedStateHandle
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.model.ActivityScope
import com.emirrkls.phokarta.core.model.FriendPlaceSummary
import com.emirrkls.phokarta.core.model.FriendPlaceUser
import com.emirrkls.phokarta.core.model.PublicReview
import com.emirrkls.phokarta.core.model.PublicReviewAuthor
import com.emirrkls.phokarta.ui.components.CommunityScoreCopy
import com.emirrkls.phokarta.ui.components.FriendsScoreCopy
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

    @Test
    fun `loads friend place summary with nullable score`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = TestTravelRepository().apply {
            friendSummariesByPlace.value = mapOf(
                placeId to FriendPlaceSummary(averageScore = null, friendsVisitedCount = 0, friends = emptyList()),
            )
        }
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.friendSummary.summary?.averageScore)
        assertEquals(0, viewModel.uiState.value.friendSummary.summary?.friendsVisitedCount)
    }

    @Test
    fun `loads friend summary with one friend`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = TestTravelRepository().apply {
            friendSummariesByPlace.value = mapOf(
                placeId to FriendPlaceSummary(
                    averageScore = 9.1,
                    friendsVisitedCount = 1,
                    friends = listOf(friendUser("b")),
                ),
            )
        }
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        assertEquals(9.1, viewModel.uiState.value.friendSummary.summary?.averageScore)
        assertEquals(1, viewModel.uiState.value.friendSummary.summary?.friendsVisitedCount)
        assertEquals(listOf("b"), viewModel.uiState.value.friendSummary.summary?.friends?.map { it.userId })
    }

    @Test
    fun `loads friend summary with many unique friends`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = TestTravelRepository().apply {
            friendSummariesByPlace.value = mapOf(
                placeId to FriendPlaceSummary(
                    averageScore = 8.5,
                    friendsVisitedCount = 3,
                    friends = listOf(friendUser("b"), friendUser("c"), friendUser("d")),
                ),
            )
        }
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.friendSummary.summary?.friendsVisitedCount)
        assertEquals(3, viewModel.uiState.value.friendSummary.summary?.friends?.map { it.userId }?.distinct()?.size)
    }

    @Test
    fun `friend summary failure does not wipe community reviews or place`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = TestTravelRepository().apply {
            publicReviewsByPlace.value = mapOf(placeId to listOf(review("r1", placeId)))
            friendSummaryError = TravelError.Offline()
        }
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.place)
        assertEquals(1, viewModel.uiState.value.communityReviews.reviews.size)
        assertNotNull(viewModel.uiState.value.friendSummary.errorMessage)
        assertNull(viewModel.uiState.value.friendSummary.summary)
    }

    @Test
    fun `refresh friend summary reloads data`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = TestTravelRepository().apply {
            friendSummariesByPlace.value = mapOf(
                placeId to FriendPlaceSummary(null, 0, emptyList()),
            )
        }
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        repository.friendSummariesByPlace.value = mapOf(
            placeId to FriendPlaceSummary(9.1, 1, listOf(friendUser("b"))),
        )
        viewModel.refreshFriendSummary()
        advanceUntilIdle()

        assertEquals(9.1, viewModel.uiState.value.friendSummary.summary?.averageScore)
        assertEquals(1, viewModel.uiState.value.friendSummary.summary?.friendsVisitedCount)
    }

    @Test
    fun `friend and community review scopes stay independent`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = TestTravelRepository().apply {
            publicReviewsByPlace.value = mapOf(placeId to listOf(review("c1", placeId)))
            friendReviewsByPlace.value = mapOf(placeId to listOf(review("f1", placeId, authorId = "friend")))
        }
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        assertEquals(ActivityScope.COMMUNITY, viewModel.uiState.value.activeReviewScope)
        assertEquals(listOf("c1"), viewModel.uiState.value.activeReviews.reviews.map { it.id })

        viewModel.selectReviewScope(ActivityScope.FRIENDS)
        advanceUntilIdle()

        assertEquals(listOf("f1"), viewModel.uiState.value.activeReviews.reviews.map { it.id })
        assertEquals(listOf("c1"), viewModel.uiState.value.communityReviews.reviews.map { it.id })
        assertTrue(repository.requestedReviewScopes.contains(ActivityScope.FRIENDS))
    }

    private fun TestScope.createViewModel(placeId: String, repository: TestTravelRepository): PlaceDetailViewModel {
        val viewModel = PlaceDetailViewModel(SavedStateHandle(mapOf("placeId" to placeId)), repository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect { } }
        return viewModel
    }

    private fun seedPlaceId(): String =
        com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource.mockPlaces.first().id

    private fun review(id: String, placeId: String, authorId: String = "u1") = PublicReview(
        id = id,
        placeId = placeId,
        author = PublicReviewAuthor(authorId, "demo", "Demo User", null),
        overallScore = 9.0,
        publicReview = "Lovely spot.",
        visitDate = LocalDate.of(2026, 8, 1),
    )

    private fun friendUser(id: String) = FriendPlaceUser(
        userId = id,
        displayName = "Friend $id",
        avatarUrl = null,
        latestScore = 9.4,
        latestVisitedAt = LocalDate.of(2026, 5, 3),
    )
}

class FriendsScoreCopyTest {
    @Test
    fun `visited label mapping uses zero string or friends-visited plurals`() {
        assertEquals(R.string.no_friend_visits_yet, FriendsScoreCopy.visitedLabelRes(0))
        assertEquals(R.plurals.friends_visited_count, FriendsScoreCopy.visitedPluralRes(1))
        assertEquals(R.plurals.friends_visited_count, FriendsScoreCopy.visitedPluralRes(3))
    }

    @Test
    fun `community and friends labels stay independent`() {
        assertEquals(R.string.no_community_ratings_yet, CommunityScoreCopy.visitCountLabelRes(0))
        assertEquals(R.string.no_friend_visits_yet, FriendsScoreCopy.visitedLabelRes(0))
        assertEquals(R.plurals.visits_count, CommunityScoreCopy.visitPluralRes(1))
        assertEquals(R.plurals.friends_visited_count, FriendsScoreCopy.visitedPluralRes(1))
    }
}

class ActivityScopeMappingTest {
    @Test
    fun `maps community and friends to query params`() {
        assertEquals("community", ActivityScope.COMMUNITY.queryParam)
        assertEquals("friends", ActivityScope.FRIENDS.queryParam)
        assertEquals(ActivityScope.COMMUNITY, ActivityScope.fromQueryParam(null))
        assertEquals(ActivityScope.COMMUNITY, ActivityScope.fromQueryParam("community"))
        assertEquals(ActivityScope.FRIENDS, ActivityScope.fromQueryParam("friends"))
    }
}
