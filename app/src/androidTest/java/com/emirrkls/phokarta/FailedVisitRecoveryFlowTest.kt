package com.emirrkls.phokarta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.data.VisitDraftRepository
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.database.entity.MutationStateValue
import com.emirrkls.phokarta.core.database.entity.MutationTypeValue
import com.emirrkls.phokarta.core.database.entity.PendingMutationEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitPayloadEntity
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.network.NetworkError
import com.emirrkls.phokarta.core.sync.OfflineMutationRepository
import com.emirrkls.phokarta.feature.rating.VisitDraft
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class FailedVisitRecoveryFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var draftRepository: VisitDraftRepository
    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var fakeVisits: FakeVisits
    @Inject lateinit var offlineMutations: OfflineMutationRepository
    @Inject lateinit var database: TravelDatabase

    @Before
    fun setUp() {
        hiltRule.inject()
        fakeVisits.failCreate = false
        fakeVisits.failCreatePermanent = null
        fakeVisits.resetRecordedClientMutationIds()
        runBlocking { clearLocalQueueState() }
    }

    @Test
    fun editAndRetry_fullFlowCreatesNewMutationAndCanonicalVisit() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        val owner = checkNotNull(sessionManager.currentUserId())
        val m1 = publishPermanentFailure(
            owner = owner,
            draft = recoveryDraft(review = REVIEW_M1, memory = MEMORY_M1),
        )

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Sync failed", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        openPendingDetailFromPlace()
        composeRule.onNodeWithTag("pending_visit_edit_retry").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(REVIEW_M1).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(MEMORY_M1).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Visibility, Friends").performScrollTo().assertIsDisplayed()

        runBlocking {
            assertNull(offlineMutations.observePendingVisits().first().firstOrNull { it.mutationId == m1 })
            assertTrue(draftRepository.hasDraft(PLACE_ID))
        }

        composeRule.onNodeWithText(REVIEW_M1).performScrollTo().performTextReplacement(REVIEW_M2)
        fakeVisits.failCreatePermanent = null
        composeRule.onNodeWithText("Publish visit").performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Your visits").fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking {
            assertEquals(2, fakeVisits.recordedClientMutationIds.size)
            assertEquals(m1, fakeVisits.recordedClientMutationIds.first())
            assertNotEquals(m1, fakeVisits.recordedClientMutationIds.last())
            assertTrue(offlineMutations.observePendingVisits().first().isEmpty())
            assertFalse(draftRepository.hasDraft(PLACE_ID))
            val visits = database.visitDao().observeVisitsWithDimensions(owner).first()
            assertEquals(1, visits.count { it.visit.placeId == PLACE_ID })
            assertEquals(REVIEW_M2, visits.single { it.visit.placeId == PLACE_ID }.visit.publicReview)
        }

        composeRule.onAllNodesWithText("Sync failed", substring = true).fetchSemanticsNodes().isEmpty()
    }

    @Test
    fun editAndRetry_samePayloadStillUsesNewMutationId() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        val owner = checkNotNull(sessionManager.currentUserId())
        val m1 = publishPermanentFailure(
            owner = owner,
            draft = recoveryDraft(review = "Same payload review"),
        )

        openPendingDetailFromPlace()
        composeRule.onNodeWithTag("pending_visit_edit_retry").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
        }

        fakeVisits.failCreatePermanent = null
        composeRule.onNodeWithText("Publish visit").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Your visits").fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking {
            assertEquals(2, fakeVisits.recordedClientMutationIds.size)
            assertNotEquals(m1, fakeVisits.recordedClientMutationIds.last())
            assertTrue(offlineMutations.observePendingVisits().first().isEmpty())
            assertFalse(draftRepository.hasDraft(PLACE_ID))
            assertEquals(1, database.visitDao().observeVisitsWithDimensions(owner).first().count { it.visit.placeId == PLACE_ID })
        }
    }

    @Test
    fun existingDraftConflict_cancelThenReplace() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        val owner = checkNotNull(sessionManager.currentUserId())
        runBlocking {
            draftRepository.saveDraft(
                PLACE_ID,
                VisitDraft(publicReview = "Existing meaningful draft"),
                owner,
            )
            seedFailedPermanentMutationDirect(
                mutationId = MUTATION_M1,
                review = "Failed payload review",
            )
        }

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Sync failed", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        openPendingDetailFromPlace()
        composeRule.onNodeWithTag("pending_visit_edit_retry").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithTag("replace_draft_dialog").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Cancel").performClick()

        runBlocking {
            assertEquals("Existing meaningful draft", draftRepository.getDraft(PLACE_ID)!!.publicReview)
            assertEquals(MUTATION_M1, offlineMutations.observePendingVisits().first().single().mutationId)
        }

        composeRule.onNodeWithTag("pending_visit_edit_retry").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithTag("replace_draft_dialog").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Replace").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Failed payload review").fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking {
            assertNull(offlineMutations.observePendingVisits().first().firstOrNull { it.mutationId == MUTATION_M1 })
            assertEquals("Failed payload review", draftRepository.getDraft(PLACE_ID)!!.publicReview)
        }
    }

    @Test
    fun removeFailedVisit_deletesLocalPendingOnly() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        val owner = checkNotNull(sessionManager.currentUserId())
        publishPermanentFailure(owner = owner, draft = recoveryDraft(review = "Remove me"))

        openPendingDetailFromPlace()
        composeRule.onNodeWithTag("pending_visit_remove").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithTag("remove_failed_visit_dialog").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithTag("pending_visit_remove").assertIsDisplayed()

        composeRule.onNodeWithTag("pending_visit_remove").performClick()
        composeRule.onNodeWithTag("remove_failed_visit_dialog").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Confirm remove failed visit").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Sync failed", substring = true).fetchSemanticsNodes().isEmpty()
        }

        runBlocking {
            assertTrue(offlineMutations.observePendingVisits().first().isEmpty())
            assertFalse(draftRepository.hasDraft(PLACE_ID))
            assertTrue(database.visitDao().observeVisitsWithDimensions(owner).first().none { it.visit.placeId == PLACE_ID })
        }
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithTag("pending_visit_detail_sheet").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun validationPermanentFailure_showsEditAndRetryNotRetry() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()
        publishPermanentFailure(
            owner = checkNotNull(sessionManager.currentUserId()),
            draft = recoveryDraft(review = "Validation failure"),
        )

        openPendingDetailFromPlace()
        composeRule.onNodeWithTag("pending_visit_edit_retry").assertIsDisplayed()
        composeRule.onNodeWithTag("pending_visit_remove").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("pending_visit_retry").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun forbiddenPermanentFailure_showsRemoveOnly() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        fakeVisits.failCreatePermanent = NetworkError.Forbidden(null)
        publishDraftFromPlace(recoveryDraft(review = "Forbidden failure"))

        openPendingDetailFromPlace()
        composeRule.onNodeWithTag("pending_visit_remove").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("pending_visit_edit_retry").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("pending_visit_retry").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun retryableFailure_showsRetryOnly() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        fakeVisits.failCreate = true
        publishDraftFromPlace(recoveryDraft(review = "Retryable failure"))
        fakeVisits.failCreate = false

        openPendingDetailFromPlace()
        composeRule.onNodeWithTag("pending_visit_retry").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("pending_visit_edit_retry").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("pending_visit_remove").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun activityRecreate_keepsRecoveredDraftFields() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        publishPermanentFailure(
            owner = checkNotNull(sessionManager.currentUserId()),
            draft = recoveryDraft(review = "Recreate review", memory = "Recreate memory"),
        )

        openPendingDetailFromPlace()
        composeRule.onNodeWithTag("pending_visit_edit_retry").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Recreate review").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.runOnUiThread { composeRule.activity.recreate() }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Recreate review").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithText("Recreate review").fetchSemanticsNodes().isEmpty()) {
            composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("Continue draft").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithText("Continue draft").onFirst().performClick()
        }
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Recreate review").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Recreate review").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Recreate memory").performScrollTo().assertIsDisplayed()
    }

    private fun openPendingDetailFromPlace() {
        composeRule.onAllNodesWithTag("owner_visit_row").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithTag("pending_visit_detail_sheet").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun recoveryDraft(
        review: String,
        memory: String = MEMORY_M1,
    ) = VisitDraft(
        overallScore = 8.5f,
        dimensions = mapOf(RatingDimension.SEA to 9f),
        publicReview = review,
        privateMemory = memory,
        visitDate = LocalDate.of(2026, 5, 12),
        visibility = Visibility.FRIENDS,
        dimensionsExpanded = true,
    )

    private fun publishPermanentFailure(owner: String, draft: VisitDraft): String {
        fakeVisits.failCreatePermanent = NetworkError.Validation(null)
        publishDraftFromPlace(draft)
        return runBlocking {
            val pending = offlineMutations.observePendingVisits().first {
                it.any { visit -> visit.visit.placeId == PLACE_ID && visit.state == MutationStateValue.FAILED_PERMANENT }
            }.single { it.visit.placeId == PLACE_ID }
            assertEquals(MutationStateValue.FAILED_PERMANENT, pending.state)
            pending.mutationId
        }
    }

    private fun publishDraftFromPlace(draft: VisitDraft) {
        val owner = checkNotNull(sessionManager.currentUserId())
        runBlocking {
            draftRepository.saveDraft(PLACE_ID, draft, owner)
        }
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Continue draft").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Continue draft").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Publish visit").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Your visits").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Sync failed", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private suspend fun seedFailedPermanentMutationDirect(
        mutationId: String,
        review: String,
        memory: String = MEMORY_M1,
    ) {
        val owner = checkNotNull(sessionManager.currentUserId())
        val dao = database.pendingMutationDao()
        dao.insertMutation(
            PendingMutationEntity(
                mutationId, owner, MutationTypeValue.PUBLISH_VISIT, mutationId,
                MutationStateValue.FAILED_PERMANENT, 1, null, 1, 1_000, 1_000, "VALIDATION",
            ),
        )
        dao.insertVisitPayload(
            PendingVisitPayloadEntity(
                mutationId, PLACE_ID, LocalDate.of(2026, 5, 12).toEpochDay(),
                8.5, review, memory, Visibility.FRIENDS.name,
            ),
        )
        dao.insertVisitDimensions(
            listOf(PendingVisitDimensionScoreEntity(mutationId, RatingDimension.SEA.name, 9.0)),
        )
    }

    private suspend fun clearLocalQueueState() {
        val owner = sessionManager.currentUserId() ?: return
        draftRepository.deleteDraft(PLACE_ID, owner)
        val db = database.openHelper.writableDatabase
        db.execSQL("DELETE FROM pending_visit_photos")
        db.execSQL("DELETE FROM pending_visit_dimension_scores")
        db.execSQL("DELETE FROM pending_visit_payloads")
        db.execSQL("DELETE FROM pending_mutations")
        db.execSQL("DELETE FROM visit_dimension_scores")
        db.execSQL("DELETE FROM visits")
    }

    companion object {
        private const val PLACE_ID = "20000000-0000-0000-0000-000000000003"
        private const val MUTATION_M1 = "11111111-1111-1111-1111-111111111111"
        private const val REVIEW_M1 = "Recovery review M1"
        private const val REVIEW_M2 = "Recovery review M2 edited"
        private const val MEMORY_M1 = "Recovery memory M1"
    }
}
