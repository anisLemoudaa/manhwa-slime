#!/bin/bash
cd "$(dirname "$0")" || exit 1
K=app/src/main/java/eu/kanade/tachiyomi/mslime
SM=app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt
HS=source-api/src/main/kotlin/eu/kanade/tachiyomi/source/online/HttpSource.kt
[ -f "$SM" ] && [ -f "$HS" ] || { echo "[!!] files missing"; exit 1; }
[ -f "$K/ProComicSource.kt" ] && { echo "[!!] ProComicSource.kt already exists"; exit 1; }
grep -q "suspend fun getChapterList" "$HS" || { echo "[!!] getChapterList not found, send: grep -n 'fun ' $HS"; exit 1; }
grep -q "ProComicSource" "$SM" && { echo "[!!] already registered"; exit 1; }

cat > "$K/ProComicSource.kt" <<'KT'
package eu.kanade.tachiyomi.mslime

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
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

    // ---------- chapters (Arabic only) ----------
    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val id = idOf(manga.url)
        val slug = manga.url.removePrefix("/ar/").substringBeforeLast("-")
        val out = mutableListOf<SChapter>()
        var page = 1
        while (page <= 50) {
            val resp = client.newCall(GET("$baseUrl/api/chapters?content_id=$id&page=$page", headers)).awaitSuccess()
            val root = json.parseToJsonElement(resp.use { it.body.string() })
            val arr = findArray(root)
            arr.forEach { e -> (e as? JsonObject)?.let { toChapter(it, slug) }?.let(out::add) }
            val pages = ((root as? JsonObject)?.get("meta") as? JsonObject)?.int("pages") ?: 1
            if (page >= pages || arr.isEmpty()) break
            page++
        }
        return out.distinctBy { it.url }.sortedByDescending { it.chapter_number }
    }

    private fun toChapter(o: JsonObject, slug: String): SChapter? {
        val id = o.strOrNull("id") ?: return null
        val language = o.strOrNull("language") ?: o.strOrNull("lang") ?: o.strOrNull("locale")
        if (language != null) {
            val l = language.lowercase()
            if (!(l.startsWith("ar") || l.contains("عرب"))) return null
        }
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
            date_upload = parseDate(o.strOrNull("created_at") ?: o.strOrNull("published_at") ?: o.strOrNull("updated_at"))
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> =
        throw UnsupportedOperationException("handled in getChapterList")

    // ---------- pages ----------
    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val html = response.use { it.body.string() }
        val all = Regex("""https://app\.procomic\.pro/chapters/[^"\\\s<>]+""")
            .findAll(html).map { it.value }.distinct().toList()
        val urls = all.filter { it.contains("-desktop") }.ifEmpty { all }
        val order = Regex("""/p(\d+)-""")
        return urls
            .sortedBy { order.find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
            .mapIndexed { i, u -> Page(i, imageUrl = u) }
    }

    override fun imageUrlParse(response: Response): String = ""
}
KT

python3 - <<'PY'
p = "app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt"
s = open(p, encoding="utf-8").read()
old = "mapOf(LocalSource.ID to localSource),"
assert s.count(old) == 1, "pattern not found exactly once"
new = ("mapOf<Long, Source>(\n"
       "                    LocalSource.ID to localSource,\n"
       "                    eu.kanade.tachiyomi.mslime.ProComicSource().let { it.id to it },\n"
       "                ),")
open(p, "w", encoding="utf-8").write(s.replace(old, new))
print("[ok] registered in AndroidSourceManager")
PY

echo "--- chapters API sample (check field names) ---"
curl -s 'https://procomic.pro/api/chapters?content_id=31' | python3 -c 'import sys,json;d=json.load(sys.stdin);a=d if isinstance(d,list) else next((v for v in d.values() if isinstance(v,list)),[]);print(type(d).__name__,list(d.keys()) if isinstance(d,dict) else len(d));print(len(a));print(a[0] if a else None)'
echo "[ok] done. git diff --stat:"; git diff --stat
