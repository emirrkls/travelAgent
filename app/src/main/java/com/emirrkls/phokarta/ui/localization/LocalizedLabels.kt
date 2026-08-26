package com.emirrkls.phokarta.ui.localization

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.ReportReason
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.feature.search.SearchSort
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Score adjective bands — UI resolves via [labelRes]. */
enum class ScoreBand {
    TERRIBLE,
    DISAPPOINTING,
    OKAY,
    GOOD,
    AMAZING,
    EXCEPTIONAL,
}

fun scoreBandFor(score: Float): ScoreBand = when {
    score >= 9.5f -> ScoreBand.EXCEPTIONAL
    score >= 9f -> ScoreBand.AMAZING
    score >= 7f -> ScoreBand.GOOD
    score >= 5f -> ScoreBand.OKAY
    score >= 2f -> ScoreBand.DISAPPOINTING
    else -> ScoreBand.TERRIBLE
}

@StringRes
fun ScoreBand.labelRes(): Int = when (this) {
    ScoreBand.EXCEPTIONAL -> R.string.score_exceptional
    ScoreBand.AMAZING -> R.string.score_amazing
    ScoreBand.GOOD -> R.string.score_good
    ScoreBand.OKAY -> R.string.score_okay
    ScoreBand.DISAPPOINTING -> R.string.score_disappointing
    ScoreBand.TERRIBLE -> R.string.score_terrible
}

@StringRes
fun PlaceCategory.labelRes(): Int = when (this) {
    PlaceCategory.BEACH -> R.string.category_beach
    PlaceCategory.RESTAURANT -> R.string.category_food
    PlaceCategory.CAFE -> R.string.category_cafe
    PlaceCategory.HOTEL -> R.string.category_hotel
    PlaceCategory.BAR -> R.string.category_bar
    PlaceCategory.NIGHTLIFE -> R.string.category_nightlife
    PlaceCategory.ATTRACTION -> R.string.category_culture
    PlaceCategory.ACTIVITY -> R.string.category_activity
    PlaceCategory.NATURE -> R.string.category_nature
}

@StringRes
fun RatingDimension.labelRes(): Int = when (this) {
    RatingDimension.SEA -> R.string.dimension_sea
    RatingDimension.ATMOSPHERE -> R.string.dimension_atmosphere
    RatingDimension.SERVICE -> R.string.dimension_service
    RatingDimension.CLEANLINESS -> R.string.dimension_cleanliness
    RatingDimension.VALUE -> R.string.dimension_value
    RatingDimension.CROWD -> R.string.dimension_crowd
    RatingDimension.FOOD -> R.string.dimension_food
    RatingDimension.PRESENTATION -> R.string.dimension_presentation
    RatingDimension.LOCATION -> R.string.dimension_location
    RatingDimension.ROOM -> R.string.dimension_room
    RatingDimension.BREAKFAST -> R.string.dimension_breakfast
    RatingDimension.DRINKS -> R.string.dimension_drinks
    RatingDimension.MUSIC -> R.string.dimension_music
    RatingDimension.EXPERIENCE -> R.string.dimension_experience
    RatingDimension.ACCESS -> R.string.dimension_access
    RatingDimension.SAFETY -> R.string.dimension_safety
    RatingDimension.GUIDE -> R.string.dimension_guide
    RatingDimension.SCENERY -> R.string.dimension_scenery
    RatingDimension.TRANQUILITY -> R.string.dimension_tranquility
}

@StringRes
fun Visibility.labelRes(): Int = when (this) {
    Visibility.PUBLIC -> R.string.visibility_public
    Visibility.FRIENDS -> R.string.visibility_friends
    Visibility.PRIVATE -> R.string.visibility_private
}

@StringRes
fun ReportReason.labelRes(): Int = when (this) {
    ReportReason.SPAM -> R.string.report_reason_spam
    ReportReason.HARASSMENT -> R.string.report_reason_harassment
    ReportReason.HATE_OR_ABUSE -> R.string.report_reason_hate
    ReportReason.SEXUAL_CONTENT -> R.string.report_reason_sexual
    ReportReason.VIOLENCE_OR_THREAT -> R.string.report_reason_violence
    ReportReason.IMPERSONATION -> R.string.report_reason_impersonation
    ReportReason.PRIVACY -> R.string.report_reason_privacy
    ReportReason.OTHER -> R.string.report_reason_other
}

@StringRes
fun Visibility.sheetDescriptionRes(): Int = when (this) {
    Visibility.PUBLIC -> R.string.visibility_public_sheet
    Visibility.FRIENDS -> R.string.visibility_friends_sheet
    Visibility.PRIVATE -> R.string.visibility_private_sheet
}

@StringRes
fun Visibility.reviewHelperRes(): Int = when (this) {
    Visibility.PUBLIC -> R.string.visibility_public_review_helper
    Visibility.FRIENDS -> R.string.visibility_friends_review_helper
    Visibility.PRIVATE -> R.string.visibility_private_review_helper
}

@StringRes
fun Visibility.impactHintRes(): Int = when (this) {
    Visibility.PUBLIC -> R.string.visibility_public_impact
    Visibility.FRIENDS -> R.string.visibility_friends_impact
    Visibility.PRIVATE -> R.string.visibility_private_impact
}

@StringRes
fun SearchSort.labelRes(): Int = when (this) {
    SearchSort.DEFAULT -> R.string.sort_recommended
    SearchSort.RATING -> R.string.sort_rating
    SearchSort.RECENTLY_SAVED -> R.string.sort_recently_saved
    SearchSort.FRIENDS_SCORE -> R.string.sort_friends_score
    SearchSort.MOST_FRIENDS_VISITED -> R.string.sort_most_friends_visited
}

/**
 * Active app locale for presentation (respects per-app language override).
 */
@Composable
fun appLocale(): Locale {
    val config = LocalConfiguration.current
    @Suppress("DEPRECATION")
    return config.locales[0] ?: Locale.getDefault()
}

fun formatScore(value: Double, locale: Locale): String {
    val format = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    return format.format(value)
}

fun formatScore(value: Float, locale: Locale): String = formatScore(value.toDouble(), locale)

@Composable
fun formatScoreLocalized(value: Double): String = formatScore(value, appLocale())

@Composable
fun formatScoreLocalized(value: Float): String = formatScore(value, appLocale())

fun formatMediumDate(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

fun formatLongDate(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))

fun formatShortMonthDay(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofPattern("MMM d", locale))

fun formatMonthYear(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofPattern("MMM yyyy", locale))

@Composable
fun formatMediumDateLocalized(date: LocalDate): String = formatMediumDate(date, appLocale())

@Composable
fun formatLongDateLocalized(date: LocalDate): String = formatLongDate(date, appLocale())

@Composable
fun formatShortMonthDayLocalized(date: LocalDate): String = formatShortMonthDay(date, appLocale())

@Composable
fun formatMonthYearLocalized(date: LocalDate): String = formatMonthYear(date, appLocale())

@Composable
fun stringRes(@StringRes id: Int, vararg formatArgs: Any): String =
    if (formatArgs.isEmpty()) stringResource(id) else stringResource(id, *formatArgs)

@Composable
fun pluralRes(@PluralsRes id: Int, count: Int, vararg formatArgs: Any): String =
    if (formatArgs.isEmpty()) {
        pluralStringResource(id, count, count)
    } else {
        pluralStringResource(id, count, *formatArgs)
    }

fun Resources.friendsVisitedLabel(count: Int): String = when (count) {
    0 -> getString(R.string.no_friend_visits_yet)
    else -> getQuantityString(R.plurals.friends_visited_count, count, count)
}

fun Resources.communityVisitCountLabel(count: Int): String = when (count) {
    0 -> getString(R.string.no_community_ratings_yet)
    else -> getQuantityString(R.plurals.visits_count, count, count)
}
