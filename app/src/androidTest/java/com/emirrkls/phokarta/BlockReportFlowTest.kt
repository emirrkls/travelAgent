package com.emirrkls.phokarta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class BlockReportFlowTest {
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
    fun blockRemovesProfileAndShowsBlockedListWithoutRefollow() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        openAhmetProfile()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag("profile_more").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("profile_more").performClick()
        composeRule.onNodeWithText("Block user").performClick()
        composeRule.onNodeWithText("Block this user?").assertIsDisplayed()
        composeRule.onNodeWithTag("block_confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("User blocked").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag("profile_more").fetchSemanticsNodes().isEmpty()
        }

        returnToOwnerProfile()
        composeRule.onNodeWithContentDescription("Find people").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search by name or username").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Search by name or username").performTextInput("ahmet")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Ahmet Deniz").fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithContentDescription("Back").performClick()
        openBlockedUsers()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Ahmet Deniz").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Unblock").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("No blocked users").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()

        openAhmetProfile()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Follow").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Follow").assertIsDisplayed()
    }

    @Test
    fun reportUserShowsThanks() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        openAhmetProfile()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag("profile_more").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("profile_more").performClick()
        composeRule.onNodeWithText("Report user").performClick()
        composeRule.onNodeWithText("Spam").performClick()
        composeRule.onNodeWithTag("report_submit").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Thanks. We'll review this report.").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithTag("profile_more").assertIsDisplayed()
    }

    @Test
    fun reportVisitShowsThanks() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onNodeWithText("Activity").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Report this visit").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Report this visit").onFirst().performClick()
        composeRule.onNodeWithText("Report visit").performClick()
        composeRule.onNodeWithText("Spam").performClick()
        composeRule.onNodeWithTag("report_submit").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Thanks. We'll review this report.").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun blockedListIsScopedToCurrentViewer() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        runBlocking { fakeSocial.block(AHMET_USER_ID) }

        openBlockedUsers()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Ahmet Deniz").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Back").performClick()

        fakeSocial.switchViewer(AHMET_USER_ID)
        composeRule.onNodeWithText("Blocked users").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("No blocked users").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithText("Ahmet Deniz").fetchSemanticsNodes().isEmpty())
    }

    private fun openAhmetProfile() {
        composeRule.onNodeWithText("Profile").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Find people").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Find people").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search by name or username").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Search by name or username").performTextInput("ahmet")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Ahmet Deniz").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Ahmet Deniz").performClick()
    }

    private fun returnToOwnerProfile() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Find people").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithContentDescription("Find people").fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithContentDescription("Find people").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun openBlockedUsers() {
        composeRule.onNodeWithText("Profile").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Blocked users").performClick()
    }

    private companion object {
        const val AHMET_USER_ID = "22222222-2222-2222-2222-222222222222"
    }
}
