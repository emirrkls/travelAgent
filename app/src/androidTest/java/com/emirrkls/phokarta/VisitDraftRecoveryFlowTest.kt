package com.emirrkls.phokarta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.data.VisitDraftRepository
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.feature.rating.VisitDraft
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class VisitDraftRecoveryFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var draftRepository: VisitDraftRepository
    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var fakeVisits: FakeVisits

    @Before
    fun inject() {
        hiltRule.inject()
        fakeVisits.failCreate = false
        sessionManager.currentUserId()?.let { owner ->
            runBlocking { draftRepository.deleteDraft(PLACE_ID, owner) }
        }
    }

    @Test
    fun continueDraft_restoresSeededFieldsAndShowsRestoredMessage() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        val owner = checkNotNull(sessionManager.currentUserId())
        runBlocking {
            draftRepository.saveDraft(
                PLACE_ID,
                VisitDraft(
                    overallScore = 8f,
                    dimensions = mapOf(RatingDimension.SEA to 9f),
                    publicReview = "Draft review text",
                    privateMemory = "Draft memory text",
                    visitDate = LocalDate.of(2026, 5, 20),
                    visibility = Visibility.FRIENDS,
                    dimensionsExpanded = true,
                ),
                owner,
            )
        }

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Continue draft").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Continue draft").onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("Continue draft").onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText("Draft review text").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Draft review text").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Draft memory text").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Visibility, Friends")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun backAndReopen_restoresAutosavedDraft() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        val owner = checkNotNull(sessionManager.currentUserId())
        runBlocking {
            draftRepository.deleteDraft(PLACE_ID, owner)
            draftRepository.saveDraft(
                PLACE_ID,
                VisitDraft(
                    publicReview = "Autosaved review",
                    visibility = Visibility.FRIENDS,
                ),
                owner,
            )
        }

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Continue draft").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Continue draft").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Autosaved review").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Continue draft").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Continue draft").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Autosaved review").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Autosaved review").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Visibility, Friends")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun activityRecreate_keepsDraftFields() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        val owner = checkNotNull(sessionManager.currentUserId())
        runBlocking {
            draftRepository.saveDraft(
                PLACE_ID,
                VisitDraft(
                    overallScore = 7.5f,
                    publicReview = "Survive recreate",
                    privateMemory = "Memory recreate",
                    visibility = Visibility.PRIVATE,
                ),
                owner,
            )
        }

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Continue draft").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Continue draft").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Survive recreate").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.runOnUiThread { composeRule.activity.recreate() }
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Survive recreate").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Survive recreate").assertIsDisplayed()
        composeRule.onNodeWithText("Memory recreate").assertIsDisplayed()
    }

    @Test
    fun publishSuccess_removesDraftAndShowsFreshDefaults() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        val owner = checkNotNull(sessionManager.currentUserId())
        runBlocking {
            draftRepository.saveDraft(
                PLACE_ID,
                VisitDraft(publicReview = "Will publish", visibility = Visibility.PRIVATE),
                owner,
            )
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
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Rate another visit").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Your visits").fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking {
            assertFalse(draftRepository.hasDraft(PLACE_ID))
        }

        composeRule.onAllNodesWithText("Rate another visit").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Will publish").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun publishFailure_keepsDraftValues() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()
        fakeVisits.failCreate = true

        val owner = checkNotNull(sessionManager.currentUserId())
        runBlocking {
            draftRepository.saveDraft(
                PLACE_ID,
                VisitDraft(
                    overallScore = 8f,
                    publicReview = "Keep after failure",
                    visibility = Visibility.PRIVATE,
                ),
                owner,
            )
        }

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Continue draft").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Continue draft").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Keep after failure").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Publish visit").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Keep after failure").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Keep after failure").assertIsDisplayed()
        runBlocking {
            assertTrue(draftRepository.hasDraft(PLACE_ID))
        }
    }

    companion object {
        private const val PLACE_ID = "20000000-0000-0000-0000-000000000003"
    }
}
