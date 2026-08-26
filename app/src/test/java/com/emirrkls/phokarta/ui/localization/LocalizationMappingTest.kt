package com.emirrkls.phokarta.ui.localization

import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.ActivityScope
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.SocialListKind
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.feature.search.SearchSort
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
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
}
