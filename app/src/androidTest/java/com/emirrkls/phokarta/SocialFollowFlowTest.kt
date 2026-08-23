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
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class SocialFollowFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun searchOpenProfileFollowAndMutualFriends() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onNodeWithText("Profile").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Find people").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Find people").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Find people").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Search by name or username").performTextInput("ahmet")

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Ahmet Deniz").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Ahmet Deniz").onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Follows you").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithContentDescription("Follow").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Follows you").onFirst().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Follow").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Friends").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Friends").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Friends").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Follows you").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Follows you").onFirst().assertIsDisplayed()
    }
}
