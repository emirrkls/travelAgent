package com.emirrkls.phokarta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.emirrkls.phokarta.core.sync.MutationSyncEngine
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Milestone5ConversationFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var fakeVisits: FakeVisits
    @Inject lateinit var fakeSocial: FakeSocial
    @Inject lateinit var syncEngine: MutationSyncEngine

    @Before
    fun reset() {
        hiltRule.inject()
        fakeSocial.reset()
        fakeVisits.resetMilestone5()
    }

    @Test
    fun rendersOneLevelHierarchyAuthorAnswerAndPrivateProfileParticipant() {
        openConversation()

        composeRule.onNodeWithText("Is the cove quiet near sunset?").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Author answer").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Private-profile participant: bring water; the nearest kiosk closes early.",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_reply_$QUESTION_ID").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_reply_$AUTHOR_REPLY_ID").assertDoesNotExist()
        composeRule.onNodeWithTag("conversation_reply_$PARTICIPANT_REPLY_ID").assertDoesNotExist()
        assertEquals(2, composeRule.onAllNodesWithText("Reply").fetchSemanticsNodes().size)
    }

    @Test
    fun createsEditsAndDeletesRootsAndReplies() {
        fakeVisits.resetMilestone5(seed = false)
        openConversation()

        createRoot("Can I reach the cove without a car?", comment = false)
        drainAndWaitFor("Can I reach the cove without a car?")
        val questionId = checkNotNull(fakeVisits.lastConversationEntryId)

        createRoot("The western path has the best sunset view.", comment = true)
        drainAndWaitFor("The western path has the best sunset view.")
        val commentId = checkNotNull(fakeVisits.lastConversationEntryId)
        composeRule.onNodeWithTag("conversation_reply_$commentId").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Can I reach the cove without a car?").performScrollTo()
        composeRule.onNodeWithTag("conversation_actions_$questionId").performClick()
        composeRule.onNodeWithTag("conversation_edit_$questionId").performClick()
        composeRule.onNodeWithTag("conversation_modal_body").performTextClearance()
        composeRule.onNodeWithTag("conversation_modal_body").performTextInput("Can I reach it without a car or taxi?")
        composeRule.onNodeWithTag("conversation_modal_confirm").performClick()
        runBlocking { syncEngine.drain() }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Can I reach it without a car or taxi?").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Edited").assertIsDisplayed()

        composeRule.onNodeWithTag("conversation_reply_$questionId").performScrollTo().performClick()
        composeRule.onNodeWithTag("conversation_modal_body").performTextInput("The local minibus stops nearby.")
        composeRule.onNodeWithTag("conversation_modal_confirm").performClick()
        drainAndWaitFor("The local minibus stops nearby.")
        val replyId = checkNotNull(fakeVisits.lastConversationEntryId)
        composeRule.onNodeWithTag("conversation_reply_$replyId").assertDoesNotExist()

        composeRule.onNodeWithTag("conversation_actions_$replyId").performScrollTo().performClick()
        composeRule.onNodeWithTag("conversation_delete_$replyId").performClick()
        composeRule.onNodeWithTag("conversation_delete_confirm").performClick()
        runBlocking { syncEngine.drain() }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("The local minibus stops nearby.").fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithTag("conversation_reply_$questionId").performScrollTo().performClick()
        composeRule.onNodeWithTag("conversation_modal_body").performTextInput("Second reply for cascade coverage.")
        composeRule.onNodeWithTag("conversation_modal_confirm").performClick()
        drainAndWaitFor("Second reply for cascade coverage.")
        composeRule.onNodeWithText("Can I reach it without a car or taxi?").performScrollTo()
        composeRule.onNodeWithTag("conversation_actions_$questionId").performClick()
        composeRule.onNodeWithTag("conversation_delete_$questionId").performClick()
        composeRule.onNodeWithText("This entry and all of its replies will be removed.").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_delete_confirm").performClick()
        runBlocking { syncEngine.drain() }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Second reply for cascade coverage.").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun reportsAndBlocksAnotherParticipantsEntry() {
        openConversation()
        composeRule.onNodeWithTag("conversation_actions_$COMMENT_ID").performScrollTo().performClick()
        composeRule.onNodeWithTag("conversation_report_$COMMENT_ID").performClick()
        composeRule.onNodeWithText("Spam").performClick()
        composeRule.onNodeWithTag("report_submit").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Thanks. We'll review this report.").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Close").performClick()

        composeRule.onNodeWithTag("conversation_actions_$COMMENT_ID").performClick()
        composeRule.onNodeWithTag("conversation_block_$COMMENT_ID").performClick()
        composeRule.onNodeWithTag("block_confirm").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(
                "Private-profile participant: bring water; the nearest kiosk closes early.",
            ).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("Is the cove quiet near sunset?").assertIsDisplayed()
    }

    @Test
    fun pendingOfflineRootCanBeRetriedWithoutDuplicate() {
        fakeVisits.resetMilestone5(seed = false)
        fakeVisits.failConversationMutations = true
        openConversation()
        createRoot("Offline question", comment = false)
        composeRule.onNodeWithText("Sending…").assertIsDisplayed()

        runBlocking { syncEngine.drain() }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Not sent").fetchSemanticsNodes().isNotEmpty()
        }
        fakeVisits.failConversationMutations = false
        composeRule.onNodeWithText("Retry").performClick()
        runBlocking { syncEngine.drain() }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Offline question").fetchSemanticsNodes().size == 1 &&
                composeRule.onAllNodesWithText("Not sent").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun openConversation() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()
        composeRule.onAllNodesWithText("Sunset at Sarnıç Cove").onFirst().performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Add Experience to Collection").fetchSemanticsNodes().isNotEmpty()
        }
        repeat(10) {
            if (composeRule.onAllNodesWithText("Questions & Comments").fetchSemanticsNodes().isNotEmpty()) {
                return@repeat
            }
            composeRule.onRoot().performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("conversation_section").performScrollTo()
    }

    private fun createRoot(text: String, comment: Boolean) {
        if (comment) composeRule.onNodeWithText("Comment").performClick()
        composeRule.onNodeWithTag("conversation_composer").performTextInput(text)
        composeRule.onNodeWithTag("conversation_send").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun drainAndWaitFor(text: String) {
        runBlocking { syncEngine.drain() }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Sending…").fetchSemanticsNodes().isEmpty()
        }
    }

    private companion object {
        const val QUESTION_ID = "50000000-0000-0000-0000-000000000601"
        const val COMMENT_ID = "50000000-0000-0000-0000-000000000602"
        const val AUTHOR_REPLY_ID = "50000000-0000-0000-0000-000000000611"
        const val PARTICIPANT_REPLY_ID = "50000000-0000-0000-0000-000000000612"
    }
}
