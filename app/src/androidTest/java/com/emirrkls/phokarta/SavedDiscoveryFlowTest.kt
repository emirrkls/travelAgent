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
class SavedDiscoveryFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun exploreSave_showsWantToGoShelf_openAndUnsaveUpdates() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onAllNodesWithContentDescription("Want to go").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Want to Go").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Want to Go").onFirst().assertIsDisplayed()

        composeRule.onAllNodesWithText("See all").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("saved", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Saved").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Remove from Want to Go").fetchSemanticsNodes().isNotEmpty()
        }
        when {
            composeRule.onAllNodesWithContentDescription("Remove from Want to Go").fetchSemanticsNodes().isNotEmpty() ->
                composeRule.onAllNodesWithContentDescription("Remove from Want to Go").onFirst().performClick()
            else -> composeRule.onAllNodesWithText("Saved").onFirst().performClick()
        }

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForExplore()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Want to Go").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun searchSavedFilter_showsMatchingPlace() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onAllNodesWithContentDescription("Want to go").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithContentDescription("Remove from Want to Go").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Search places, cities or categories").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText("Discover").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Want to Go", substring = false).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().assertIsDisplayed()
    }
}
