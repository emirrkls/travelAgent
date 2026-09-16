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
class SavedDiscoveryFlowTest {
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
    fun exploreSave_showsMyPlanAndUnsaveUpdates() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        saveSarnicFromExplore()
        openMyPlan()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Saved").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Remove from Want to Go").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Saved").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Want to go").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithContentDescription("Remove from Want to Go")
                    .fetchSemanticsNodes().isEmpty()
        }

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isEmpty() ||
                composeRule.onAllNodesWithText("Nothing saved yet").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun searchSavedFilter_showsMatchingPlace() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        saveSarnicFromExplore()
        openMyPlan()
        composeRule.onNodeWithText("Search saved places").performTextInput("Sarnıç")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().assertIsDisplayed()
    }

    @Test
    fun wantToGo_showsFriendSignalMatchingPlaceDetail() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        saveSarnicFromExplore()
        openMyPlan()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Friends 9.1", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("1 friend visited").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Friends visited").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Friends score 9.1", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun saveSarnicFromExplore() {
        composeRule.openSarnicPlaceFromExplore()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Want to go").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Saved").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithContentDescription("Want to go").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onAllNodesWithContentDescription("Want to go").onFirst().performClick()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Saved").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Remove from Want to Go")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForExplore()
    }

    private fun openMyPlan() {
        composeRule.onNodeWithText("My Plan").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Places I Want to Go").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Search saved places").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
