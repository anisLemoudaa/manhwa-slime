package eu.kanade.tachiyomi.ui.browse.extension

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionLanguageFilterTest {

    @Test
    fun arabicFilterMatchesArabicTagsOnly() {
        assertTrue(ExtensionLanguageFilter.ARABIC.includes("ar"))
        assertTrue(ExtensionLanguageFilter.ARABIC.includes("ar-SA"))
        assertTrue(ExtensionLanguageFilter.ARABIC.includes("AR_001"))
        assertFalse(ExtensionLanguageFilter.ARABIC.includes("en"))
        assertFalse(ExtensionLanguageFilter.ARABIC.includes("all"))
    }

    @Test
    fun englishFilterMatchesEnglishTagsOnly() {
        assertTrue(ExtensionLanguageFilter.ENGLISH.includes("en"))
        assertTrue(ExtensionLanguageFilter.ENGLISH.includes("en-US"))
        assertTrue(ExtensionLanguageFilter.ENGLISH.includes("EN_GB"))
        assertFalse(ExtensionLanguageFilter.ENGLISH.includes("ar"))
        assertFalse(ExtensionLanguageFilter.ENGLISH.includes("all"))
    }

    @Test
    fun combinedFilterKeepsArabicAndEnglishOnly() {
        assertTrue(ExtensionLanguageFilter.ARABIC_ENGLISH.includes("ar"))
        assertTrue(ExtensionLanguageFilter.ARABIC_ENGLISH.includes("ar-SA"))
        assertTrue(ExtensionLanguageFilter.ARABIC_ENGLISH.includes("en"))
        assertTrue(ExtensionLanguageFilter.ARABIC_ENGLISH.includes("en-US"))
        assertFalse(ExtensionLanguageFilter.ARABIC_ENGLISH.includes("fr"))
        assertFalse(ExtensionLanguageFilter.ARABIC_ENGLISH.includes("all"))
        assertFalse(ExtensionLanguageFilter.ARABIC_ENGLISH.includes(null))
    }
}
