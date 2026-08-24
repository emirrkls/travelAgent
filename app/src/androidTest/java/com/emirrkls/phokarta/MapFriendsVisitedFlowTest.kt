package com.emirrkls.phokarta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class MapFriendsVisitedFlowTest {
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
    fun friendsVisitedFilter_keepsOnlyFriendSignalPlaces() {
        openMap()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Quiet Bay").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Friends visited filter").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Quiet Bay").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Friends visited filter, selected").assertIsDisplayed()
    }

    @Test
    fun wantToGoAndFriendsVisited_showsSavedFriendOverlap() {
        openMap()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Save Sarnıç Cove to Want to Go").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithContentDescription("Remove Sarnıç Cove from Want to Go")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Want to Go").performClick()
        composeRule.onNodeWithContentDescription("Friends visited filter").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Quiet Bay").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onAllNodesWithText("Friends 9.1").onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("1 friend visited").onFirst().assertIsDisplayed()
    }

    @Test
    fun selectedFriendPlace_opensDetailWithMatchingFriendsSignal() {
        openMap()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Friends 9.1").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("1 friend visited").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Friends score 9.1", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Friends score 9.1", substring = true)
            .onFirst()
            .assertIsDisplayed()
    }

    @Test
    fun savedVisitedFriends_allStatesRemainVisible() {
        openMap()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Save Sarnıç Cove to Want to Go").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithContentDescription("Remove Sarnıç Cove from Want to Go")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Friends 9.1").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Saved", substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Friends 9.1").onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("1 friend visited").onFirst().assertIsDisplayed()
    }

    @Test
    fun placeWithoutFriendSignal_hasNoFriendsLine() {
        openMap()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Quiet Bay").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Quiet Bay", substring = false).onFirst().assertIsDisplayed()
        assert(
            composeRule.onAllNodesWithContentDescription("Quiet Bay, Friends", substring = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    @Test
    fun enrichmentError_keepsBaseMapAndExposesRetryOnFriendsFilter() {
        fakeSocial.failFriendMetrics = true
        openMap()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Friend signals are unavailable right now.").onFirst().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Friends visited filter").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText("Friends visited isn’t available").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Retry friends").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Retry friends").onFirst().assertIsDisplayed()
    }

    private fun openMap() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()
        composeRule.onNodeWithText("Map").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Map discovery").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
