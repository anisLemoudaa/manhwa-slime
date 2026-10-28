package eu.kanade.tachiyomi.novel

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class NovelReadMarker(
    val sourceId: String,
    val novelPath: String,
    val chapterPath: String,
    val markedAt: Long = System.currentTimeMillis(),
)

internal fun novelReadMarkerKey(sourceId: String, novelPath: String, chapterPath: String): String =
    listOf(sourceId, novelPath, chapterPath).joinToString("\u0000")

/** Persistent, uncapped read-state store, separate from the recent-reading history. */
class NovelReadStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "msl_novel_read_chapters.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun all(): Set<String> = readMarkers().mapTo(mutableSetOf(), ::key)

    @Synchronized
    fun contains(sourceId: String, novelPath: String, chapterPath: String): Boolean =
        key(sourceId, novelPath, chapterPath) in all()

    @Synchronized
    fun markRead(sourceId: String, novelPath: String, chapterPath: String) {
        if (sourceId.isBlank() || novelPath.isBlank() || chapterPath.isBlank()) return
        val marker = NovelReadMarker(sourceId, novelPath, chapterPath)
        val markers = readMarkers().filterNot { key(it) == key(marker) }.toMutableList()
        markers += marker
        file.writeText(json.encodeToString(markers))
    }

    private fun readMarkers(): List<NovelReadMarker> =
        if (!file.exists()) emptyList()
        else runCatching { json.decodeFromString<List<NovelReadMarker>>(file.readText()) }.getOrDefault(emptyList())

    private fun key(marker: NovelReadMarker): String = novelReadMarkerKey(marker.sourceId, marker.novelPath, marker.chapterPath)

    private fun key(sourceId: String, novelPath: String, chapterPath: String): String =
        novelReadMarkerKey(sourceId, novelPath, chapterPath)
}
