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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ProComicSource : HttpSource() {

    override val name = "ProComic"
    override val baseUrl = "https://procomic.pro"
    override val lang = "ar"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

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
        val html = response.use { it.body.string() }

        // توحيد الروابط التي قد تكون مخزنة داخل JSON أو JavaScript.
        val normalizedHtml = html
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("&amp;", "&")

        // استخراج جميع روابط صور الفصول، وليس نسخة desktop فقط.
        val urlRegex = Regex(
            """https?://app\.procomic\.pro/chapters/[^"'\\\s<>]+""",
            RegexOption.IGNORE_CASE,
        )

        val candidates = urlRegex.findAll(normalizedHtml)
            .map { it.value.trimEnd(',', ';', ')', ']', '}') }
            .filter { it.contains("/chapters/", ignoreCase = true) }
            .distinct()
            .toList()

        // استخراج رقم الصفحة من اسم الصورة، مثل p1- وp25-.
        val pageNumber = Regex(
            """/p(\d+)-""",
            RegexOption.IGNORE_CASE,
        )

        // جمع نسخ الصور حسب رقم الصفحة لتجنب تكرار الصفحة نفسها.
        val numbered = candidates.mapNotNull { url ->
            val number = pageNumber.find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            number?.let { it to url }
        }.groupBy(
            keySelector = { it.first },
            valueTransform = { it.second },
        )

        // تفضيل نسخة desktop عند وجودها، مع الاحتفاظ بالبدائل.
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

        // عدم إسقاط الصور التي لا يتضمن اسمها رقم صفحة معروفًا.
        val unnumberedUrls = candidates.filter {
            pageNumber.find(it) == null
        }

        val urls = (numberedUrls + unnumberedUrls)
            .distinct()
            .ifEmpty { candidates }

        // تسجيل معلومات مفيدة إذا بقي عدد الصفحات المستخرجة صغيرًا.
        if (urls.size <= 3) {
            android.util.Log.w(
                "ProComicSource",
                "Parsed ${urls.size} unique page image URLs " +
                    "from ${candidates.size} matching URLs. " +
                    "If the chapter has more pages, the remaining URLs " +
                    "may be loaded through the site's API or JavaScript.",
            )
        }

        return urls.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }


    override fun imageUrlParse(response: Response): String = ""
}
