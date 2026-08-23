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
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class CollectionsAndVisitHistoryFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun collectionFlow_createAndAddPlaceFromDetail() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onNodeWithText("Add").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText("Create collection").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Create collection").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("+ New").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("+ New collection").fetchSemanticsNodes().isNotEmpty()
        }
        when {
            composeRule.onAllNodesWithText("+ New").fetchSemanticsNodes().isNotEmpty() ->
                composeRule.onNodeWithText("+ New").performClick()
            else -> composeRule.onNodeWithText("+ New collection").performClick()
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Collection title").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("New collection").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithContentDescription("Collection title").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithContentDescription("Collection title").performTextInput("Bodrum Summer")
        } else {
            composeRule.onNodeWithText("Title").performTextInput("Bodrum Summer")
        }
        composeRule.onNodeWithText("Create collection").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Bodrum Summer").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Bodrum Summer").onFirst().assertIsDisplayed()

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForExplore()

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Add to list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Add to list").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Bodrum Summer").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Bodrum Summer").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Bodrum Summer").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Bodrum Summer").onFirst().assertIsDisplayed()
    }

    @Test
    fun visitHistory_twoVisitsNewestFirst() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Been here").fetchSemanticsNodes().isNotEmpty()
        }
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

        composeRule.onNodeWithText("Rate another visit").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Publish visit").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Your visits").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Your visits").assertIsDisplayed()
        composeRule.onNodeWithText("Rate another visit").assertIsDisplayed()
    }
}
