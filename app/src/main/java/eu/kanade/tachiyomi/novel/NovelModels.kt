package eu.kanade.tachiyomi.novel

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class NovelRepositoryEntry(
    val id: String,
    val name: String,
    val site: String? = null,
    val lang: String? = null,
    val version: String? = null,
    val url: String,
    val iconUrl: String? = null,
    @kotlinx.serialization.Transient val repositoryUrl: String? = null,
)

internal fun String?.toNovelLanguageCode(): String? {
    val normalized = this.orEmpty()
        .replace(Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]"), "")
        .trim()
        .lowercase()
        .replace('_', '-')
        .substringBefore('(')
        .trim()

    return when {
        normalized in setOf("ar", "ara", "arabic", "العربية", "عربية", "عربي", "اللغة العربية") || normalized.startsWith("ar-") -> "ar"
        normalized in setOf("en", "eng", "english", "الإنجليزية", "الانجليزية", "إنجليزية", "انجليزية", "إنجليزي", "انجليزي") || normalized.startsWith("en-") -> "en"
        else -> null
    }
}

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
    val rating: JsonElement? = null,
    val genres: JsonElement? = null,
    val tags: JsonElement? = null,
    val contentRating: JsonElement? = null,
    val nsfw: JsonElement? = null,
    val adult: JsonElement? = null,
)

internal fun NovelItem.ratingValue(): Double? = runCatching { rating?.jsonPrimitive?.content?.toDoubleOrNull() }.getOrNull()

internal fun hasAdultContentMarker(vararg values: String?): Boolean {
    val text = values.filterNotNull().joinToString(" ").lowercase()
    val markers = listOf(
        "18+", "18 +", "r18", "nsfw", "adult only", "adult content", "mature", "explicit",
        "erotica", "erotic", "hentai", "smut", "porn", "sexual content", "ecchi", "yaoi 18", "yuri 18",
        "للكبار", "للكبار فقط", "محتوى للبالغين", "محتوى جنسي", "إباحي", "اباحي", "جنس صريح", "+١٨", "١٨+", "١٨ +",
    )
    return markers.any { it in text }
}

internal fun NovelItem.isAdultContent(): Boolean {
    val explicitFlag = listOf(nsfw, adult, contentRating).any { value ->
        value?.toString()?.lowercase()?.let { it == "true" || it.contains("nsfw") || it.contains("adult") || it.contains("18+") || it.contains("18 +") || it.contains("explicit") } == true
    }
    return explicitFlag || hasAdultContentMarker(name, path, sourceName, genres?.toString(), tags?.toString(), contentRating?.toString())
}

internal fun NovelDetails.isAdultContent(): Boolean =
    hasAdultContentMarker(name, path, genres, summary, author)

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
    val languageCode: String? get() = installed.metadata.lang.toNovelLanguageCode()
}
