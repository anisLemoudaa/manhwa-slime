package eu.kanade.tachiyomi.novel

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class NovelHistoryEntry(
    val sourceId: String,
    val novelPath: String,
    val novelTitle: String,
    val cover: String? = null,
    val chapterPath: String,
    val chapterName: String,
    val chapterNumber: Double? = null,
    val chapterIndex: Int = 0,
    val totalChapters: Int = 0,
    val readAt: Long = System.currentTimeMillis(),
)

class NovelHistoryStore(context: Context) {
    private val file =
        File(context.applicationContext.filesDir, "msl_novel_history.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun all(): List<NovelHistoryEntry> {
        if (!file.exists()) return emptyList()

        return runCatching {
            json.decodeFromString<List<NovelHistoryEntry>>(file.readText())
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun upsert(entry: NovelHistoryEntry) {
        val updated = all()
            .filterNot {
                it.sourceId == entry.sourceId &&
                    it.chapterPath == entry.chapterPath
            }
            .toMutableList()
            .apply {
                add(
                    0,
                    entry.copy(
                        readAt = System.currentTimeMillis(),
                    ),
                )
            }
            .take(MAX_ENTRIES)

        file.writeText(json.encodeToString(updated))
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    companion object {
        private const val MAX_ENTRIES = 100
    }
}
