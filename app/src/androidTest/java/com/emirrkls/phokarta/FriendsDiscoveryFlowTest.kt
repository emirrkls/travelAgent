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
    fun followingShowsMutualFriendExperienceAndOpensPlace() {
        openFollowingFeed()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Sunset at Sarnıç Cove")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Ahmet Deniz").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("Ece Aksoy")
                .fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithText("Deniz Community")
                .fetchSemanticsNodes().isEmpty(),
        )

        composeRule.onNodeWithText("Sarnıç Cove").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Been here").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().assertIsDisplayed()
    }

    @Test
    fun lensSwitchingReplacesAndRestoresExperienceItems() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onNodeWithText("Popular").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("A slow breakfast by Quiet Bay")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Following").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Sunset at Sarnıç Cove")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            composeRule.onAllNodesWithText("A slow breakfast by Quiet Bay")
                .fetchSemanticsNodes().isEmpty(),
        )

        composeRule.onNodeWithText("Popular").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("A slow breakfast by Quiet Bay")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun placeShowsExperienceFirstAggregateAndFeed() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("What did people experience here?")
                .fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("2 visible to you · 4 community contributions")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("What did people experience here?").assertIsDisplayed()
        composeRule.onNodeWithText("Community feeling").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Experience dimensions").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Practical signals").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sunset at Sarnıç Cove").performScrollTo().assertIsDisplayed()
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
    fun followingExcludesOneWayAndUnrelatedAuthors() {
        openFollowingFeed()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Sunset at Sarnıç Cove")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Following must exclude an unrelated author",
            composeRule.onAllNodesWithText("Deniz Community")
                .fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "Following must exclude a one-way non-followed author",
            composeRule.onAllNodesWithText("Ece Aksoy")
                .fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onNodeWithText("Friends").assertIsDisplayed()
    }

    private fun openFollowingFeed() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()
        composeRule.onNodeWithText("Following").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Sunset at Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
