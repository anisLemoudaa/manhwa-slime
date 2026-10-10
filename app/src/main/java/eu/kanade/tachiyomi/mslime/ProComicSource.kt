package eu.kanade.tachiyomi.mslime

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

internal fun orderProComicPageUrls(candidates: List<String>): List<String> {
    val pageNumber = Regex("""/p(\d+)(?:/|-)""", RegexOption.IGNORE_CASE)
    val numbered = candidates.mapNotNull { url ->
        pageNumber.find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { it to url }
    }.groupBy(
        keySelector = { it.first },
        valueTransform = { it.second },
    )

    val numberedUrls = numbered.toSortedMap().values.mapNotNull { variants ->
        variants.distinct().minByOrNull { url ->
            val cleanPath = url.substringBefore('?').lowercase(Locale.ROOT)
            when {
                cleanPath.contains("-desktop") -> 0
                cleanPath.contains("-mobile") -> 2
                else -> 1
            }
        }
    }
    val unnumberedUrls = candidates.filter { pageNumber.find(it) == null }

    return (numberedUrls + unnumberedUrls).distinct().ifEmpty { candidates.distinct() }
}

internal fun extractProComicPageUrls(html: String): List<String> {
    val normalizedHtml = html
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("&amp;", "&")

    val urlRegex = Regex(
        """(?:https?://app\.procomic\.pro/chapters/[^"'\\\s<>]+|//app\.procomic\.pro/chapters/[^"'\\\s<>]+|(?<![A-Za-z0-9._:/])/chapters/[^"'\\\s<>]+)""",
        RegexOption.IGNORE_CASE,
    )

    val candidates = urlRegex.findAll(normalizedHtml)
        .map { it.value.trimEnd(',', ';', ')', ']', '}') }
        .map { url ->
            when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> "https://app.procomic.pro$url"
                else -> url
            }
        }
        .distinct()
        .toList()

    return orderProComicPageUrls(candidates)
}

class ProComicSource : HttpSource() {

    override val name = "ProComic"
    override val baseUrl = "https://procomic.pro"
    override val lang = "ar"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }
    private data class CachedReaderPageList(val savedAt: Long, val urls: List<String>, val complete: Boolean)
    private val readerPageLists = ConcurrentHashMap<String, CachedReaderPageList>()
    private val readerPageListTtlMs = 30 * 60 * 1000L

    internal fun cacheReaderPageUrls(chapterUrl: String, imageUrls: List<String>) {
        val chapter = runCatching { URI(chapterUrl) }.getOrNull() ?: return
        val host = chapter.host?.lowercase(Locale.ROOT).orEmpty()
        val chapterPath = chapter.path.orEmpty()
        if (
            chapter.scheme != "https" ||
            host != "procomic.pro" ||
            !chapterPath.startsWith("/ar/chapter/")
        ) return

        val chapterId = chapterPath.substringAfterLast('-').takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?: return
        val pagePath = Regex("""/p\d+(?:/|-)""", RegexOption.IGNORE_CASE)
        val urls = imageUrls.asSequence()
            .take(2_000)
            .mapNotNull { rawUrl ->
                val image = runCatching { URI(rawUrl) }.getOrNull() ?: return@mapNotNull null
                if (image.scheme != "https" || image.host?.equals("app.procomic.pro", ignoreCase = true) != true) {
                    return@mapNotNull null
                }
                val segments = image.path.orEmpty().split('/').filter(String::isNotBlank)
                if (segments.getOrNull(0) != "chapters" || segments.getOrNull(2) != chapterId) return@mapNotNull null
                if (!pagePath.containsMatchIn(image.path.orEmpty())) return@mapNotNull null
                rawUrl
            }
            .toList()
        val orderedUrls = orderProComicPageUrls(urls).take(2_000)
        if (orderedUrls.isEmpty()) return

        val now = System.currentTimeMillis()
        readerPageLists.entries.removeIf { now - it.value.savedAt > readerPageListTtlMs }
        val previousUrls = readerPageLists[chapterPath]?.urls.orEmpty()
        val mergedUrls = orderProComicPageUrls(previousUrls + orderedUrls).take(2_000)
        val wasComplete = readerPageLists[chapterPath]?.complete == true
        readerPageLists[chapterPath] = CachedReaderPageList(now, mergedUrls, wasComplete)
    }

    internal fun markReaderPageListComplete(chapterUrl: String): Boolean {
        val chapter = runCatching { URI(chapterUrl) }.getOrNull() ?: return false
        if (
            chapter.scheme != "https" ||
            chapter.host?.equals("procomic.pro", ignoreCase = true) != true ||
            !chapter.path.orEmpty().startsWith("/ar/chapter/")
        ) return false
        val path = chapter.path.orEmpty()
        val cached = readerPageLists[path] ?: return false
        if (cached.urls.isEmpty() || System.currentTimeMillis() - cached.savedAt > readerPageListTtlMs) return false
        readerPageLists[path] = cached.copy(savedAt = System.currentTimeMillis(), complete = true)
        return true
    }

    internal fun cachedReaderPageUrls(chapterPath: String): List<String>? {
        val cachedPages = readerPageLists[chapterPath] ?: return null
        if (System.currentTimeMillis() - cachedPages.savedAt <= readerPageListTtlMs) {
            return cachedPages.urls
        }
        readerPageLists.remove(chapterPath, cachedPages)
        return null
    }

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder().add("Referer", "$baseUrl/")

    // ---------- helpers ----------
    private fun JsonObject.strOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun idOf(url: String) = url.substringAfterLast("-")

    private fun parseDate(s: String?): Long = try {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        s?.take(19)?.let { f.parse(it)?.time } ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun findArray(e: JsonElement?): JsonArray = when (e) {
        is JsonArray -> e
        is JsonObject ->
            e["data"]?.let { findArray(it).takeIf { a -> a.isNotEmpty() } }
                ?: e["chapters"]?.let { findArray(it) }
                ?: e.values.firstNotNullOfOrNull { it as? JsonArray }
                ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }

    // ---------- browse ----------
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/api/content?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/api/content?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val b = "$baseUrl/api/content".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
        if (query.isNotBlank()) b.addQueryParameter("search", query)
        return GET(b.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    private fun parseList(response: Response): MangasPage {
        val root = json.parseToJsonElement(response.use { it.body.string() }).jsonObject
        val mangas = findArray(root["data"]).mapNotNull { (it as? JsonObject)?.let(::toManga) }
        val meta = root["meta"] as? JsonObject
        val page = meta?.int("page") ?: 1
        val pages = meta?.int("pages") ?: 1
        return MangasPage(mangas, page < pages)
    }

    private fun toManga(o: JsonObject): SManga = SManga.create().apply {
        url = "/ar/${o.strOrNull("slug").orEmpty()}-${o.strOrNull("id").orEmpty()}"
        title = o.strOrNull("title").orEmpty()
        thumbnail_url = o.strOrNull("thumbnail")
    }

    // ---------- details ----------
    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl/api/content/${idOf(manga.url)}", headers)

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun mangaDetailsParse(response: Response): SManga {
        val root = json.parseToJsonElement(response.use { it.body.string() }).jsonObject
        val o = (root["data"] as? JsonObject) ?: root
        return SManga.create().apply {
            title = o.strOrNull("title").orEmpty()
            thumbnail_url = o.strOrNull("thumbnail")
            description = o.strOrNull("description")
            val meta = o["metadata"] as? JsonObject
            author = meta?.strOrNull("author")
            artist = meta?.strOrNull("artist")
            genre = (meta?.get("genres") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.joinToString(", ")
            val p = o.strOrNull("progress").orEmpty()
            status = when {
                p.contains("مكتمل") || p.equals("completed", true) -> SManga.COMPLETED
                p.contains("متوقف") || p.equals("hiatus", true) -> SManga.ON_HIATUS
                else -> SManga.ONGOING
            }
            initialized = true
        }
    }

    // ---------- chapters ----------
    private suspend fun loadChapterList(manga: SManga): List<SChapter> {
        val id = idOf(manga.url)
        val slug = manga.url.removePrefix("/ar/").substringBeforeLast("-")
        val out = mutableListOf<SChapter>()
        var page = 1
        while (page <= 50) {
            val resp = client.newCall(GET("$baseUrl/api/chapters?contentId=$id&page=$page", headers)).awaitSuccess()
            val root = json.parseToJsonElement(resp.use { it.body.string() })
            val arr = findArray(root)
            arr.forEach { e -> (e as? JsonObject)?.let { toChapter(it, slug) }?.let(out::add) }
            val hasMore = ((root as? JsonObject)?.get("hasMore") as? JsonPrimitive)
                ?.contentOrNull?.equals("true", ignoreCase = true) ?: false
            if (!hasMore || arr.isEmpty()) break
            page++
        }
        return out.distinctBy { it.url }.sortedByDescending { it.chapter_number }
    }

    private fun toChapter(o: JsonObject, slug: String): SChapter? {
        val id = o.strOrNull("id") ?: return null
        val numRaw = listOf("chapter_number", "number", "chapter", "chapterNumber", "order", "index")
            .firstNotNullOfOrNull { o.strOrNull(it) }
        val num = numRaw?.toFloatOrNull()
        val numText = when {
            num == null -> numRaw ?: id
            num % 1f == 0f -> num.toInt().toString()
            else -> num.toString()
        }
        return SChapter.create().apply {
            url = "/ar/chapter/$slug-$numText-$id"
            name = o.strOrNull("title")?.takeIf { it.isNotBlank() } ?: "الفصل $numText"
            chapter_number = num ?: -1f
            date_upload =
                parseDate(o.strOrNull("created_at") ?: o.strOrNull("published_at") ?: o.strOrNull("updated_at"))
        }
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = if (fetchDetails) {
            val response = client.newCall(mangaDetailsRequest(manga)).awaitSuccess()
            mangaDetailsParse(response).apply { initialized = true }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            loadChapterList(manga)
        } else {
            chapters
        }

        return SMangaUpdate(details, updatedChapters)
    }

    override fun chapterListParse(response: Response): List<SChapter> =
        throw UnsupportedOperationException("handled in getMangaUpdate")

    // ---------- pages ----------
    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val chapterPath = response.request.url.encodedPath
        val cachedPages = readerPageLists[chapterPath]
        if (cachedPages != null && System.currentTimeMillis() - cachedPages.savedAt <= readerPageListTtlMs) {
            response.close()
            if (!cachedPages.complete) throw ProComicChapterNotPreparedException()
            return cachedPages.urls.mapIndexed { index, url -> Page(index, imageUrl = url) }
        }
        if (cachedPages != null) readerPageLists.remove(chapterPath, cachedPages)
        response.close()
        throw ProComicChapterNotPreparedException()
    }


    override fun imageUrlParse(response: Response): String = ""
}

class ProComicChapterNotPreparedException : Exception()
