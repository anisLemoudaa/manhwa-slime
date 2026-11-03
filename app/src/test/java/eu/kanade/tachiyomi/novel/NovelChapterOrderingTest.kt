package eu.kanade.tachiyomi.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NovelChapterOrderingTest {

    @Test
    fun sortsChapterNumbersNumericallyAndKeepsUnnumberedChaptersLast() {
        val chapters = listOf(
            NovelChapter("Chapter 10", "/10", chapterNumber = 10.0),
            NovelChapter("Special", "/special"),
            NovelChapter("Chapter 2", "/2", chapterNumber = 2.0),
            NovelChapter("Chapter 2.5", "/2.5", chapterNumber = 2.5),
            NovelChapter("Extra", "/extra"),
        )

        val result = orderNovelChapters(chapters, descending = false)

        assertEquals(listOf("/2", "/2.5", "/10", "/special", "/extra"), result.map { it.second.path })
        assertEquals(listOf(2, 3, 0, 1, 4), result.map { it.first })
    }

    @Test
    fun descendingSortKeepsUnnumberedChaptersInSourceOrder() {
        val chapters = listOf(
            NovelChapter("Special A", "/a"),
            NovelChapter("Chapter 2", "/2", chapterNumber = 2.0),
            NovelChapter("Special B", "/b"),
            NovelChapter("Chapter 12", "/12", chapterNumber = 12.0),
        )

        val result = orderNovelChapters(chapters, descending = true)

        assertEquals(listOf("/12", "/2", "/a", "/b"), result.map { it.second.path })
    }

    @Test
    fun sourceOrderCanBeRestoredExactly() {
        val chapters = listOf(
            NovelChapter("Chapter 10", "/10", chapterNumber = 10.0),
            NovelChapter("Chapter 2", "/2", chapterNumber = 2.0),
        )

        assertEquals(chapters.mapIndexed { index, chapter -> index to chapter }, orderNovelChapters(chapters, descending = null))
    }
}
