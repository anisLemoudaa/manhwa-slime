package eu.kanade.tachiyomi.mslime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MslTranslationCoreTest {
    @Test
    fun splitPreservesSeparatorsAndResponseOrder() {
        val source = "one two\nthree four"
        val chunks = MslTranslationText.splitPreservingSeparators(source, maxChars = 7)

        assertEquals(source, MslTranslationText.joinTranslated(chunks, chunks.map { it.source }))
        assertEquals(listOf(0, 1, 2), chunks.map { it.index })
        assertEquals("\n", chunks[0].separatorAfter)
    }

    @Test
    fun aSingleLongTokenIsSplitWithoutDroppingCharacters() {
        val source = "abcdefghij"
        val chunks = MslTranslationText.splitPreservingSeparators(source, maxChars = 3)

        assertEquals(source, MslTranslationText.joinTranslated(chunks, chunks.map { it.source }))
        assertTrue(chunks.all { it.source.length <= 3 })
    }

    @Test
    fun longTokenAfterTextKeepsItsSeparatorOnlyOnce() {
        val source = "one  abcdefgh"
        val chunks = MslTranslationText.splitPreservingSeparators(source, maxChars = 3)

        assertEquals(source, MslTranslationText.joinTranslated(chunks, chunks.map { it.source }))
    }

    @Test
    fun emptyResponseIsNotUsable() {
        assertFalse(MslTranslationText.isUsableTranslation(" \n\t"))
        assertTrue(MslTranslationText.isUsableTranslation("Arabic"))
        assertNull(MslTranslationText.joinTranslated(listOf(MslTranslationChunk(0, "a", "")), emptyList()))
    }

    @Test
    fun cacheIsBoundedAndKeysNeverContainSource() {
        val store = FakeCacheStore()
        val cache = MslBoundedTranslationCache(store, maxEntries = 2)
        cache.put("first source", "الأول")
        cache.put("second source", "الثاني")
        cache.put("third source", "الثالث")

        assertNull(cache.get("first source"))
        assertEquals("الثاني", cache.get("second source"))
        assertEquals("الثالث", cache.get("third source"))
        assertTrue(store.values.keys.all { it.length == 64 })
        assertTrue(store.values.keys.none { it.contains("first", ignoreCase = true) })
    }

    @Test
    fun emptyGlossaryDoesNotInventTermsAndExactPhraseCanBeRestored() {
        assertTrue(MslGlossary.fromJson("{}").entries.isEmpty())

        val glossary = MslGlossary(mapOf("Captain" to "كابتن"))
        val protected = glossary.protect("Captain arrived at Captaincy")
        val token = protected.replacements.keys.single()
        assertTrue(protected.text.startsWith(token))
        assertEquals("كابتن arrived at Captaincy", protected.restore("$token arrived at Captaincy"))
        assertNull(protected.restore("وصل القبطان من دون العلامة"))
    }

    @Test
    fun englishOnlyGateRejectsJapaneseAndAllowsEnglish() {
        assertTrue(MslTranslationText.isEnglishCandidate("A quiet English line."))
        assertFalse(MslTranslationText.isEnglishCandidate("こんにちは"))
        assertFalse(MslTranslationText.isEnglishCandidate("Hello مرحبًا"))
    }

    private class FakeCacheStore : MslTranslationCacheStore {
        val values = linkedMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun keys(): Set<String> = values.keys
    }
}
