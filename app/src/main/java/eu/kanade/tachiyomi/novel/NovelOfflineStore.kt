package eu.kanade.tachiyomi.novel

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Small private app-storage cache for explicitly purchased offline novel chapters. */
class NovelOfflineStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "msl_novel_downloads").apply { mkdirs() }

    fun read(sourceId: String, novelPath: String, chapterPath: String): String? {
        val target = chapterFile(sourceId, novelPath, chapterPath)
        return target.takeIf(File::isFile)?.readText(Charsets.UTF_8)
    }

    fun save(sourceId: String, novelPath: String, chapterPath: String, html: String) {
        val target = chapterFile(sourceId, novelPath, chapterPath)
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(html, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temporary.delete()
            error("تعذّر استبدال نسخة الفصل المحلية")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            error("تعذّر حفظ الفصل على الجهاز")
        }
    }

    fun contains(sourceId: String, novelPath: String, chapterPath: String): Boolean =
        chapterFile(sourceId, novelPath, chapterPath).isFile

    private fun chapterFile(sourceId: String, novelPath: String, chapterPath: String): File {
        val key = "$sourceId\u0000$novelPath\u0000$chapterPath"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { byte -> "%02x".format(byte) }
        return File(directory, "$name.html")
    }
}
