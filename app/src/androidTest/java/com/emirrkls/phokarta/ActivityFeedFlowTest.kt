package com.emirrkls.phokarta

import androidx.compose.ui.semantics.SemanticsProperties
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
import org.junit.Assert.assertFalse
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
        openActivityFeed()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Ahmet Deniz visited", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Ahmet Deniz visited", substring = true)
            .onFirst()
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("Beautiful cove with clear water.", substring = true)
            .onFirst()
            .assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithContentDescription("score 9.1", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )

        composeRule.onAllNodesWithContentDescription("Ahmet Deniz visited", substring = true)
            .onFirst()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Been here").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Community reviews").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().assertIsDisplayed()
    }

    @Test
    fun ratingOnlyActivityHasNoEmptyReviewBlock() {
        openActivityFeed()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Ece Aksoy visited", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule
            .onAllNodesWithContentDescription("Ece Aksoy visited", substring = true)
            .onFirst()
            .assertIsDisplayed()

        val description = composeRule
            .onAllNodesWithContentDescription("Ece Aksoy visited", substring = true)
            .fetchSemanticsNodes()
            .first()
            .config[SemanticsProperties.ContentDescription]
            .joinToString(" ")

        assertTrue("Expected rating-only semantics", description.contains("rating only", ignoreCase = true))
        assertTrue("Expected score in rating-only card", description.contains("score 8.7", ignoreCase = true))
        assertFalse(
            "Rating-only card should not include review quote text",
            description.contains("Beautiful cove", ignoreCase = true),
        )
        assertTrue(
            "Public review event should still be present on the feed",
            composeRule.onAllNodesWithText("Beautiful cove with clear water.", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun activityPaginationAppendsWithoutLosingExistingRows() {
        openActivityFeed()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Ahmet Deniz visited", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val initialCount = composeRule
            .onAllNodesWithContentDescription("visited Sarnıç Cove", substring = true)
            .fetchSemanticsNodes()
            .size
        assertTrue(initialCount >= 1)

        repeat(8) {
            val cards = composeRule.onAllNodesWithContentDescription("visited Sarnıç Cove", substring = true)
            val count = cards.fetchSemanticsNodes().size
            if (count > 0) {
                cards[count - 1].performScrollTo()
            }
            val pagedText = composeRule.onAllNodesWithText("Paged activity", substring = true)
            if (pagedText.fetchSemanticsNodes().isNotEmpty()) {
                pagedText.onFirst().performScrollTo()
            }
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("visited Sarnıç Cove", substring = true)
                .fetchSemanticsNodes().size > initialCount ||
                composeRule.onAllNodesWithText("Paged activity", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(
            "Original first-page event must remain after pagination",
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
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
        }

        val publicReviewText = "Activity feed public review text."
        val privateMemoryText = "SECRET_ACTIVITY_PRIVATE_MEMORY"
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Public review input").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Public review input")
            .performScrollTo()
            .performTextInput(publicReviewText)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Private memory input").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Private memory input")
            .performScrollTo()
            .performTextInput(privateMemoryText)
        composeRule.onNodeWithText("Publish visit").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Visit published").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Community reviews").fetchSemanticsNodes().isNotEmpty()
        }

        if (composeRule.onAllNodesWithText("Back to Explore").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Back to Explore").performClick()
        } else if (composeRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithContentDescription("Back").performClick()
        }
        composeRule.waitForExplore()
        composeRule.onNodeWithText("Activity").performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText(publicReviewText, substring = true).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Emir Kaya visited", substring = true)
                    .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Emir Kaya · You visited", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(
            "Private memory leaked into Activity UI",
            composeRule.onAllNodesWithText(privateMemoryText).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "Published public review should appear in Activity",
            composeRule.onAllNodesWithText(publicReviewText, substring = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription(publicReviewText, substring = true)
                    .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun openActivityFeed() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()
        composeRule.onNodeWithText("Activity").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Community activity").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
