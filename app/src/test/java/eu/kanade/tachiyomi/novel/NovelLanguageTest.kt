package eu.kanade.tachiyomi.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NovelLanguageTest {

    @Test
    fun normalizesArabicNamesAndTags() {
        assertEquals("ar", "ar".toNovelLanguageCode())
        assertEquals("ar", "ar-EG".toNovelLanguageCode())
        assertEquals("ar", "AR_001".toNovelLanguageCode())
        assertEquals("ar", "العربية".toNovelLanguageCode())
        assertEquals("ar", "\u200fالعربية".toNovelLanguageCode())
    }

    @Test
    fun normalizesEnglishNamesAndTags() {
        assertEquals("en", "en".toNovelLanguageCode())
        assertEquals("en", "en-US".toNovelLanguageCode())
        assertEquals("en", "English".toNovelLanguageCode())
        assertEquals("en", "English (US)".toNovelLanguageCode())
    }

    @Test
    fun rejectsUnrecognizedAndOtherLanguages() {
        assertEquals(null, "fr".toNovelLanguageCode())
        assertEquals(null, "العربية/English".toNovelLanguageCode())
        assertEquals(null, null.toNovelLanguageCode())
    }
}
