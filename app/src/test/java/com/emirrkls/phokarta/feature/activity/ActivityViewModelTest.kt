package com.emirrkls.phokarta.feature.activity

import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.data.ActivityFeedInvalidator
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.model.ActivityAuthor
import com.emirrkls.phokarta.core.model.ActivityEvent
import com.emirrkls.phokarta.core.model.ActivityPlaceSummary
import com.emirrkls.phokarta.core.model.ActivityScope
import com.emirrkls.phokarta.core.model.OwnerSocialCounts
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.UserSummary
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
    fun `defaults to community scope`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.activityItems.value = listOf(event("c1"))
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        assertEquals(ActivityScope.COMMUNITY, viewModel.uiState.value.activeScope)
        assertEquals(listOf("c1"), viewModel.uiState.value.items.map { it.visitId })
        assertEquals(listOf(ActivityScope.COMMUNITY), repository.requestedActivityScopes)
    }

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
    fun `resume refreshes community when feed was invalidated`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        val invalidator = ActivityFeedInvalidator()
        repository.activityItems.value = listOf(event("old"))
        repository.friendsActivityItems.value = listOf(event("friend-old", authorId = "friend"))
        val viewModel = createViewModel(repository, invalidator)
        advanceUntilIdle()
        viewModel.selectScope(ActivityScope.FRIENDS)
        advanceUntilIdle()

        repository.activityItems.value = listOf(event("new"))
        repository.friendsActivityItems.value = listOf(event("friend-new", authorId = "friend"))
        invalidator.markDirty()
        viewModel.onScreenResumed()
        advanceUntilIdle()

        assertEquals(listOf("friend-old"), viewModel.uiState.value.friends.items.map { it.visitId })
        assertEquals(listOf("new"), viewModel.uiState.value.community.items.map { it.visitId })
    }

    @Test
    fun `switching to friends loads independent list`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.activityItems.value = listOf(event("community"))
        repository.friendsActivityItems.value = listOf(event("friend", authorId = "friend"))
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.selectScope(ActivityScope.FRIENDS)
        advanceUntilIdle()

        assertEquals(ActivityScope.FRIENDS, viewModel.uiState.value.activeScope)
        assertEquals(listOf("friend"), viewModel.uiState.value.items.map { it.visitId })
        assertEquals(listOf("community"), viewModel.uiState.value.community.items.map { it.visitId })
        assertTrue(repository.requestedActivityScopes.contains(ActivityScope.FRIENDS))
    }

    @Test
    fun `scope switch preserves previously loaded state`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.activityItems.value = (1..25).map { event("c$it") }
        repository.friendsActivityItems.value = listOf(event("f1", authorId = "friend"))
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        viewModel.loadNextPage()
        advanceUntilIdle()

        viewModel.selectScope(ActivityScope.FRIENDS)
        advanceUntilIdle()
        viewModel.selectScope(ActivityScope.COMMUNITY)
        advanceUntilIdle()

        assertEquals(25, viewModel.uiState.value.community.items.size)
        assertEquals(listOf("f1"), viewModel.uiState.value.friends.items.map { it.visitId })
        assertEquals(1, repository.requestedActivityScopes.count { it == ActivityScope.FRIENDS })
    }

    @Test
    fun `friends and community errors stay isolated`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.activityItems.value = listOf(event("community"))
        repository.friendsActivityError = TravelError.Offline()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.selectScope(ActivityScope.FRIENDS)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.friends.errorMessage != null)
        assertEquals(listOf("community"), viewModel.uiState.value.community.items.map { it.visitId })
        assertNull(viewModel.uiState.value.community.errorMessage)
    }

    @Test
    fun `friends empty with zero friends shows no friends reason`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.ownerSocialCounts = OwnerSocialCounts(0, 0, 0)
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.selectScope(ActivityScope.FRIENDS)
        advanceUntilIdle()

        assertEquals(FriendsEmptyReason.NO_FRIENDS, viewModel.uiState.value.friendsEmptyReason)
        assertTrue(viewModel.uiState.value.items.isEmpty())
    }

    @Test
    fun `friends empty with friends shows no activity reason`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.ownerSocialCounts = OwnerSocialCounts(1, 1, 2)
        repository.friends += UserSummary("friend", "Friend", "friend", "")
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.selectScope(ActivityScope.FRIENDS)
        advanceUntilIdle()

        assertEquals(FriendsEmptyReason.NO_ACTIVITY, viewModel.uiState.value.friendsEmptyReason)
    }

    @Test
    fun `friends pagination stays on friends scope`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.friendsActivityItems.value = (1..25).map { event("f$it", authorId = "friend") }
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        viewModel.selectScope(ActivityScope.FRIENDS)
        advanceUntilIdle()

        viewModel.loadNextPage()
        advanceUntilIdle()

        assertEquals(25, viewModel.uiState.value.friends.items.size)
        assertTrue(repository.requestedActivityScopes.takeLast(2).all { it == ActivityScope.FRIENDS })
    }

    @Test
    fun `friends feed does not include self authored events in expectations`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        val selfId = repository.currentUser.id
        repository.friendsActivityItems.value = listOf(
            event("friend", authorId = "friend-user"),
        )
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        viewModel.selectScope(ActivityScope.FRIENDS)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.items.none { it.author.userId == selfId })
        assertEquals(listOf("friend"), viewModel.uiState.value.items.map { it.visitId })
    }

    @Test
    fun `friends retry clears friends error`() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.friendsActivityError = TravelError.Offline()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        viewModel.selectScope(ActivityScope.FRIENDS)
        advanceUntilIdle()

        repository.friendsActivityError = null
        repository.friendsActivityItems.value = listOf(event("f1", authorId = "friend"))
        viewModel.retry()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.friends.errorMessage)
        assertEquals(1, viewModel.uiState.value.friends.items.size)
    }

    private fun TestScope.createViewModel(
        repository: TestTravelRepository,
        invalidator: ActivityFeedInvalidator = ActivityFeedInvalidator(),
    ): ActivityViewModel {
        val viewModel = ActivityViewModel(repository, invalidator)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect { } }
        return viewModel
    }

    private fun event(id: String, authorId: String = "u1") = ActivityEvent(
        visitId = id,
        author = ActivityAuthor(authorId, "demo", "Demo User", null),
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
