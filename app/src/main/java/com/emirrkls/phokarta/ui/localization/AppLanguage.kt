package com.emirrkls.phokarta.ui.localization

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * App language preference for Settings.
 * SYSTEM clears the per-app locale override so the system language is used.
 */
enum class AppLanguage {
    SYSTEM,
    ENGLISH,
    TURKISH,
}

object AppLanguageController {
    fun current(): AppLanguage {
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (tags.isBlank()) return AppLanguage.SYSTEM
        val primary = tags.substringBefore(',').substringBefore('-').lowercase()
        return when (primary) {
            "en" -> AppLanguage.ENGLISH
            "tr" -> AppLanguage.TURKISH
            else -> AppLanguage.SYSTEM
        }
    }

    fun apply(language: AppLanguage) {
        val locales = when (language) {
            AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            AppLanguage.ENGLISH -> LocaleListCompat.forLanguageTags("en")
            AppLanguage.TURKISH -> LocaleListCompat.forLanguageTags("tr")
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** Mapping for unit tests — no display labels. */
    fun localeTags(language: AppLanguage): String = when (language) {
        AppLanguage.SYSTEM -> ""
        AppLanguage.ENGLISH -> "en"
        AppLanguage.TURKISH -> "tr"
    }

    @StringRes
    fun labelRes(language: AppLanguage): Int = when (language) {
        AppLanguage.SYSTEM -> com.emirrkls.phokarta.R.string.language_system_default
        AppLanguage.ENGLISH -> com.emirrkls.phokarta.R.string.language_english
        AppLanguage.TURKISH -> com.emirrkls.phokarta.R.string.language_turkish
    }
}
