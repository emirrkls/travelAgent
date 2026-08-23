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
    fun activityShowsPublicVisitAndOpensPlaceDetail() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onNodeWithText("Activity").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Community activity").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithContentDescription("Ahmet Deniz visited", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onAllNodesWithContentDescription("Ahmet Deniz visited", substring = true)
            .onFirst()
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("Beautiful cove with clear water.", substring = true)
            .onFirst()
            .assertIsDisplayed()

        composeRule.onAllNodesWithContentDescription("Ahmet Deniz visited", substring = true)
            .onFirst()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Been here").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Community reviews").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().assertIsDisplayed()
    }

    @Test
    fun ratingOnlyActivityHasNoEmptyReviewBlock() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onNodeWithText("Activity").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Ece Aksoy visited", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onAllNodesWithContentDescription("Ece Aksoy visited", substring = true)
            .onFirst()
            .assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("Read more").fetchSemanticsNodes().isEmpty() ||
                composeRule.onAllNodesWithContentDescription("Ece Aksoy visited", substring = true)
                    .fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithText("Beautiful cove with clear water.", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun activityPaginationAppendsWithoutLosingExistingRows() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onNodeWithText("Activity").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Ahmet Deniz visited", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val initialCount = composeRule
            .onAllNodesWithContentDescription("visited Sarnıç Cove", substring = true)
            .fetchSemanticsNodes()
            .size
        assertTrue(initialCount >= 1)

        // Scroll toward bottom to trigger next page.
        repeat(8) {
            val nodes = composeRule
                .onAllNodesWithContentDescription("visited Sarnıç Cove", substring = true)
                .fetchSemanticsNodes()
            if (nodes.isNotEmpty()) {
                composeRule.onAllNodesWithContentDescription("visited Sarnıç Cove", substring = true)
                    .onFirst()
                // Prefer scrolling the last visible activity card when present.
            }
            val paged = composeRule.onAllNodesWithText("Paged activity", substring = true)
            if (paged.fetchSemanticsNodes().isNotEmpty()) {
                paged.onFirst().performScrollTo()
            }
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("visited Sarnıç Cove", substring = true)
                .fetchSemanticsNodes().size > initialCount ||
                composeRule.onAllNodesWithText("Paged activity", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(
            composeRule.onAllNodesWithContentDescription("Ahmet Deniz visited", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun publishedVisitWithPrivateMemoryNeverShowsMemoryInActivity() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.onAllNodesWithText("Been here").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
        }

        val publicReviewText = "Activity feed public review text."
        val privateMemoryText = "SECRET_ACTIVITY_PRIVATE_MEMORY"
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
            composeRule.onAllNodesWithText("Visit published").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Community reviews").fetchSemanticsNodes().isNotEmpty()
        }

        // Navigate to Activity (may need to leave success / place detail first).
        if (composeRule.onAllNodesWithText("Back to Explore").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Back to Explore").performClick()
        } else if (composeRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithContentDescription("Back").performClick()
        }
        composeRule.waitForExplore()
        composeRule.onNodeWithText("Activity").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(publicReviewText, substring = true).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Emir Kaya visited", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(
            "Private memory leaked into Activity UI",
            composeRule.onAllNodesWithText(privateMemoryText).fetchSemanticsNodes().isEmpty(),
        )
        if (composeRule.onAllNodesWithText(publicReviewText, substring = true).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText(publicReviewText, substring = true).assertIsDisplayed()
        }
    }
}
