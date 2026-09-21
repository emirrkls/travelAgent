package com.emirrkls.phokarta

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Captures Milestone 5 design-review evidence from the real Compose conversation surface. */
@HiltAndroidTest
class Milestone5ScreenshotTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var fakeVisits: FakeVisits

    @Before
    fun seed() {
        hiltRule.inject()
        fakeVisits.resetMilestone5()
    }

    @After
    fun restoreAppearance() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        composeRule.runOnUiThread {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    @Test
    fun captureLightConversationPackage() {
        setLight()
        openExploreEnglish()
        capture("01_experience_card_conversation.png")
        capture("06_experience_card_count.png")
        openConversationEnglish()
        capture("02_conversation_populated_compact.png")

        scrollTo("Is the cove quiet near sunset?")
        capture("03_conversation_question.png")
        capture("04_question_timestamp.png")
        scrollTo("Private-profile participant: bring water; the nearest kiosk closes early.")
        capture("04_conversation_comment.png")
        capture("19_private_profile_public_comment.png")
        scrollTo("Author answer")
        capture("05_author_answer.png")
        capture("05_author_answer_timestamp.png")
        capture("06_multiple_replies.png")

        composeRule.onNodeWithTag("conversation_actions_$QUESTION_ID").performScrollTo().performClick()
        composeRule.onNodeWithTag("conversation_edit_$QUESTION_ID").performClick()
        capture("09_edit_entry.png")
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithTag("conversation_actions_$COMMENT_ID").performScrollTo().performClick()
        capture("10_entry_menu_other_user.png")
        composeRule.onNodeWithTag("conversation_report_$COMMENT_ID").performClick()
        capture("11_report_flow.png")
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

    }

    @Test
    fun captureEmptyConversation() {
        fakeVisits.resetMilestone5(seed = false)
        setLight()
        openExploreEnglish()
        openConversationEnglish()
        scrollTo("No questions or comments yet. Start the conversation.")
        capture("02_conversation_empty.png")
        capture("01_conversation_empty_expanded.png")
    }

    @Test
    fun captureLightComposers() {
        fakeVisits.resetMilestone5(seed = false)
        setLight()
        openExploreEnglish()
        openConversationEnglish()
        composeRule.onNodeWithTag("conversation_section").performScrollTo()
        composeRule.onNodeWithTag("conversation_composer").performTextInput("Is parking available after sunset?")
        capture("07_create_question.png")
        capture("03_conversation_composer_expanded.png")
        composeRule.onNodeWithTag("conversation_composer").performTextClearance()
        composeRule.onNodeWithText("Comment").performClick()
        composeRule.onNodeWithTag("conversation_composer").performTextInput("The western path has a beautiful view.")
        capture("08_create_comment.png")
    }

    @Test
    fun captureDarkConversationPackage() {
        composeRule.runOnUiThread {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
        openExploreEnglish()
        openConversationEnglish()
        capture("12_conversation_dark.png")
        capture("07_conversation_dark.png")
        scrollTo("Author answer")
        capture("13_author_answer_dark.png")
    }

    @Test
    fun captureTurkishConversationPackage() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("tr"))
        openExploreLocalized()
        openConversationLocalized()
        capture("14_conversation_tr.png")
        capture("08_conversation_tr.png")
        composeRule.onNodeWithTag("conversation_compact_composer").performClick()
        composeRule.onNodeWithTag("conversation_composer").performTextInput("Gün batımında sakin mi?")
        capture("15_question_composer_tr.png")
        scrollTo("Deneyim sahibinin yanıtı")
        capture("16_author_answer_tr.png")
    }

    @Test
    fun captureLargeFontConversationPackage() {
        setLight()
        openExploreEnglish()
        openConversationEnglish()
        capture("17_conversation_120.png")
        capture("09_conversation_120.png")
        composeRule.onNodeWithTag("conversation_compact_composer").performClick()
        composeRule.onNodeWithTag("conversation_composer").performTextInput("Is the path accessible?")
        capture("18_question_composer_120.png")
    }

    private fun setLight() {
        composeRule.runOnUiThread {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    private fun openExploreEnglish() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()
    }

    private fun openExploreLocalized() {
        composeRule.waitUntil(10_000) {
            listOf("Atla", "Skip", "Giriş yap", "Sign in", "Sarnıç Cove").any { text ->
                composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        }
        firstVisible("Atla", "Skip")?.performClick()
        composeRule.waitUntil(10_000) {
            listOf("E-posta veya kullanıcı adı", "Email or username", "Sarnıç Cove").any { text ->
                composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        }
        if (composeRule.onAllNodesWithText("E-posta veya kullanıcı adı").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("E-posta veya kullanıcı adı").performTextInput("demo@phokarta.local")
            composeRule.onNodeWithText("Şifre").performTextInput("password1")
            composeRule.onAllNodesWithText("Giriş yap").onFirst().performClick()
        } else if (composeRule.onAllNodesWithText("Email or username").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Email or username").performTextInput("demo@phokarta.local")
            composeRule.onNodeWithText("Password").performTextInput("password1")
            composeRule.onAllNodesWithText("Sign in").onFirst().performClick()
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openConversationEnglish() {
        composeRule.onAllNodesWithText("Sunset at Sarnıç Cove").onFirst().performClick()
        waitForDetail("Add Experience to Collection")
        revealConversation("Questions & Comments")
    }

    private fun openConversationLocalized() {
        composeRule.onAllNodesWithText("Sunset at Sarnıç Cove").onFirst().performClick()
        waitForDetail("Deneyimi Koleksiyona Ekle")
        revealConversation("Sorular ve Yorumlar")
    }

    private fun waitForDetail(text: String) {
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun revealConversation(title: String) {
        repeat(10) {
            if (composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()) {
                composeRule.onRoot().performTouchInput { swipeUp() }
                composeRule.waitForIdle()
            }
        }
        composeRule.onNodeWithTag("conversation_section").performScrollTo()
    }

    private fun scrollTo(text: String) {
        repeat(8) {
            if (composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
                composeRule.onRoot().performTouchInput { swipeUp() }
                composeRule.waitForIdle()
            }
        }
        composeRule.onAllNodesWithText(text).onFirst().performScrollTo()
        composeRule.waitForIdle()
    }

    private fun firstVisible(vararg labels: String) = labels.firstNotNullOfOrNull { label ->
        composeRule.onAllNodesWithText(label).let { nodes ->
            if (nodes.fetchSemanticsNodes().isEmpty()) null else nodes.onFirst()
        }
    }

    private fun capture(fileName: String) {
        composeRule.waitForIdle()
        // UiAutomation can observe the frame immediately before the last Compose scroll/recomposition.
        // Give SurfaceFlinger one stable frame so the review image matches the asserted UI state.
        SystemClock.sleep(750)
        val resolver = composeRule.activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Phokarta/Milestone5")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri).use { output ->
            checkNotNull(output)
            val bitmap = checkNotNull(
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(),
            )
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private companion object {
        const val QUESTION_ID = "50000000-0000-0000-0000-000000000601"
        const val COMMENT_ID = "50000000-0000-0000-0000-000000000602"
    }
}
