package eu.kanade.tachiyomi.novel

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

@Serializable
data class OfflineNovelChapter(
    val path: String,
    val title: String,
    val number: Double? = null,
    val order: Int = 0,
)

@Serializable
data class OfflineNovelBook(
    val sourceId: String,
    val novelPath: String,
    val title: String,
    val cover: String? = null,
    val description: String? = null,
    val isCommunity: Boolean = false,
    val contentNotice: String = "general",
    val chapters: List<OfflineNovelChapter> = emptyList(),
)

/** Private on-device storage for downloaded novel chapters and the metadata needed to browse them offline. */
class NovelOfflineStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "msl_novel_downloads").apply { mkdirs() }
    private val indexFile = File(directory, "index.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(sourceId: String, novelPath: String, chapterPath: String): String? {
        val target = chapterFile(sourceId, novelPath, chapterPath)
        return target.takeIf(File::isFile)?.readText(Charsets.UTF_8)
    }

    @Synchronized
    fun save(
        sourceId: String,
        novelPath: String,
        chapterPath: String,
        html: String,
        title: String = "رواية محفوظة",
        cover: String? = null,
        description: String? = null,
        chapterTitle: String = "فصل محفوظ",
        chapterNumber: Double? = null,
        order: Int = 0,
        isCommunity: Boolean = false,
        contentNotice: String = "general",
    ) {
        require(sourceId.isNotBlank() && novelPath.isNotBlank() && chapterPath.isNotBlank())
        val target = chapterFile(sourceId, novelPath, chapterPath)
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(html, Charsets.UTF_8)
        try {
            atomicReplaceFile(temporary, target)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }

        val existing = readBooks().toMutableList()
        val oldBook = existing.firstOrNull { it.sourceId == sourceId && it.novelPath == novelPath }
        val bookChapters = oldBook?.chapters.orEmpty().filterNot { it.path == chapterPath } +
            OfflineNovelChapter(chapterPath, chapterTitle, chapterNumber, order)
        val book = OfflineNovelBook(
            sourceId = sourceId,
            novelPath = novelPath,
            title = title.ifBlank { oldBook?.title ?: "رواية محفوظة" },
            cover = cover ?: oldBook?.cover,
            description = description ?: oldBook?.description,
            isCommunity = isCommunity || oldBook?.isCommunity == true,
            contentNotice = contentNotice.ifBlank { oldBook?.contentNotice ?: "general" },
            chapters = bookChapters.sortedWith(compareBy<OfflineNovelChapter> { it.order }.thenBy { it.number ?: Double.MAX_VALUE }),
        )
        existing.removeAll { it.sourceId == sourceId && it.novelPath == novelPath }
        existing.add(0, book)
        writeBooks(existing)
    }

    fun contains(sourceId: String, novelPath: String, chapterPath: String): Boolean =
        chapterFile(sourceId, novelPath, chapterPath).isFile

    @Synchronized
    fun downloadedNovels(): List<OfflineNovelBook> = readBooks().mapNotNull { book ->
        val available = book.chapters.filter { contains(book.sourceId, book.novelPath, it.path) }
        if (available.isEmpty()) null else book.copy(chapters = available)
    }

    @Synchronized
    fun deleteChapter(sourceId: String, novelPath: String, chapterPath: String) {
        chapterFile(sourceId, novelPath, chapterPath).delete()
        val books = readBooks().mapNotNull { book ->
            if (book.sourceId != sourceId || book.novelPath != novelPath) book
            else book.copy(chapters = book.chapters.filterNot { it.path == chapterPath }).takeIf { it.chapters.isNotEmpty() }
        }
        writeBooks(books)
    }

    @Synchronized
    fun migrateFromHistory(history: List<NovelHistoryEntry>) {
        history.forEachIndexed { index, entry ->
            val content = read(entry.sourceId, entry.novelPath, entry.chapterPath) ?: return@forEachIndexed
            val exists = readBooks().any { book ->
                book.sourceId == entry.sourceId && book.novelPath == entry.novelPath && book.chapters.any { it.path == entry.chapterPath }
            }
            if (!exists) {
                save(
                    sourceId = entry.sourceId,
                    novelPath = entry.novelPath,
                    chapterPath = entry.chapterPath,
                    html = content,
                    title = entry.novelTitle,
                    cover = entry.cover,
                    chapterTitle = entry.chapterName,
                    chapterNumber = entry.chapterNumber,
                    order = entry.chapterIndex.takeIf { it >= 0 } ?: index,
                )
            }
        }
    }

    private fun readBooks(): List<OfflineNovelBook> =
        if (!indexFile.isFile) emptyList()
        else runCatching { json.decodeFromString<List<OfflineNovelBook>>(indexFile.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())

    private fun writeBooks(books: List<OfflineNovelBook>) {
        val tmp = File(directory, "index.json.tmp")
        tmp.writeText(json.encodeToString(books), Charsets.UTF_8)
        try {
            atomicReplaceFile(tmp, indexFile)
        } catch (error: Exception) {
            tmp.delete()
            throw error
        }
    }

    private fun chapterFile(sourceId: String, novelPath: String, chapterPath: String): File {
        val key = "$sourceId\u0000$novelPath\u0000$chapterPath"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { byte -> "%02x".format(byte) }
        return File(directory, "$name.html")
    }
}

internal fun atomicReplaceFile(temporary: File, target: File) {
    Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
}
