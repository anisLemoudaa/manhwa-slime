package eu.kanade.tachiyomi.mslime

import android.content.Context
import eu.kanade.tachiyomi.novel.hasAdultContentMarker
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Data access for user-published Community Novels. Uses the existing Supabase session and RLS. */
object CommunityNovelApi {
    data class Novel(
        val id: String,
        val authorId: String,
        val authorName: String = "",
        val contentNotice: String = "general",
        val title: String,
        val description: String,
        val coverPath: String?,
        val category: String,
        val language: String,
        val completionStatus: String,
        val status: String,
        val chapterCount: Int = 0,
        val viewCount: Long = 0,
        val likeCount: Int = 0,
        val liked: Boolean = false,
        val inLibrary: Boolean = false,
        val updatedAt: String = "",
        val tags: Set<String> = emptySet(),
        val averageRating: Double = 0.0,
        val ratingCount: Int = 0,
        val myRating: Int = 0,
    )

    data class RatingSummary(val average: Double = 0.0, val count: Int = 0, val myRating: Int = 0)
    data class LikeSummary(val count: Int = 0, val liked: Boolean = false)

    data class Chapter(
        val id: String,
        val novelId: String,
        val number: Int,
        val title: String,
        val content: String,
        val status: String,
    )

    data class Comment(val id: String, val userId: String, val content: String, val createdAt: String)

    private fun token(ctx: Context): String? = MslSupabase.token(ctx)
    private fun uid(ctx: Context): String = MslSupabase.uid(ctx)

    private fun get(ctx: Context, path: String): JSONArray? {
        val tok = token(ctx) ?: return null
        val r = MslSupabase.call("GET", path, null, tok)
        return if (r.first in 200..299) JSONArray(r.second) else null
    }

    fun list(ctx: Context, filter: String = "latest", query: String = "", category: String? = null): List<Novel> {
        val clauses = mutableListOf("status=eq.published")
        if (query.isNotBlank()) clauses += "title=ilike.*${query.trim().replace("*", "")}*"
        if (!category.isNullOrBlank()) clauses += "primary_category=eq.$category"
        if (filter == "liked") {
            val likedRows = get(ctx, "/rest/v1/community_novel_likes?select=novel_id&user_id=eq.${uid(ctx)}&limit=100")
            val ids = likedRows?.let { rows -> List(rows.length()) { i -> rows.getJSONObject(i).optString("novel_id") }.filter(String::isNotBlank) } ?: emptyList()
            if (ids.isEmpty()) return emptyList()
            clauses += "id=in.(${ids.joinToString(",")})"
        }
        val order = when (filter) {
            "popular" -> "view_count.desc,updated_at.desc"
            "liked" -> "updated_at.desc"
            else -> "updated_at.desc"
        }
        val rows = get(ctx, "/rest/v1/community_novels?select=*&${clauses.joinToString("&")}&order=$order&limit=40") ?: return emptyList()
        return parseNovels(rows).filterNot { MslAdultContentFilter.isEnabled(ctx) && isAdult(it) }
    }

    fun mine(ctx: Context): List<Novel> {
        val id = uid(ctx).takeIf { it.isNotBlank() } ?: return emptyList()
        val rows = get(ctx, "/rest/v1/community_novels?select=*&author_id=eq.$id&order=updated_at.desc&limit=50") ?: return emptyList()
        return parseNovels(rows).filterNot { MslAdultContentFilter.isEnabled(ctx) && isAdult(it) }
    }

    fun details(ctx: Context, id: String): Novel? {
        val rows = get(ctx, "/rest/v1/community_novels?select=*&id=eq.$id&limit=1") ?: return null
        val novel = parseNovels(rows).firstOrNull()?.takeUnless { MslAdultContentFilter.isEnabled(ctx) && isAdult(it) } ?: return null
        val saved = get(ctx, "/rest/v1/community_novel_library?select=novel_id&user_id=eq.${uid(ctx)}&novel_id=eq.$id&limit=1")?.length() == 1
        val likes = likeSummary(ctx, id)
        val rating = ratingSummary(ctx, id)
        return novel.copy(
            inLibrary = saved,
            liked = likes.liked,
            likeCount = likes.count,
            averageRating = rating.average,
            ratingCount = rating.count,
            myRating = rating.myRating,
        )
    }

    fun likeSummary(ctx: Context, novelId: String): LikeSummary {
        return runCatching {
            val tok = token(ctx) ?: return LikeSummary()
            val response = MslSupabase.call(
                "POST",
                "/rest/v1/rpc/community_novel_like_stats",
                JSONObject().put("p_novel_id", novelId).toString(),
                tok,
            )
            if (response.first !in 200..299) return LikeSummary()
            val row = JSONArray(response.second).optJSONObject(0) ?: JSONObject(response.second)
            LikeSummary(row.optInt("like_count", 0), row.optBoolean("liked", false))
        }.getOrDefault(LikeSummary())
    }

    fun ratingSummary(ctx: Context, novelId: String): RatingSummary {
        return runCatching {
            val tok = token(ctx) ?: return RatingSummary()
            val response = MslSupabase.call(
                "POST",
                "/rest/v1/rpc/community_novel_rating_stats",
                JSONObject().put("p_novel_id", novelId).toString(),
                tok,
            )
            if (response.first !in 200..299) return RatingSummary()
            val row = JSONArray(response.second).optJSONObject(0) ?: JSONObject(response.second)
            RatingSummary(
                average = row.optDouble("average_rating", 0.0),
                count = row.optInt("rating_count", 0),
                myRating = row.optInt("my_rating", 0),
            )
        }.getOrDefault(RatingSummary())
    }

    fun setRating(ctx: Context, novelId: String, rating: Int): Boolean {
        if (rating !in 1..10) return false
        return runCatching {
            val tok = token(ctx) ?: return false
            val response = MslSupabase.call(
                "POST",
                "/rest/v1/rpc/community_set_novel_rating",
                JSONObject().put("p_novel_id", novelId).put("p_rating", rating).toString(),
                tok,
            )
            response.first in 200..299
        }.getOrDefault(false)
    }

    fun incrementView(ctx: Context, novelId: String): Boolean {
        val tok = token(ctx) ?: return false
        val body = JSONObject().put("p_novel_id", novelId).toString()
        return MslSupabase.call("POST", "/rest/v1/rpc/community_increment_view", body, tok).first in 200..299
    }

    fun chapters(ctx: Context, novelId: String, includeDrafts: Boolean = false): List<Chapter> {
        val rows = if (includeDrafts) {
            get(ctx, "/rest/v1/community_novel_chapters?select=*&novel_id=eq.$novelId&order=chapter_number.asc&limit=200")
        } else {
            runCatching {
                val tok = token(ctx) ?: return emptyList()
                val response = MslSupabase.call(
                    "POST",
                    "/rest/v1/rpc/list_community_chapter_metadata",
                    JSONObject().put("p_novel_id", novelId).toString(),
                    tok,
                )
                if (response.first in 200..299) JSONArray(response.second) else null
            }.getOrNull()
        } ?: return emptyList()
        return List(rows.length()) { i ->
            val o = rows.getJSONObject(i)
            Chapter(o.getString("id"), o.getString("novel_id"), o.optInt("chapter_number"), o.optString("title"), o.optString("content"), o.optString("status"))
        }.filterNot { MslAdultContentFilter.isEnabled(ctx) && hasAdultContentMarker(it.title, it.content) }
    }

    /** Fetches a single chapter only after the database confirms its entitlement. */
    fun chapterContent(ctx: Context, novelId: String, chapterId: String): String? = runCatching {
        val tok = token(ctx) ?: return null
        val response = MslSupabase.call(
            "POST",
            "/rest/v1/rpc/read_community_chapter",
            JSONObject().put("p_novel_id", novelId).put("p_chapter_id", chapterId).toString(),
            tok,
        )
        if (response.first !in 200..299) return null
        val result = runCatching { JSONArray("[${response.second}]").optString(0) }
            .getOrElse { response.second.trim().trim('"') }
        result.takeIf(String::isNotBlank)
    }.getOrNull()

    fun comments(ctx: Context, novelId: String, chapterId: String?): List<Comment> = runCatching {
        val chapterFilter = if (chapterId == null) "chapter_id=is.null" else "chapter_id=eq.$chapterId"
        val rows = get(
            ctx,
            "/rest/v1/community_comments?select=id,user_id,content,created_at&novel_id=eq.$novelId&$chapterFilter&status=eq.visible&order=created_at.asc&limit=100",
        ) ?: return emptyList()
        List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            Comment(row.optString("id"), row.optString("user_id"), row.optString("content"), row.optString("created_at"))
        }
    }.getOrDefault(emptyList())

    fun addComment(ctx: Context, novelId: String, chapterId: String?, content: String): Boolean {
        val bodyText = content.trim()
        if (bodyText.isEmpty() || bodyText.length > 1000) return false
        val tok = token(ctx) ?: return false
        val body = JSONObject()
            .put("p_novel_id", novelId)
            .put("p_chapter_id", chapterId)
            .put("p_content", bodyText)
            .put("p_request_id", UUID.randomUUID().toString())
        return MslSupabase.call("POST", "/rest/v1/rpc/add_community_comment", body.toString(), tok).first in 200..299
    }

    fun nextChapterNumber(ctx: Context, novelId: String): Int {
        val rows = runCatching {
            get(ctx, "/rest/v1/community_novel_chapters?select=chapter_number&novel_id=eq.$novelId&order=chapter_number.desc&limit=1")
        }.getOrNull()
        return ((rows?.optJSONObject(0)?.optInt("chapter_number") ?: 0) + 1).coerceAtLeast(1)
    }

    fun saveNovel(ctx: Context, novel: Novel?, title: String, authorName: String, description: String, category: String, language: String, completion: String, tags: Set<String> = setOf(category), coverData: String? = novel?.coverPath): Result<String> = runCatching {
        val tok = token(ctx) ?: error("يلزم تسجيل الدخول لإنشاء رواية")
        val body = JSONObject().put("title", title.trim()).put("author_name", authorName.trim()).put("description", description.trim()).put("primary_category", category).put("tags", JSONArray(tags.toList())).put("language", language).put("completion_status", completion)
        if (!coverData.isNullOrBlank()) body.put("cover_path", coverData)
        if (novel == null) body.put("author_id", uid(ctx)).put("status", "draft")
        val path = if (novel == null) "/rest/v1/community_novels" else "/rest/v1/community_novels?id=eq.${novel.id}"
        val r = MslSupabase.call(if (novel == null) "POST" else "PATCH", path, body.toString(), tok, "return=representation")
        if (r.first !in 200..299) error("فشل حفظ الرواية (${r.first})")
        JSONObject(JSONArray(r.second).optJSONObject(0)?.toString() ?: r.second).getString("id")
    }

    fun setNovelStatus(ctx: Context, id: String, status: String): Boolean {
        val tok = token(ctx) ?: return false
        val body = JSONObject().put("status", status)
        return MslSupabase.call("PATCH", "/rest/v1/community_novels?id=eq.$id", body.toString(), tok).first in 200..299
    }

    fun saveChapter(ctx: Context, novelId: String, chapter: Chapter?, number: Int, title: String, content: String, publish: Boolean): Result<String> = runCatching {
        val tok = token(ctx) ?: error("يلزم تسجيل الدخول لحفظ الفصل")
        val body = JSONObject().put("novel_id", novelId).put("chapter_number", number).put("title", title.trim()).put("content", content).put("status", if (publish) "published" else "draft")
        val path = if (chapter == null) "/rest/v1/community_novel_chapters" else "/rest/v1/community_novel_chapters?id=eq.${chapter.id}"
        val r = MslSupabase.call(if (chapter == null) "POST" else "PATCH", path, body.toString(), tok, "return=representation")
        if (r.first !in 200..299) {
            val duplicateChapter = r.first == 409 || r.second.contains("23505") || r.second.contains("community_novel_chapters_novel_id_chapter_number_key")
            if (duplicateChapter) error("يوجد فصل آخر بهذا الرقم. غيّر رقم الفصل أو افتح الفصل الموجود لتعديله.")
            error("فشل حفظ الفصل (${r.first})")
        }
        JSONObject(JSONArray(r.second).optJSONObject(0)?.toString() ?: r.second).getString("id")
    }

    fun toggleLibrary(ctx: Context, novelId: String, add: Boolean): Boolean {
        val tok = token(ctx) ?: return false
        return if (add) {
            val body = JSONObject().put("user_id", uid(ctx)).put("novel_id", novelId)
            MslSupabase.call("POST", "/rest/v1/community_novel_library", body.toString(), tok, "resolution=ignore-duplicates,return=minimal").first in 200..299
        } else MslSupabase.call("DELETE", "/rest/v1/community_novel_library?user_id=eq.${uid(ctx)}&novel_id=eq.$novelId", null, tok).first in 200..299
    }

    fun toggleLike(ctx: Context, novelId: String, like: Boolean): Boolean {
        return runCatching {
            val tok = token(ctx) ?: return false
            if (like) {
                MslSupabase.call("POST", "/rest/v1/community_novel_likes", JSONObject().put("user_id", uid(ctx)).put("novel_id", novelId).toString(), tok, "resolution=ignore-duplicates,return=minimal").first in 200..299
            } else MslSupabase.call("DELETE", "/rest/v1/community_novel_likes?user_id=eq.${uid(ctx)}&novel_id=eq.$novelId", null, tok).first in 200..299
        }.getOrDefault(false)
    }

    fun saveProgress(ctx: Context, novelId: String, chapterId: String, position: Int): Boolean {
        val tok = token(ctx) ?: return false
        val body = JSONObject().put("user_id", uid(ctx)).put("novel_id", novelId).put("chapter_id", chapterId).put("reading_position", position)
        return MslSupabase.call("POST", "/rest/v1/community_reading_progress?on_conflict=user_id,novel_id", body.toString(), tok, "resolution=merge-duplicates,return=minimal").first in 200..299
    }

    fun toggleAuthorFollow(ctx: Context, authorId: String, follow: Boolean): Boolean {
        val tok = token(ctx) ?: return false
        return if (follow) {
            MslSupabase.call("POST", "/rest/v1/community_author_follows", JSONObject().put("follower_id", uid(ctx)).put("author_id", authorId).toString(), tok, "resolution=ignore-duplicates,return=minimal").first in 200..299
        } else MslSupabase.call("DELETE", "/rest/v1/community_author_follows?follower_id=eq.${uid(ctx)}&author_id=eq.$authorId", null, tok).first in 200..299
    }

    fun reportNovel(ctx: Context, novelId: String, reason: String = "other", description: String = ""): Result<Unit> = runCatching {
        val tok = token(ctx) ?: error("تعذّر إنشاء جلسة للإبلاغ؛ سجّل الدخول ثم أعد المحاولة.")
        val safeReason = reason.takeIf { it in setOf("copyright", "abuse", "spam", "inappropriate", "other") } ?: "other"
        val body = JSONObject().put("reporter_id", uid(ctx)).put("novel_id", novelId).put("reason", safeReason).put("description", description.take(1000))
        val response = MslSupabase.call("POST", "/rest/v1/community_reports", body.toString(), tok, "return=minimal")
        if (response.first !in 200..299) {
            val message = when (response.first) {
                401, 403 -> "لم يسمح الخادم بإرسال البلاغ. تأكد من تسجيل الدخول ثم أعد المحاولة."
                else -> "تعذّر إرسال البلاغ (${response.first}). تحقق من الاتصال ثم أعد المحاولة."
            }
            error(message)
        }
    }

    private fun isAdult(novel: Novel): Boolean =
        novel.contentNotice.lowercase() in setOf("mature", "adult", "nsfw", "18+", "18plus") ||
            hasAdultContentMarker(novel.title, novel.description, novel.category, novel.tags.joinToString(" "))

    private fun parseNovels(rows: JSONArray): List<Novel> = List(rows.length()) { i ->
        val o = rows.getJSONObject(i)
        Novel(
            id = o.getString("id"),
            authorId = o.optString("author_id"),
            authorName = o.optString("author_name"),
            contentNotice = o.optString("content_notice", "general"),
            title = o.optString("title"),
            description = o.optString("description"),
            coverPath = o.optString("cover_path").takeIf(String::isNotBlank),
            category = o.optString("primary_category"),
            language = o.optString("language"),
            completionStatus = o.optString("completion_status"),
            status = o.optString("status"),
            chapterCount = o.optInt("chapter_count"),
            viewCount = o.optLong("view_count"),
            likeCount = o.optInt("like_count"),
            liked = o.optBoolean("liked"),
            inLibrary = o.optBoolean("in_library"),
            updatedAt = o.optString("updated_at"),
            tags = buildSet { o.optJSONArray("tags")?.let { a -> for (i in 0 until a.length()) add(a.optString(i)) } },
        )
    }
}
