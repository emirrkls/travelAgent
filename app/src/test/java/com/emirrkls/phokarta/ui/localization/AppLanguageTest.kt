package com.emirrkls.phokarta.ui.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun localeTags_matchExpectedApplicationOverride() {
        assertEquals("", AppLanguageController.localeTags(AppLanguage.SYSTEM))
        assertEquals("en", AppLanguageController.localeTags(AppLanguage.ENGLISH))
        assertEquals("tr", AppLanguageController.localeTags(AppLanguage.TURKISH))
    }

    @Test
    fun labelRes_mapsEachLanguage() {
        assertEquals(
            com.emirrkls.phokarta.R.string.language_system_default,
            AppLanguageController.labelRes(AppLanguage.SYSTEM),
        )
        assertEquals(
            com.emirrkls.phokarta.R.string.language_english,
            AppLanguageController.labelRes(AppLanguage.ENGLISH),
        )
        assertEquals(
            com.emirrkls.phokarta.R.string.language_turkish,
            AppLanguageController.labelRes(AppLanguage.TURKISH),
        )
    }
}
