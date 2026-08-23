package com.emirrkls.phokarta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class CommunityReviewsFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun publishPublicReview_showsInCommunitySection() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.onAllNodesWithText("Been here").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
        }

        val publicReviewText = "Great atmosphere and very good service."
        val privateMemoryText = "SECRET_PRIVATE_MEMORY_DO_NOT_SHOW"
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Public review input").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Public review input")
            .performScrollTo()
            .performTextInput(publicReviewText)
        composeRule.onNodeWithContentDescription("Private memory input")
            .performScrollTo()
            .performTextInput(privateMemoryText)
        composeRule.onNodeWithText("Publish visit").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Community reviews").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(publicReviewText, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Community reviews").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(publicReviewText, substring = true).performScrollTo().assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("Review by Emir Kaya", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertTrue(
            "Expected community review author semantics for Emir Kaya",
            composeRule.onAllNodesWithContentDescription("Review by Emir Kaya", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        assertTrue(
            "Private memory leaked into Place Detail community UI",
            composeRule.onAllNodesWithText(privateMemoryText).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun communityAndPersonalScores_areBothVisible() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Community").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Community").assertIsDisplayed()
    }
}
