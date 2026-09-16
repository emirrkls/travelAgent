package com.emirrkls.phokarta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
class ActivityFeedFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun forYouShowsExperienceAndOpensDetail() {
        openExperienceFeed()

        composeRule.onNodeWithText("For You").assertIsDisplayed()
        composeRule.onNodeWithText("Following").assertIsDisplayed()
        composeRule.onNodeWithText("Nearby").assertIsDisplayed()
        composeRule.onNodeWithText("Popular").assertIsDisplayed()
        composeRule.onNodeWithText("Sunset at Sarnıç Cove").assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Experience").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(
                    "The cove turned gold as the sun slipped behind the hills.",
                    substring = true,
                ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Sarnıç Cove").assertIsDisplayed()
    }

    @Test
    fun noMediaExperienceRendersAsACompleteCard() {
        openExperienceFeed()

        composeRule.onNodeWithText("Search experiences, places or feelings")
            .performTextInput("quiet swim")
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("A quiet swim at Sarnıç Cove")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("A quiet swim at Sarnıç Cove")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Clear water and a calm morning without a photo.")
            .assertIsDisplayed()
    }

    @Test
    fun popularLensShowsMultiMediaExperience() {
        openExperienceFeed()

        composeRule.onNodeWithText("Popular").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("A slow breakfast by Quiet Bay")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("A slow breakfast by Quiet Bay")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("3 photos").assertIsDisplayed()
    }

    @Test
    fun followingLensShowsOnlyFollowedAuthors() {
        openExperienceFeed()

        composeRule.onNodeWithText("Following").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Sunset at Sarnıç Cove")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Ahmet Deniz").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("A slow breakfast by Quiet Bay")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun searchFiltersTheExperienceFeed() {
        openExperienceFeed()

        composeRule.onNodeWithText("Search experiences, places or feelings")
            .performTextInput("breakfast")
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("A slow breakfast by Quiet Bay")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("A slow breakfast by Quiet Bay")
            .onFirst()
            .assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("Sunset at Sarnıç Cove")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun openExperienceFeed() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()
    }
}
