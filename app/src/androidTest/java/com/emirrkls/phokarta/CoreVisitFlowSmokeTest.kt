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

        composeRule.openSarnicPlaceFromExplore()
        composeRule.openVisitComposerFromCurrentPlace()
        composeRule.completeMinimumExperience("Core connected smoke Experience.")
        composeRule.onNodeWithText("Publish visit").assertIsDisplayed()
        composeRule.onNodeWithText("Publish visit").performClick()

        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText("Your visits").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Visit added").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Rate another visit").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForExplore()
        composeRule.onNodeWithText("Profile").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText("My Experiences").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("My Experiences").assertIsDisplayed()
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("A quiet swim at Sarnıç Cove").assertIsDisplayed()
    }
}
