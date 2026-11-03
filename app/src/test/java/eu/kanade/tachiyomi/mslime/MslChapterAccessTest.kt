package eu.kanade.tachiyomi.mslime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MslChapterAccessTest {
    @Test
    fun mangaKeyMatchesTheExistingStableReadingTaskIdentity() {
        val accessKey = MslChapterAccess.mangaKey(42L, "https://source.example/series/a", "/chapter/7")
        val readingKey = MslReadingTasks.canonicalChapterKey(42L, "https://source.example/series/a", "/chapter/7")

        assertEquals("manga:<sha256>", accessKey?.replace(Regex("[0-9a-f]{64}$"), "<sha256>"))
        assertEquals(readingKey, accessKey)
    }

    @Test
    fun mangaKeyTrimsIdentityAndDoesNotUseTitleOrDatabaseRowId() {
        val first = MslChapterAccess.mangaKey(42L, " https://source.example/series/a ", " /chapter/7 ")
        val normalized = MslChapterAccess.mangaKey(42L, "https://source.example/series/a", "/chapter/7")
        val differentSource = MslChapterAccess.mangaKey(43L, "https://source.example/series/a", "/chapter/7")
        val differentChapter = MslChapterAccess.mangaKey(42L, "https://source.example/series/a", "/chapter/8")

        assertEquals(normalized, first)
        assertNotEquals(first, differentSource)
        assertNotEquals(first, differentChapter)
        assertTrue(first?.matches(Regex("^manga:[0-9a-f]{64}$")) == true)
    }

    @Test
    fun novelKeyIncludesSourceNovelAndChapter() {
        val first = MslChapterAccess.novelKey("plugin-a", "/novel/one", "/chapter/1")
        assertNotEquals(first, MslChapterAccess.novelKey("plugin-b", "/novel/one", "/chapter/1"))
        assertNotEquals(first, MslChapterAccess.novelKey("plugin-a", "/novel/two", "/chapter/1"))
        assertNotEquals(first, MslChapterAccess.novelKey("plugin-a", "/novel/one", "/chapter/2"))
        assertTrue(first?.matches(Regex("^novel:[0-9a-f]{64}$")) == true)
    }

    @Test
    fun communityKeyRequiresTwoUuids() {
        val key = MslChapterAccess.communityKey(
            "d59a34c1-cd59-4d7a-97b2-090ae89c5934",
            "7bc8845a-9726-4570-a04d-7e240ebc95ba",
        )
        assertEquals(
            "community:d59a34c1-cd59-4d7a-97b2-090ae89c5934:7bc8845a-9726-4570-a04d-7e240ebc95ba",
            key,
        )
        assertNull(MslChapterAccess.communityKey("not-a-uuid", "also-not-a-uuid"))
    }
}
