package com.emirrkls.phokarta

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.os.LocaleListCompat
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.model.CreateCollectionDto
import com.emirrkls.phokarta.core.network.model.VisibilityDto
import com.emirrkls.phokarta.core.network.source.CollectionRemoteDataSource
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Produces the review package from real Compose screens and deterministic network fixtures. */
@HiltAndroidTest
class Milestone4ScreenshotTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var fakeVisits: FakeVisits
    @Inject lateinit var collections: CollectionRemoteDataSource

    @Before
    fun seedReviewContent() {
        hiltRule.inject()
        fakeVisits.resetMilestone4()
        runBlocking {
            fakeVisits.planExperience(EXPERIENCE_ID)
            fakeVisits.acknowledgeExperience(EXPERIENCE_ID)
            val created = collections.create(
                CreateCollectionDto(
                    title = "Bodrum Summer",
                    description = "Places and experiences for a slow Aegean weekend.",
                    visibility = VisibilityDto.PRIVATE,
                    coverImage = "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=1200",
                ),
            ) as RemoteResult.Success
            collections.addPlace(created.value.id, PLACE_ID)
            collections.addExperience(created.value.id, EXPERIENCE_ID)
        }
    }

    @After
    fun restoreLocale() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun captureLightReviewPackage() {
        openExploreEnglish()
        capture("01_experience_card_actions_light.png")

        openExperience("Add Experience to Collection")
        capture("02_experience_detail_ack_light.png")
        composeRule.onAllNodesWithText("I Experienced This Too").onFirst().performScrollTo().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("experienced this too", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        capture("10_experience_source_count_light.png")

        returnToRoot()
        composeRule.onNodeWithText("My Plan").performClick()
        composeRule.onNodeWithText("Experiences").performClick()
        waitFor("Sunset at Sarnıç Cove")
        capture("03_plan_experiences_light.png")
        composeRule.onNodeWithText("Places").performClick()
        capture("04_plan_places_light.png")
        composeRule.onNodeWithText("Collections").performClick()
        waitFor("Bodrum Summer")
        composeRule.onAllNodesWithText("Bodrum Summer").onFirst().performClick()
        waitFor("Sunset at Sarnıç Cove")
        capture("05_collection_mixed_light.png")

        returnToRoot()
        composeRule.onNodeWithText("Profile").performClick()
        waitFor("I Experienced This Too")
        capture("06_profile_also_experienced_light.png")
        composeRule.onAllNodesWithText("I Experienced This Too").onFirst().performScrollTo().performClick()
        waitFor("Add your own experience")
        capture("07_ack_conversion_cta_light.png")
        composeRule.onNodeWithText("Add your own experience").performClick()
        waitFor("You experienced this too")
        capture("08_composer_prefilled_light.png")

        composeRule.completeMinimumExperience("My own sunset notes from Sarnıç Cove.")
        composeRule.onNodeWithText("Share experience").performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Profile").fetchSemanticsNodes().isNotEmpty()
        }
        returnToRoot()
        composeRule.onNodeWithText("Profile").performClick()
        capture("09_profile_after_conversion_light.png")
    }

    @Test
    fun captureDarkReviewPackage() {
        openExploreEnglish()
        openExperience("Add Experience to Collection")
        capture("11_experience_detail_dark.png")
        returnToRoot()
        composeRule.onNodeWithText("My Plan").performClick()
        composeRule.onNodeWithText("Experiences").performClick()
        waitFor("Sunset at Sarnıç Cove")
        capture("12_plan_experiences_dark.png")
        composeRule.onNodeWithText("Profile").performClick()
        waitFor("I Experienced This Too")
        capture("13_profile_also_experienced_dark.png")
    }

    @Test
    fun captureTurkishReviewPackage() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("tr"))
        openExploreLocalized()
        composeRule.onNodeWithText("Planım").performClick()
        composeRule.onNodeWithText("Deneyimler").performClick()
        waitFor("Sunset at Sarnıç Cove")
        capture("14_plan_experiences_tr.png")
        composeRule.onNodeWithText("Profil").performClick()
        waitFor("Ben de Yaşadım")
        capture("15_ben_de_yasadim_tr.png")
        composeRule.onAllNodesWithText("Ben de Yaşadım").onFirst().performScrollTo().performClick()
        waitFor("Kendi deneyimini ekle")
        composeRule.onNodeWithText("Kendi deneyimini ekle").performClick()
        waitFor("Bunu sen de yaşadın")
        capture("16_composer_prefilled_tr.png")
    }

    @Test
    fun captureLargeFontReviewPackage() {
        openExploreEnglish()
        composeRule.onNodeWithText("My Plan").performClick()
        composeRule.onNodeWithText("Experiences").performClick()
        waitFor("Sunset at Sarnıç Cove")
        capture("17_plan_experiences_120.png")
        composeRule.onNodeWithText("Profile").performClick()
        waitFor("I Experienced This Too")
        capture("18_profile_ack_120.png")
    }

    private fun openExploreEnglish() {
        composeRule.skipOnboardingIfNeeded()
        composeRule.signInIfNeeded()
        composeRule.waitForExplore()
    }

    private fun openExploreLocalized() {
        composeRule.waitUntil(10_000) {
            listOf("Atla", "Skip", "Giriş yap", "Sign in", "Sarnıç Cove").any {
                text -> composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        }
        firstVisible("Atla", "Skip")?.performClick()
        composeRule.waitUntil(10_000) {
            listOf("E-posta veya kullanıcı adı", "Email or username", "Sarnıç Cove").any {
                text -> composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
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
        waitFor("Sarnıç Cove")
    }

    private fun openExperience(readyText: String) {
        composeRule.onAllNodesWithText("Sunset at Sarnıç Cove").onFirst().performClick()
        waitFor(readyText)
    }

    private fun returnToRoot() {
        repeat(4) {
            if (composeRule.onAllNodesWithText("My Plan").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Planım").fetchSemanticsNodes().isNotEmpty()
            ) return
            composeRule.runOnUiThread {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitForIdle()
        }
    }

    private fun waitFor(text: String) {
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun firstVisible(vararg labels: String) = labels.firstNotNullOfOrNull { label ->
        composeRule.onAllNodesWithText(label).let { nodes ->
            if (nodes.fetchSemanticsNodes().isEmpty()) null else nodes.onFirst()
        }
    }

    private fun capture(fileName: String) {
        composeRule.waitForIdle()
        val resolver = composeRule.activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Phokarta/Milestone4")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri).use { output ->
            checkNotNull(output)
            check(
                composeRule.onRoot().captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private companion object {
        const val EXPERIENCE_ID = "30000000-0000-0000-0000-000000000501"
        const val PLACE_ID = "20000000-0000-0000-0000-000000000003"
    }
}
