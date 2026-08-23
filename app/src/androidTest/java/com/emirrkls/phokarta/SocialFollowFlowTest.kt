package com.emirrkls.phokarta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class SocialFollowFlowTest {
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
    }

    @Test
    fun ownerProfileLiveCountsRefreshAfterFollowUnfollow() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onNodeWithText("Profile").performClick()
        waitForOwnerProfile()
        assertOwnerCounts(followers = 1, following = 0, friends = 0)

        openAhmetProfile()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Follows you").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithContentDescription("Follow").fetchSemanticsNodes().isNotEmpty()
        }
        // Public profile still shows its own backend-backed counters (11 before follow).
        composeRule.onAllNodesWithText("11").onFirst().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Follow").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Friends").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Friends").assertIsDisplayed()
        composeRule.onAllNodesWithText("12").onFirst().assertIsDisplayed()

        navigateBackToOwnerProfile()
        assertOwnerCounts(followers = 1, following = 1, friends = 1)

        openAhmetProfile()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Friends").fetchSemanticsNodes().isNotEmpty()
        }
        // FollowActionButton shows "Friends" while mutual; tapping toggles unfollow.
        composeRule.onNodeWithContentDescription("Friends").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Follow").fetchSemanticsNodes().isNotEmpty()
        }

        navigateBackToOwnerProfile()
        assertOwnerCounts(followers = 1, following = 0, friends = 0)
    }

    private fun openAhmetProfile() {
        composeRule.onNodeWithContentDescription("Find people").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search by name or username").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Search by name or username").performTextInput("ahmet")
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Ahmet Deniz").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Ahmet Deniz").onFirst().performClick()
    }

    private fun navigateBackToOwnerProfile() {
        // Nested social routes hide the bottom bar; pop public profile then search.
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Find people").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithContentDescription("Find people").fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithContentDescription("Back").performClick()
        }
        waitForOwnerProfile()
    }

    private fun waitForOwnerProfile() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Find people").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertOwnerCounts(followers: Int, following: Int, friends: Int) {
        val followersCd = "$followers Followers"
        val followingCd = "$following Following"
        val friendsCd = "$friends Friends"
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription(followersCd).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithContentDescription(followingCd).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithContentDescription(friendsCd).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(followersCd).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(followingCd).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(friendsCd).assertIsDisplayed()
    }
}
