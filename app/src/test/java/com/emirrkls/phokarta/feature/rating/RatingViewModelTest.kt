package com.emirrkls.phokarta.feature.rating

import androidx.lifecycle.SavedStateHandle
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.FakeVisitDraftRepository
import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.auth.testSessionManager
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.data.VisitDraftRepository
import com.emirrkls.phokarta.core.model.PolicyStatus
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.model.Visit
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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

@OptIn(ExperimentalCoroutinesApi::class)
class RatingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val userId = "11111111-1111-1111-1111-111111111111"

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.createViewModel(
        placeId: String,
        repository: TestTravelRepository = TestTravelRepository(),
        drafts: FakeVisitDraftRepository = FakeVisitDraftRepository(activeUserId = userId),
        sessionUserId: String = userId,
    ): RatingViewModel {
        val viewModel = RatingViewModel(
            SavedStateHandle(mapOf("placeId" to placeId)),
            repository,
            drafts,
            testSessionManager(userId = sessionUserId),
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect { } }
        return viewModel
    }

    private fun seedPlaceId(): String =
        com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource.mockPlaces.first().id

    @Test
    fun newVisitDefaultsVisibilityToPublic() = runTest(dispatcher) {
        val viewModel = createViewModel(seedPlaceId())
        advanceUntilIdle()

        assertEquals(Visibility.PUBLIC, viewModel.uiState.value.visibility)
        assertFalse(viewModel.uiState.value.isDraftInitializing)
    }

    @Test
    fun openWithoutMeaningfulEditsDoesNotPersistDraft() = runTest(dispatcher) {
        val drafts = FakeVisitDraftRepository(activeUserId = userId)
        val placeId = seedPlaceId()
        val viewModel = createViewModel(placeId, drafts = drafts)
        advanceUntilIdle()
        advanceTimeBy(VisitDraftRepository.AUTOSAVE_DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertTrue(drafts.records().isEmpty())
        assertNull(drafts.getDraft(placeId))
    }

    @Test
    fun autosavePersistsScoreReviewAndVisibility() = runTest(dispatcher) {
        val drafts = FakeVisitDraftRepository(activeUserId = userId)
        val placeId = seedPlaceId()
        val viewModel = createViewModel(placeId, drafts = drafts)
        advanceUntilIdle()

        viewModel.setOverall(8.5f)
        viewModel.setReview("Quiet cove")
        viewModel.setVisibility(Visibility.FRIENDS)
        advanceTimeBy(VisitDraftRepository.AUTOSAVE_DEBOUNCE_MS + 50)
        advanceUntilIdle()

        val stored = drafts.getDraft(placeId)!!
        assertEquals(8.5f, stored.overallScore, 0.01f)
        assertEquals("Quiet cove", stored.publicReview)
        assertEquals(Visibility.FRIENDS, stored.visibility)
    }

    @Test
    fun restoresPersistedDraftOnInit() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val drafts = FakeVisitDraftRepository(activeUserId = userId).apply {
            seed(
                userId,
                placeId,
                VisitDraft(
                    overallScore = 8f,
                    dimensions = mapOf(RatingDimension.SEA to 9f),
                    publicReview = "Seeded review",
                    privateMemory = "Seeded memory",
                    visitDate = LocalDate.of(2026, 7, 4),
                    visibility = Visibility.FRIENDS,
                ),
            )
        }
        val viewModel = createViewModel(placeId, drafts = drafts)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(8f, state.overall, 0.01f)
        assertEquals(9f, state.dimensions[RatingDimension.SEA]!!, 0.01f)
        assertEquals("Seeded review", state.review)
        assertEquals("Seeded memory", state.note)
        assertEquals(LocalDate.of(2026, 7, 4), state.visitedAt)
        assertEquals(Visibility.FRIENDS, state.visibility)
        assertTrue(state.showDraftRestoredMessage)
    }

    @Test
    fun processLikeRecreateRestoresFromRepository() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val drafts = FakeVisitDraftRepository(activeUserId = userId)
        val first = createViewModel(placeId, drafts = drafts)
        advanceUntilIdle()
        first.setOverall(7.2f)
        first.setNote("Keep me")
        advanceTimeBy(VisitDraftRepository.AUTOSAVE_DEBOUNCE_MS + 50)
        advanceUntilIdle()
        first.flushDraft()
        advanceUntilIdle()

        val second = createViewModel(placeId, drafts = drafts)
        advanceUntilIdle()
        assertEquals(7.2f, second.uiState.value.overall, 0.01f)
        assertEquals("Keep me", second.uiState.value.note)
    }

    @Test
    fun otherUserDoesNotSeeDraft() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val drafts = FakeVisitDraftRepository(activeUserId = userId).apply {
            seed(userId, placeId, VisitDraft(overallScore = 9f, publicReview = "A only"))
        }
        drafts.activeUserId = "22222222-2222-2222-2222-222222222222"
        val viewModel = createViewModel(
            placeId,
            drafts = drafts,
            sessionUserId = "22222222-2222-2222-2222-222222222222",
        )
        advanceUntilIdle()

        assertEquals(Visibility.PUBLIC, viewModel.uiState.value.visibility)
        assertEquals("", viewModel.uiState.value.review)
        assertEquals(8f, viewModel.uiState.value.overall, 0.01f)
    }

    @Test
    fun publishAppendsVisitAndMarksPublished() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = TestTravelRepository()
        val drafts = FakeVisitDraftRepository(activeUserId = userId)
        val viewModel = createViewModel(placeId, repository, drafts)
        advanceUntilIdle()

        viewModel.publish()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.published)
        assertEquals(1, repository.visits.value.size)
        assertEquals(placeId, repository.visits.value.single().placeId)
        assertEquals(Visibility.PUBLIC, repository.visits.value.single().visibility)
    }

    @Test
    fun publishSuccessDeletesDraftOnlyAfterSuccess() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val drafts = FakeVisitDraftRepository(activeUserId = userId)
        val viewModel = createViewModel(placeId, drafts = drafts)
        advanceUntilIdle()
        viewModel.setReview("Will publish")
        advanceTimeBy(VisitDraftRepository.AUTOSAVE_DEBOUNCE_MS + 50)
        advanceUntilIdle()
        assertTrue(drafts.hasDraft(placeId))

        viewModel.publish()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.published)
        assertFalse(drafts.hasDraft(placeId))
        assertTrue(drafts.deleteCalls.contains(placeId))
    }

    @Test
    fun publishFailurePreservesDraftIncludingVisibility() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val drafts = FakeVisitDraftRepository(activeUserId = userId)
        val viewModel = createViewModel(placeId, FailingRepository(), drafts)
        advanceUntilIdle()

        viewModel.setReview("Keep this note")
        viewModel.setVisibility(Visibility.PRIVATE)
        advanceTimeBy(VisitDraftRepository.AUTOSAVE_DEBOUNCE_MS + 50)
        advanceUntilIdle()
        viewModel.publish()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.published)
        assertEquals("Keep this note", viewModel.uiState.value.review)
        assertEquals(Visibility.PRIVATE, viewModel.uiState.value.visibility)
        assertTrue(viewModel.uiState.value.publishError != null)
        assertTrue(drafts.hasDraft(placeId))
    }

    @Test
    fun pendingAutosaveCannotRecreateDraftAfterSuccessfulPublish() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val drafts = FakeVisitDraftRepository(activeUserId = userId)
        val viewModel = createViewModel(placeId, drafts = drafts)
        advanceUntilIdle()

        viewModel.setReview("Race")
        // Publish before debounce fires.
        viewModel.publish()
        advanceUntilIdle()
        advanceTimeBy(VisitDraftRepository.AUTOSAVE_DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.published)
        assertFalse(drafts.hasDraft(placeId))
    }

    @Test
    fun discardClearsRepositoryAndResetsState() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val drafts = FakeVisitDraftRepository(activeUserId = userId)
        val viewModel = createViewModel(placeId, drafts = drafts)
        advanceUntilIdle()
        viewModel.setReview("Trash me")
        viewModel.setVisibility(Visibility.FRIENDS)
        advanceTimeBy(VisitDraftRepository.AUTOSAVE_DEBOUNCE_MS + 50)
        advanceUntilIdle()

        viewModel.discardDraft()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.discarded)
        assertEquals("", viewModel.uiState.value.review)
        assertEquals(Visibility.PUBLIC, viewModel.uiState.value.visibility)
        assertFalse(drafts.hasDraft(placeId))
    }

    @Test
    fun pendingAutosaveCannotRecreateDraftAfterDiscard() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val drafts = FakeVisitDraftRepository(activeUserId = userId)
        val viewModel = createViewModel(placeId, drafts = drafts)
        advanceUntilIdle()
        viewModel.setReview("Going away")
        viewModel.discardDraft()
        advanceUntilIdle()
        advanceTimeBy(VisitDraftRepository.AUTOSAVE_DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertFalse(drafts.hasDraft(placeId))
    }

    @Test
    fun clearingMeaningfulFieldsDeletesPersistedDraft() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val drafts = FakeVisitDraftRepository(activeUserId = userId)
        val viewModel = createViewModel(placeId, drafts = drafts)
        advanceUntilIdle()
        viewModel.setVisibility(Visibility.PRIVATE)
        advanceTimeBy(VisitDraftRepository.AUTOSAVE_DEBOUNCE_MS + 50)
        advanceUntilIdle()
        assertTrue(drafts.hasDraft(placeId))

        viewModel.setVisibility(Visibility.PUBLIC)
        advanceTimeBy(VisitDraftRepository.AUTOSAVE_DEBOUNCE_MS + 50)
        advanceUntilIdle()
        assertFalse(drafts.hasDraft(placeId))
    }

    @Test
    fun expiryCleanupRemovesStaleDrafts() = runTest(dispatcher) {
        val now = VisitDraftRepository.EXPIRY_MS + 100_000L
        val drafts = FakeVisitDraftRepository(activeUserId = userId, nowMillis = now)
        val placeId = seedPlaceId()
        drafts.seed(userId, placeId, VisitDraft(publicReview = "old"), updatedAt = 1L)
        drafts.seed(
            userId,
            "other-place",
            VisitDraft(publicReview = "fresh"),
            updatedAt = now - 1_000L,
        )

        drafts.deleteExpiredDrafts()

        assertNull(drafts.records()[userId to placeId])
        assertEquals("fresh", drafts.records()[userId to "other-place"]!!.draft.publicReview)
    }

    @Test
    fun publishPrivateSucceedsAndNextDraftDefaultsPublic() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = TestTravelRepository()
        val drafts = FakeVisitDraftRepository(activeUserId = userId)
        val viewModel = createViewModel(placeId, repository, drafts)
        advanceUntilIdle()

        viewModel.setVisibility(Visibility.PRIVATE)
        viewModel.publish()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.published)
        assertEquals(Visibility.PRIVATE, repository.visits.value.single().visibility)

        val next = createViewModel(placeId, repository, drafts)
        advanceUntilIdle()
        assertEquals(Visibility.PUBLIC, next.uiState.value.visibility)
        assertEquals("", next.uiState.value.review)
    }

    @Test
    fun futureVisitDateBlocksPublish() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = TestTravelRepository()
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        viewModel.setVisitedAt(LocalDate.now().plusDays(2))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canPublish)
        viewModel.publish()
        advanceUntilIdle()
        assertTrue(repository.visits.value.isEmpty())
    }

    @Test
    fun publishWithoutAcceptanceShowsPolicySheetAndDoesNotPublish() = runTest(dispatcher) {
        val repository = TestTravelRepository().apply {
            policyStatus = PolicyStatus("2026-08-beta", null, false)
        }
        val viewModel = createViewModel(seedPlaceId(), repository)
        advanceUntilIdle()

        viewModel.publish()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.policy.visible)
        assertFalse(viewModel.uiState.value.policy.checked)
        assertFalse(viewModel.uiState.value.published)
        assertTrue(repository.visits.value.isEmpty())
        assertTrue(repository.acceptPolicyCalls.isEmpty())
    }

    @Test
    fun acceptPolicyThenPublishSucceeds() = runTest(dispatcher) {
        val repository = TestTravelRepository().apply {
            policyStatus = PolicyStatus("2026-08-beta", null, false)
        }
        val viewModel = createViewModel(seedPlaceId(), repository)
        advanceUntilIdle()

        viewModel.publish()
        advanceUntilIdle()
        viewModel.setPolicyChecked(true)
        viewModel.acceptPolicy()
        advanceUntilIdle()

        assertEquals(listOf("2026-08-beta"), repository.acceptPolicyCalls)
        assertFalse(viewModel.uiState.value.policy.visible)
        assertTrue(viewModel.uiState.value.published)
        assertEquals(1, repository.visits.value.size)
    }

    @Test
    fun staleServerRejectionOpensPolicySheetThenRetryPublishes() = runTest(dispatcher) {
        val repository = StaleThenOkRepository()
        val viewModel = createViewModel(seedPlaceId(), repository)
        advanceUntilIdle()

        viewModel.publish()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.policy.visible)
        assertFalse(viewModel.uiState.value.published)
        assertTrue(repository.visits.value.isEmpty())

        viewModel.setPolicyChecked(true)
        viewModel.acceptPolicy()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.published)
        assertEquals(1, repository.visits.value.size)
        assertEquals(1, repository.acceptPolicyCalls.size)
    }

    @Test
    fun offlineWithoutConfirmedAcceptanceDoesNotPretendAccepted() = runTest(dispatcher) {
        val repository = TestTravelRepository().apply {
            policyStatusError = TravelError.Offline()
        }
        val viewModel = createViewModel(seedPlaceId(), repository)
        advanceUntilIdle()

        viewModel.publish()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.policy.visible)
        assertFalse(viewModel.uiState.value.published)
        assertEquals(R.string.error_offline, viewModel.uiState.value.publishError)
        assertTrue(repository.visits.value.isEmpty())
        assertTrue(repository.acceptPolicyCalls.isEmpty())
    }

    private class FailingRepository : TestTravelRepository() {
        override suspend fun publishVisit(visit: Visit): RepositoryResult<Visit> =
            RepositoryResult.Failure(TravelError.Offline())
    }

    private class StaleThenOkRepository : TestTravelRepository() {
        private var failOnce = true
        override suspend fun publishVisit(visit: Visit): RepositoryResult<Visit> {
            if (failOnce) {
                failOnce = false
                return RepositoryResult.Failure(
                    TravelError.PolicyAcceptanceRequired("2026-08-beta"),
                )
            }
            return super.publishVisit(visit)
        }
    }
}
