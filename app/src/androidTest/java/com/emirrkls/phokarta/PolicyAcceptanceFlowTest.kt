package com.emirrkls.phokarta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class PolicyAcceptanceFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var fakeSocial: FakeSocial

    @Before
    fun inject() {
        hiltRule.inject()
        fakeSocial.reset()
        fakeSocial.policyAccepted = false
    }

    @After
    fun restore() {
        fakeSocial.reset()
    }

    @Test
    fun reportWorksWithoutAcceptanceThenPublishPromptsAndSucceedsAfterAccept() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Community reviews").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Community reviews").performScrollTo()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Report this visit").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Report this visit").onFirst()
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Report visit", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Report visit", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Spam").performClick()
        composeRule.onNodeWithTag("report_submit").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Thanks. We'll review this report.").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Close").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Community reviews").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithContentDescription("Share experience")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Share experience").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Share experience").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Breakfast").performClick()
        composeRule.onNodeWithText("🙂 Liked it").performClick()
        composeRule.onNodeWithText("Tell your story").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Review input")
            .performScrollTo()
            .performTextInput("A calm breakfast by the water.")
        composeRule.onNodeWithText("Share experience").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("policy_acceptance_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("policy_acceptance_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("policy_acceptance_checkbox").performClick()
        composeRule.onNodeWithTag("policy_acceptance_accept").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Your experiences").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Share experience").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Your experiences").assertIsDisplayed()
    }
}
