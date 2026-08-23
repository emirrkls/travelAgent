package com.emirrkls.phokarta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class VisitVisibilityFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun publicPublish_appearsInOwnerHistory() {
        openRatingForSarnicCove()
        val marker = "PUBLIC_VIS_FLOW_${System.currentTimeMillis() % 100000}"
        enterReview(marker)
        dismissKeyboard()
        composeRule.onNodeWithText("Publish visit").performClick()
        waitForYourVisits()
        composeRule.onNodeWithText(marker, substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun privatePublish_appearsInOwnerHistoryWithoutCommunityLeak() {
        openRatingForSarnicCove()

        val reviewText = "PRIVATE_VISIBILITY_FLOW_REVIEW"
        val memoryText = "PRIVATE_VISIBILITY_FLOW_MEMORY"
        enterReview(reviewText)
        composeRule.onNodeWithContentDescription("Private memory input")
            .performScrollTo()
            .performTextInput(memoryText)
        dismissKeyboard()
        selectVisibility("Private")
        composeRule.onNodeWithText("Publish visit").performClick()
        waitForYourVisits()
        composeRule.onNodeWithText(reviewText, substring = true).performScrollTo().assertIsDisplayed()
        assertTrue(
            "Private memory must not leak into place detail community UI",
            composeRule.onAllNodesWithText(memoryText).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "Private visit must not appear as a community review card",
            composeRule.onAllNodesWithContentDescription("Review by", substring = true)
                .fetchSemanticsNodes()
                .none { it.config.toString().contains(reviewText) },
        )
    }

    @Test
    fun friendsPublish_appearsInOwnerHistory() {
        openRatingForSarnicCove()
        val marker = "FRIENDS_VIS_FLOW_${System.currentTimeMillis() % 100000}"
        enterReview(marker)
        dismissKeyboard()
        selectVisibility("Friends")
        composeRule.onNodeWithText("Publish visit").performClick()
        waitForYourVisits()
        composeRule.onNodeWithText(marker, substring = true).performScrollTo().assertIsDisplayed()
        assertTrue(
            "Friends visit must not appear as a community review card",
            composeRule.onAllNodesWithContentDescription("Review by", substring = true)
                .fetchSemanticsNodes()
                .none { it.config.toString().contains(marker) },
        )
    }

    @Test
    fun switchingVisibility_updatesReviewHelperAndPreservesEnteredText() {
        openRatingForSarnicCove()

        val reviewText = "KEEP_THIS_REVIEW_TEXT"
        enterReview(reviewText)
        dismissKeyboard()

        composeRule.onNodeWithText("Shared with the community").assertIsDisplayed()
        selectVisibility("Private")
        composeRule.onNodeWithText("Only you can see this review").assertIsDisplayed()
        composeRule.onNodeWithText(reviewText).assertIsDisplayed()

        selectVisibility("Public")
        composeRule.onNodeWithText("Shared with the community").assertIsDisplayed()
        composeRule.onNodeWithText(reviewText).assertIsDisplayed()
    }

    private fun openRatingForSarnicCove() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Been here").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Been here").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun enterReview(text: String) {
        composeRule.onNodeWithContentDescription("Review input")
            .performScrollTo()
            .performTextInput(text)
    }

    private fun dismissKeyboard() {
        runCatching {
            composeRule.onNodeWithContentDescription("Review input").performImeAction()
        }
        composeRule.onNodeWithText("Publish visit").assertIsDisplayed()
    }

    private fun selectVisibility(label: String) {
        composeRule.onNodeWithContentDescription("Visibility,", substring = true)
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText("Who can see this visit?").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("$label.", substring = true).performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText("Who can see this visit?").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithContentDescription("Visibility, $label")
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForYourVisits() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Your visits").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
