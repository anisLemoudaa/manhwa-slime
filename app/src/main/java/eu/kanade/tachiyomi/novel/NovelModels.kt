package eu.kanade.tachiyomi.novel

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class NovelRepositoryEntry(
    val id: String,
    val name: String,
    val site: String? = null,
    val lang: String? = null,
    val version: String? = null,
    val url: String,
    val iconUrl: String? = null,
)

@Serializable
data class NovelPluginInfo(
    val id: String,
    val name: String,
    val version: String? = null,
    val site: String? = null,
    val lang: String? = null,
    val iconUrl: String? = null,
    val filters: JsonObject? = null,
    val pluginSettings: JsonObject? = null,
)

@Serializable
data class NovelItem(
    val name: String,
    val path: String,
    val cover: String? = null,
    val sourceId: String? = null,
    val sourceName: String? = null,
)

@Serializable
data class NovelChapter(
    val name: String,
    val path: String,
    val releaseTime: String? = null,
    val chapterNumber: Double? = null,
    val page: String? = null,
)

@Serializable
data class NovelDetails(
    val path: String,
    val name: String? = null,
    val cover: String? = null,
    val genres: String? = null,
    val summary: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val chapters: List<NovelChapter>? = null,
    val totalPages: Int = 1,
)

@Serializable
data class NovelInstalledPlugin(
    val pluginUrl: String,
    val metadata: NovelPluginInfo,
    val repositoryUrl: String? = null,
    val jsFile: String,
)

data class NovelSourceRuntime(
    val installed: NovelInstalledPlugin,
) {
    val id: String get() = installed.metadata.id
    val name: String get() = installed.metadata.name
    val lang: String get() = installed.metadata.lang.orEmpty().ifBlank { "متعدد" }
    val site: String get() = installed.metadata.site.orEmpty()
    val iconUrl: String? get() = installed.metadata.iconUrl
}
