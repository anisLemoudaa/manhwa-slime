package eu.kanade.tachiyomi.novel

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

class NovelPluginStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "msl_novel_plugins").apply { mkdirs() }
    private val reposFile = File(dir, "repositories.json")
    private val installedFile = File(dir, "installed.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun repositories(): List<String> = read(reposFile, emptyList())
    fun setRepositories(values: List<String>) = write(reposFile, values.distinct())

    fun installed(): List<NovelInstalledPlugin> = read(installedFile, emptyList())
    fun setInstalled(values: List<NovelInstalledPlugin>) = write(installedFile, values)

    fun pluginFile(url: String): File = File(dir, "${sha256(url)}.js")

    fun pluginFile(item: NovelInstalledPlugin): File = File(dir, item.jsFile)

    fun savePlugin(url: String, js: String): File {
        val f = pluginFile(url)
        f.writeText(js)
        return f
    }

    fun deletePlugin(url: String) {
        pluginFile(url).delete()
    }

    private inline fun <reified T> read(file: File, fallback: T): T {
        if (!file.exists()) return fallback
        return runCatching { json.decodeFromString<T>(file.readText()) }.getOrDefault(fallback)
    }

    private inline fun <reified T> write(file: File, value: T) {
        file.writeText(json.encodeToString(value))
    }

    companion object {
        fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
