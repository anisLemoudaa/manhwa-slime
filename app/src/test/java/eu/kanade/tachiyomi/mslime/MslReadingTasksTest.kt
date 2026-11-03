package eu.kanade.tachiyomi.mslime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MslReadingTasksTest {
    @Test
    fun canonicalChapterKeyIsStableAndDoesNotUseLocalRowIds() {
        val first = MslReadingTasks.canonicalChapterKey(12, "https://source.test/manga/a", "/chapter/34")
        assertNotNull(first)
        assertEquals(first, MslReadingTasks.canonicalChapterKey(12, "https://source.test/manga/a", "/chapter/34"))
        assertNotEquals(first, MslReadingTasks.canonicalChapterKey(12, "https://source.test/manga/a", "/chapter/35"))
        assertNotEquals(first, MslReadingTasks.canonicalChapterKey(13, "https://source.test/manga/a", "/chapter/34"))
        assertNull(MslReadingTasks.canonicalChapterKey(12, "", "/chapter/34"))
    }

    @Test
    fun parsesServerTaskSnapshotAndClampsProgress() {
        val snapshot = MslReadingTasks.parseSnapshot(
            """
            {"date":"2026-10-17","tasks":[
              {"id":"daily_read_3","title":"قارئ اليوم","description":"اقرأ","reward_coins":15,
               "target_count":3,"progress":9,"claimed":false,"claimable":true}
            ]}
            """.trimIndent(),
        )
        assertEquals("2026-10-17", snapshot?.date)
        assertEquals(1, snapshot?.tasks?.size)
        assertEquals(3, snapshot?.tasks?.single()?.progress)
        assertTrue(snapshot?.tasks?.single()?.claimable == true)
    }

    @Test
    fun malformedTaskPayloadReturnsEmptyList() {
        assertTrue(MslReadingTasks.parseTasks("{\"tasks\":[{\"id\":null}]}").isEmpty())
    }
}
