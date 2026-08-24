package com.emirrkls.phokarta

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Focused localization connected coverage.
 * Avoids Google Maps (known AVD dynamite CloneNotSupported flakes).
 * Does not call setApplicationLocales mid-test (AppCompat activity recreate can crash the instrumented process on this AVD).
 */
@HiltAndroidTest
class LocalizationLanguageSwitchTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun inject() {
        hiltRule.inject()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @After
    fun resetLocales() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun profileSettings_showsLanguageOptions() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()

        composeRule.onNodeWithText("Profile").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Language").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Language").assertIsDisplayed()
        composeRule.onNodeWithText("System default").assertIsDisplayed()
        composeRule.onNodeWithText("English").assertIsDisplayed()
        composeRule.onNodeWithText("Türkçe").assertIsDisplayed()
    }
}

@HiltAndroidTest
class LocalizationTurkishSmokeTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setTurkishLocale() {
        // Apply before activity content settles on Turkish resources.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("tr"))
        hiltRule.inject()
    }

    @After
    fun resetLocales() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun exploreProfileAndSettings_showTurkishLabels() {
        skipOnboardingLocalized()
        signInLocalized()
        waitForExploreLocalized()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Keşfet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Keşfet").onFirst().assertIsDisplayed()

        composeRule.onNodeWithText("Profil").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Ayarlar").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Ayarlar").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Dil").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Ayarlar").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Türkçe").assertIsDisplayed()
        composeRule.onNodeWithText("Sistem varsayılanı").assertIsDisplayed()
    }

    private fun skipOnboardingLocalized() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Atla").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Giriş yap").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
        }
        when {
            composeRule.onAllNodesWithText("Atla").fetchSemanticsNodes().isNotEmpty() ->
                composeRule.onNodeWithText("Atla").performClick()
            composeRule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() ->
                composeRule.onNodeWithText("Skip").performClick()
        }
    }

    private fun signInLocalized() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Giriş yap").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Sırada nereye", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val needsLogin =
            composeRule.onAllNodesWithText("E-posta veya kullanıcı adı").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Email or username").fetchSemanticsNodes().isNotEmpty()
        if (!needsLogin) return

        when {
            composeRule.onAllNodesWithText("E-posta veya kullanıcı adı").fetchSemanticsNodes().isNotEmpty() ->
                composeRule.onNodeWithText("E-posta veya kullanıcı adı").performTextInput("demo@phokarta.local")
            else -> composeRule.onNodeWithText("Email or username").performTextInput("demo@phokarta.local")
        }
        when {
            composeRule.onAllNodesWithText("Şifre").fetchSemanticsNodes().isNotEmpty() ->
                composeRule.onNodeWithText("Şifre").performTextInput("password1")
            else -> composeRule.onNodeWithText("Password").performTextInput("password1")
        }
        when {
            composeRule.onAllNodesWithText("Giriş yap").fetchSemanticsNodes().isNotEmpty() ->
                composeRule.onAllNodesWithText("Giriş yap").onFirst().performClick()
            else -> composeRule.onNodeWithText("Sign in").performClick()
        }
    }

    private fun waitForExploreLocalized() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Sırada nereye", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Keşfet").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
