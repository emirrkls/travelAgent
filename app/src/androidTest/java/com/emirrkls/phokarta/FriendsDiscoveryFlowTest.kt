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
import javax.inject.Inject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class FriendsDiscoveryFlowTest {
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
        fakeSocial.seedMutualFriend()
    }

    @Test
    fun friendsActivityShowsMutualFriendAndOpensPlace() {
        openActivityFeed()

        composeRule.onNodeWithContentDescription("Friends scope").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Ahmet Deniz visited", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Ahmet Deniz visited", substring = true)
            .onFirst()
            .assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithContentDescription("Ece Aksoy visited", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithContentDescription("Deniz Community visited", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )

        composeRule.onAllNodesWithContentDescription("Ahmet Deniz visited", substring = true)
            .onFirst()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Been here").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().assertIsDisplayed()
    }

    @Test
    fun activityScopeSwitchPreservesCommunityItems() {
        openActivityFeed()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Deniz Community visited", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Friends scope").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Ahmet Deniz visited", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            composeRule.onAllNodesWithContentDescription("Deniz Community visited", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )

        composeRule.onNodeWithContentDescription("Community scope").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Deniz Community visited", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun placeScoreLensesShowCommunityFriendsAndYou() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.onAllNodesWithText("Been here").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Publish visit").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Community score", substring = true)
                .fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithContentDescription("Friends score", substring = true)
                    .fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("You").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Community score 8.7", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Friends score 9.1", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("You").assertIsDisplayed()
    }

    @Test
    fun friendsWhoVisitedOpensPublicProfile() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Friend who visited Ahmet Deniz", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Friend who visited Ahmet Deniz", substring = true)
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Friends").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Follows you").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("@ahmetgoes").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Ahmet Deniz").fetchSemanticsNodes().size > 1
        }
    }

    @Test
    fun friendReviewsShowFriendPublicReviewAndKeepCommunity() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Community reviews").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Community reviews").performScrollTo()

        composeRule.onNodeWithContentDescription("Friends scope").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Friend reviews").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Friend public review of the cove.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Friend-only review of the cove.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            composeRule.onAllNodesWithText("SECRET", substring = true).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithText("One-way visitor review.", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )

        composeRule.onNodeWithContentDescription("Community scope").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Community reviews").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "FRIENDS-only review must stay out of community",
            composeRule.onAllNodesWithText("Friend-only review of the cove.", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun friendsActivityIncludesFriendsOnlyVisitAndExcludesFromCommunity() {
        openActivityFeed()

        composeRule.onNodeWithContentDescription("Friends scope").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Friends-only cove notes.", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Community scope").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Deniz Community visited", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "FRIENDS-only activity must stay out of community",
            composeRule.onAllNodesWithText("Friends-only cove notes.", substring = true)
                .fetchSemanticsNodes().isEmpty(),
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
