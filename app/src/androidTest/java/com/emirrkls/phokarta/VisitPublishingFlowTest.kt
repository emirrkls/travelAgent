package com.emirrkls.phokarta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class VisitPublishingFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun firstVisit_showsVisitedOnPlaceDetail() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.onAllNodesWithText("Been here").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Publish visit").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Your visits").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Rate another visit").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Your visits").assertIsDisplayed()
    }

    @Test
    fun savedAndVisited_areIndependent() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Want to go").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Saved").fetchSemanticsNodes().isNotEmpty()
        }
        when {
            composeRule.onAllNodesWithContentDescription("Want to go").fetchSemanticsNodes().isNotEmpty() ->
                composeRule.onAllNodesWithContentDescription("Want to go").onFirst().performClick()
            else -> composeRule.onAllNodesWithContentDescription("Saved").onFirst().performClick()
        }

        composeRule.onAllNodesWithText("Been here").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Publish visit").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Your visits").fetchSemanticsNodes().isNotEmpty()
        }

        when {
            composeRule.onAllNodesWithContentDescription("Saved").fetchSemanticsNodes().isNotEmpty() ->
                composeRule.onAllNodesWithContentDescription("Saved").onFirst().performClick()
            else -> composeRule.onAllNodesWithContentDescription("Want to go").fetchSemanticsNodes().let {
                if (it.isNotEmpty()) composeRule.onAllNodesWithContentDescription("Want to go").onFirst().performClick()
            }
        }

        composeRule.onNodeWithText("Your visits").assertIsDisplayed()
        composeRule.onNodeWithText("Rate another visit").assertIsDisplayed()
    }
}
