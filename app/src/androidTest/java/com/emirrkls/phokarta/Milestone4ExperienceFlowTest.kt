package com.emirrkls.phokarta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Milestone4ExperienceFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var fakeVisits: FakeVisits

    @Before
    fun setUp() {
        hiltRule.inject()
        fakeVisits.resetMilestone4()
    }

    @Test
    fun planExperience_appearsInMyPlanAndOpensDetail() {
        openExplore()
        composeRule.onAllNodesWithText("Add to My Plan").onFirst().performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("In My Plan").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("My Plan").performClick()
        composeRule.onNodeWithText("Experiences").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Sunset at Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Sunset at Sarnıç Cove").onFirst().assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Add Experience to Collection").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Add Experience to Collection").onFirst().assertIsDisplayed()
    }

    @Test
    fun acknowledgeExperience_appearsInProfileAndPrefillsConversion() {
        openExplore()
        composeRule.onAllNodesWithText("I Experienced This Too").onFirst().performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("1 person experienced this too").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Profile").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("I Experienced This Too").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("I Experienced This Too").onFirst().performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Add your own experience").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("Add your own experience").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("You experienced this too").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("You experienced this too").assertIsDisplayed()
        composeRule.onAllNodesWithText("Sunset").onFirst().assertIsDisplayed()
    }

    private fun openExplore() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()
    }
}
