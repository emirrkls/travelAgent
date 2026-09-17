package com.emirrkls.phokarta.ui.localization

import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.ActivityScope
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.SocialListKind
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.model.ExperienceClassification
import com.emirrkls.phokarta.core.model.OverallFeelingCode
import com.emirrkls.phokarta.core.model.PrimaryExperienceCode
import com.emirrkls.phokarta.core.model.VibeCode
import com.emirrkls.phokarta.feature.search.SearchSort
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationMappingTest {
    private val previousDefault = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(previousDefault)
    }

    @Test
    fun reportReasonLabelRes_mapsEachValue() {
        assertEquals(R.string.report_reason_spam, com.emirrkls.phokarta.core.model.ReportReason.SPAM.labelRes())
        assertEquals(R.string.report_reason_other, com.emirrkls.phokarta.core.model.ReportReason.OTHER.labelRes())
    }

    @Test
    fun visibilityLabelRes_mapsEachValue() {
        assertEquals(R.string.visibility_public, Visibility.PUBLIC.labelRes())
        assertEquals(R.string.visibility_friends, Visibility.FRIENDS.labelRes())
        assertEquals(R.string.visibility_private, Visibility.PRIVATE.labelRes())
    }

    @Test
    fun placeCategoryLabelRes_mapsEachEntry() {
        assertEquals(R.string.category_beach, PlaceCategory.BEACH.labelRes())
        assertEquals(R.string.category_food, PlaceCategory.RESTAURANT.labelRes())
        assertEquals(R.string.category_cafe, PlaceCategory.CAFE.labelRes())
        assertEquals(R.string.category_hotel, PlaceCategory.HOTEL.labelRes())
        assertEquals(R.string.category_bar, PlaceCategory.BAR.labelRes())
        assertEquals(R.string.category_nightlife, PlaceCategory.NIGHTLIFE.labelRes())
        assertEquals(R.string.category_culture, PlaceCategory.ATTRACTION.labelRes())
        assertEquals(R.string.category_activity, PlaceCategory.ACTIVITY.labelRes())
        assertEquals(R.string.category_nature, PlaceCategory.NATURE.labelRes())
    }

    @Test
    fun searchSortLabelRes_mapsEachEntry() {
        assertEquals(R.string.sort_recommended, SearchSort.DEFAULT.labelRes())
        assertEquals(R.string.sort_rating, SearchSort.RATING.labelRes())
        assertEquals(R.string.sort_recently_saved, SearchSort.RECENTLY_SAVED.labelRes())
        assertEquals(R.string.sort_friends_score, SearchSort.FRIENDS_SCORE.labelRes())
        assertEquals(R.string.sort_most_friends_visited, SearchSort.MOST_FRIENDS_VISITED.labelRes())
    }

    @Test
    fun appLanguageLocaleTags() {
        assertEquals("", AppLanguageController.localeTags(AppLanguage.SYSTEM))
        assertEquals("en", AppLanguageController.localeTags(AppLanguage.ENGLISH))
        assertEquals("tr", AppLanguageController.localeTags(AppLanguage.TURKISH))
    }

    @Test
    fun formatScore_usesLocaleDecimalSeparator() {
        assertEquals("9.1", formatScore(9.1, Locale.US))
        assertEquals("9,1", formatScore(9.1, Locale("tr", "TR")))
    }

    @Test
    fun activityScopeAndSocialListKind_areLocaleRootSafe() {
        Locale.setDefault(Locale("tr", "TR"))
        assertEquals(ActivityScope.FRIENDS, ActivityScope.fromQueryParam("friends"))
        assertEquals(ActivityScope.FRIENDS, ActivityScope.fromQueryParam("FRIENDS"))
        assertEquals("friends", ActivityScope.FRIENDS.queryParam)
        assertEquals("friends", SocialListKind.FRIENDS.routeValue)
        assertEquals(SocialListKind.FRIENDS, SocialListKind.fromRoute("friends"))
        assertEquals(SocialListKind.FRIENDS, SocialListKind.fromRoute("FRIENDS"))
    }

    @Test
    fun ratingDimensionFromStoredKey_isLocaleRootSafe() {
        assertEquals(RatingDimension.SEA, RatingDimension.fromStoredKey("sea"))
        assertEquals(RatingDimension.SEA, RatingDimension.fromStoredKey("SEA"))
        Locale.setDefault(Locale("tr", "TR"))
        assertEquals(RatingDimension.SEA, RatingDimension.fromStoredKey("sea"))
        assertEquals("SEA", RatingDimension.SEA.apiKey)
        assertEquals(RatingDimension.SEA, RatingDimension.fromStoredKey(RatingDimension.SEA.apiKey.lowercase(Locale.ROOT)))
    }

    @Test
    fun experienceTaxonomyLabels_areLocalizedWithoutWireCodes() {
        assertEquals("Breakfast", ExperienceLabels.primary(PrimaryExperienceCode.KAHVALTI, DisplayLanguage.EN))
        assertEquals("Kahvaltı", ExperienceLabels.primary(PrimaryExperienceCode.KAHVALTI, DisplayLanguage.TR))
        assertEquals("Meal", ExperienceLabels.primary(PrimaryExperienceCode.OGUN_YEMEK, DisplayLanguage.EN))
        assertEquals("Öğün / Yemek", ExperienceLabels.primary(PrimaryExperienceCode.OGUN_YEMEK, DisplayLanguage.TR))
        assertEquals("Local / authentic", ExperienceLabels.vibe(VibeCode.LOCAL_AUTHENTIC, DisplayLanguage.EN))
        assertEquals("Yerel / otantik", ExperienceLabels.vibe(VibeCode.LOCAL_AUTHENTIC, DisplayLanguage.TR))
        assertNull(ExperienceLabels.primary(PrimaryExperienceCode.UNKNOWN_LEGACY, DisplayLanguage.EN))
    }

    @Test
    fun feelingLabels_matchLockedEnglishAndTurkishPresentation() {
        assertEquals("😍 Loved it", ExperienceLabels.feeling(OverallFeelingCode.BAYILDIM, DisplayLanguage.EN))
        assertEquals("😍 Bayıldım", ExperienceLabels.feeling(OverallFeelingCode.BAYILDIM, DisplayLanguage.TR))
        assertEquals("😐 It was okay", ExperienceLabels.feeling(OverallFeelingCode.EH_ISTE, DisplayLanguage.EN))
        assertEquals("😐 Eh işte", ExperienceLabels.feeling(OverallFeelingCode.EH_ISTE, DisplayLanguage.TR))
    }

    @Test
    fun legacyPlaceFallbackTitle_isDeduplicatedOnlyForLegacyCards() {
        assertFalse(shouldShowExperienceTitle("Foça", " foça ", ExperienceClassification.LEGACY_COMPATIBILITY))
        assertTrue(shouldShowExperienceTitle("A quiet sunset", "Foça", ExperienceClassification.LEGACY_COMPATIBILITY))
        assertTrue(shouldShowExperienceTitle("Foça", "Foça", ExperienceClassification.NATIVE_V2))
    }

    @Test
    fun composerDisclosureAndDimensionSelection_defaultToCompactOptionalState() {
        val initial = ComposerDisclosureState()
        assertFalse(initial.storyExpanded)
        assertFalse(initial.titleExpanded)
        assertTrue(initial.toggleStory().storyExpanded)
        assertTrue(initial.toggleTitle().titleExpanded)
        assertEquals(listOf("Select", "Very good", "Good", "Medium", "Weak", "Very weak"), ExperienceLabels.dimensionChoices(DisplayLanguage.EN).map { it.second })
        assertEquals(listOf("Seç", "Çok iyi", "İyi", "Orta", "Zayıf", "Çok zayıf"), ExperienceLabels.dimensionChoices(DisplayLanguage.TR).map { it.second })
    }

    @Test
    fun milestone35FinalPolishPresentationRules_arePreserved() {
        assertEquals(R.string.experience_tip_title, R.string.experience_tip_title)
        assertEquals(R.string.experience_friend_state, R.string.experience_friend_state)
        assertEquals("Sea / swimming", ExperienceLabels.primary(PrimaryExperienceCode.DENIZ_YUZME, DisplayLanguage.EN))
        assertEquals("Deniz / Yüzme", ExperienceLabels.primary(PrimaryExperienceCode.DENIZ_YUZME, DisplayLanguage.TR))
        val filterLabel = "${ExperienceLabels.primary(PrimaryExperienceCode.DENIZ_YUZME, DisplayLanguage.TR)} · 3"
        assertEquals("Deniz / Yüzme · 3", filterLabel)
    }
}
