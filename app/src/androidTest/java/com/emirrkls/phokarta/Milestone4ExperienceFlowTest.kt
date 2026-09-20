package com.emirrkls.phokarta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import com.emirrkls.phokarta.core.data.VisitDraftRepository
import com.emirrkls.phokarta.feature.rating.VisitDraft
import com.emirrkls.phokarta.core.model.OverallFeelingCode
import com.emirrkls.phokarta.core.model.PrimaryExperienceCode
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.model.CreateCollectionDto
import com.emirrkls.phokarta.core.network.model.VisibilityDto
import com.emirrkls.phokarta.core.network.source.CollectionRemoteDataSource
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

    @Inject lateinit var collections: CollectionRemoteDataSource
    @Inject lateinit var drafts: VisitDraftRepository

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
        composeRule.onAllNodesWithText("Your draft was restored").assertCountEquals(0)
    }

    @Test
    fun noMediaDetailIsCompactAndAcknowledgedStateIsSelectedNotDisabled() {
        openExplore()
        composeRule.onAllNodesWithText("I Experienced This Too").onFirst().performScrollTo().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("✓ I Experienced This Too").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("✓ I Experienced This Too").onFirst()
            .assertIsDisplayed().assertIsSelected().assertIsEnabled()

        composeRule.onNodeWithText("Search experiences, places or feelings")
            .performTextInput("quiet swim")
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("A quiet swim at Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("A quiet swim at Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Photo-free experience").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Photo-free experience").assertIsDisplayed()
    }

    @Test
    fun mixedCollectionSummaryCountsItemsNotOnlyPlaces() {
        runBlocking {
            val created = collections.create(
                CreateCollectionDto("Mixed", "", VisibilityDto.PRIVATE, ""),
            ) as RemoteResult.Success
            collections.addPlace(created.value.id, PLACE_ID)
            collections.addExperience(created.value.id, EXPERIENCE_ID)
        }
        openExplore()
        composeRule.onNodeWithText("My Plan").performClick()
        composeRule.onNodeWithText("Collections").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Mixed").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Mixed").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("2 items · Private").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("2 items · Private").assertIsDisplayed()
        repeat(3) {
            if (composeRule.onAllNodesWithText("Experience").fetchSemanticsNodes().isEmpty()) {
                composeRule.onRoot().performTouchInput { swipeUp() }
                composeRule.waitForIdle()
            }
        }
        composeRule.onNodeWithText("Experience").fetchSemanticsNode()
        repeat(3) {
            if (composeRule.onAllNodesWithText("Place").fetchSemanticsNodes().isEmpty()) {
                composeRule.onRoot().performTouchInput { swipeUp() }
                composeRule.waitForIdle()
            }
        }
        composeRule.onNodeWithText("Place").fetchSemanticsNode()
    }

    @Test
    fun genuineAcknowledgementDraftRestoreStillShowsContextualFeedback() {
        openExplore()
        composeRule.onAllNodesWithText("I Experienced This Too").onFirst().performScrollTo().performClick()
        composeRule.onNodeWithText("Profile").performClick()
        composeRule.onAllNodesWithText("I Experienced This Too").onFirst().performScrollTo().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Add your own experience").fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking {
            drafts.saveDraft(
                PLACE_ID,
                VisitDraft(
                    payloadVersion = 2,
                    primaryExperience = PrimaryExperienceCode.GUN_BATIMI,
                    overallFeeling = OverallFeelingCode.GUZELDI,
                    story = "Recovered user draft",
                ),
                USER_ID,
            )
        }
        composeRule.onNodeWithText("Add your own experience").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Your draft was restored").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Your draft was restored").assertIsDisplayed()
    }

    private fun openExplore() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()
    }

    private companion object {
        const val USER_ID = "11111111-1111-1111-1111-111111111111"
        const val EXPERIENCE_ID = "30000000-0000-0000-0000-000000000501"
        const val PLACE_ID = "20000000-0000-0000-0000-000000000003"
    }
}
