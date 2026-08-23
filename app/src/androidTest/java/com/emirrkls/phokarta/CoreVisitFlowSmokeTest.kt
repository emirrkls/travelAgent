package com.emirrkls.phokarta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class CoreVisitFlowSmokeTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun exploreToPublishShowsVisitOnProfile() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.onNodeWithText("Been here · Rate this place").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Publish visit").assertIsDisplayed()
        composeRule.onNodeWithText("Publish visit").performClick()

        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText("Visit published").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("See it on your profile").performClick()

        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText("Your visits").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Your visits").assertIsDisplayed()
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("1 total").assertTextContains("1 total")
    }
}
