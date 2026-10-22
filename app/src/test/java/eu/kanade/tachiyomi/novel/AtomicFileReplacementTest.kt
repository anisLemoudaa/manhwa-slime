package eu.kanade.tachiyomi.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class AtomicFileReplacementTest {

    @Test
    fun replacesExistingFileWithoutDeletingTheOldFileFirst() {
        val directory = Files.createTempDirectory("novel-offline-store").toFile()
        try {
            val target = File(directory, "index.json").apply { writeText("old") }
            val temporary = File(directory, "index.json.tmp").apply { writeText("new") }

            atomicReplaceFile(temporary, target)

            assertEquals("new", target.readText())
            assertFalse(temporary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingTemporaryFileLeavesExistingFileUntouched() {
        val directory = Files.createTempDirectory("novel-offline-store").toFile()
        try {
            val target = File(directory, "index.json").apply { writeText("preserved") }
            val missingTemporary = File(directory, "missing.tmp")

            assertThrows(Exception::class.java) { atomicReplaceFile(missingTemporary, target) }

            assertTrue(target.exists())
            assertEquals("preserved", target.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
