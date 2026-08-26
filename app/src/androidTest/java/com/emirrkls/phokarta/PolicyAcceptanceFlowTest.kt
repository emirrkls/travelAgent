package com.emirrkls.phokarta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

        composeRule.onNodeWithText("Activity").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Report this visit").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Report this visit").onFirst().performClick()
        composeRule.onNodeWithText("Report visit").performClick()
        composeRule.onNodeWithText("Spam").performClick()
        composeRule.onNodeWithTag("report_submit").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Thanks. We'll review this report.").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Close").performClick()

        composeRule.onNodeWithText("Explore").performClick()
        composeRule.waitForExplore()
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.onAllNodesWithText("Been here").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Publish visit").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("policy_acceptance_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("policy_acceptance_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("policy_acceptance_checkbox").performClick()
        composeRule.onNodeWithTag("policy_acceptance_accept").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Your visits").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Rate another visit").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Your visits").assertIsDisplayed()
    }
}
