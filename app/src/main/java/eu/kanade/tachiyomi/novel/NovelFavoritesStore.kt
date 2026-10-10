package eu.kanade.tachiyomi.novel

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class NovelFavoriteEntry(
    val sourceId: String,
    val novelPath: String,
    val title: String,
    val cover: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

class NovelFavoritesStore(context: Context) {

    private val file =
        File(context.applicationContext.filesDir, "msl_novel_favorites.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun all(): List<NovelFavoriteEntry> {
        if (!file.exists()) return emptyList()

        return runCatching {
            json.decodeFromString<List<NovelFavoriteEntry>>(file.readText())
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun contains(sourceId: String, novelPath: String): Boolean {
        return all().any {
            it.sourceId == sourceId && it.novelPath == novelPath
        }
    }

    @Synchronized
    fun toggle(entry: NovelFavoriteEntry): Boolean {
        val current = all().toMutableList()

        val index = current.indexOfFirst {
            it.sourceId == entry.sourceId &&
                it.novelPath == entry.novelPath
        }

        return if (index >= 0) {
            current.removeAt(index)
            file.writeText(json.encodeToString(current))
            false
        } else {
            current.add(
                0,
                entry.copy(
                    addedAt = System.currentTimeMillis(),
                ),
            )
            file.writeText(json.encodeToString(current.take(MAX_ENTRIES)))
            true
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    @Synchronized
    fun replace(entries: List<NovelFavoriteEntry>) {
        file.writeText(json.encodeToString(entries.take(MAX_ENTRIES)))
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}
