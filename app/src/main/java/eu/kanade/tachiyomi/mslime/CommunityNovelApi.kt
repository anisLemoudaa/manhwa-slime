package eu.kanade.tachiyomi.mslime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Data access for user-published Community Novels. Uses the existing Supabase session and RLS. */
object CommunityNovelApi {
    data class Novel(
        val id: String,
        val authorId: String,
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
    )

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
        val order = when (filter) {
            "popular" -> "view_count.desc,updated_at.desc"
            "liked" -> "updated_at.desc"
            else -> "updated_at.desc"
        }
        val rows = get(ctx, "/rest/v1/community_novels?select=*&${clauses.joinToString("&")}&order=$order&limit=40") ?: return emptyList()
        return parseNovels(rows)
    }

    fun mine(ctx: Context): List<Novel> {
        val id = uid(ctx).takeIf { it.isNotBlank() } ?: return emptyList()
        val rows = get(ctx, "/rest/v1/community_novels?select=*&author_id=eq.$id&order=updated_at.desc&limit=50") ?: return emptyList()
        return parseNovels(rows)
    }

    fun details(ctx: Context, id: String): Novel? {
        val rows = get(ctx, "/rest/v1/community_novels?select=*&id=eq.$id&limit=1") ?: return null
        val novel = parseNovels(rows).firstOrNull() ?: return null
        val saved = get(ctx, "/rest/v1/community_novel_library?select=novel_id&user_id=eq.${uid(ctx)}&novel_id=eq.$id&limit=1")?.length() == 1
        val liked = get(ctx, "/rest/v1/community_novel_likes?select=novel_id&user_id=eq.${uid(ctx)}&novel_id=eq.$id&limit=1")?.length() == 1
        return novel.copy(inLibrary = saved, liked = liked)
    }

    fun chapters(ctx: Context, novelId: String, includeDrafts: Boolean = false): List<Chapter> {
        val extra = if (includeDrafts) "" else "&status=eq.published"
        val rows = get(ctx, "/rest/v1/community_novel_chapters?select=*&novel_id=eq.$novelId$extra&order=chapter_number.asc&limit=200") ?: return emptyList()
        return List(rows.length()) { i ->
            val o = rows.getJSONObject(i)
            Chapter(o.getString("id"), o.getString("novel_id"), o.optInt("chapter_number"), o.optString("title"), o.optString("content"), o.optString("status"))
        }
    }

    fun saveNovel(ctx: Context, novel: Novel?, title: String, description: String, category: String, language: String, completion: String, tags: Set<String> = setOf(category), coverData: String? = novel?.coverPath): Result<String> = runCatching {
        val tok = token(ctx) ?: error("يلزم تسجيل الدخول لإنشاء رواية")
        val body = JSONObject().put("title", title.trim()).put("description", description.trim()).put("primary_category", category).put("tags", JSONArray(tags.toList())).put("language", language).put("completion_status", completion)
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
        if (r.first !in 200..299) error("فشل حفظ الفصل (${r.first})")
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
        val tok = token(ctx) ?: return false
        return if (like) {
            MslSupabase.call("POST", "/rest/v1/community_novel_likes", JSONObject().put("user_id", uid(ctx)).put("novel_id", novelId).toString(), tok, "resolution=ignore-duplicates,return=minimal").first in 200..299
        } else MslSupabase.call("DELETE", "/rest/v1/community_novel_likes?user_id=eq.${uid(ctx)}&novel_id=eq.$novelId", null, tok).first in 200..299
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

    fun reportNovel(ctx: Context, novelId: String, reason: String = "other", description: String = ""): Boolean {
        val tok = token(ctx) ?: return false
        val body = JSONObject().put("reporter_id", uid(ctx)).put("novel_id", novelId).put("reason", reason).put("description", description.take(1000))
        return MslSupabase.call("POST", "/rest/v1/community_reports", body.toString(), tok, "return=minimal").first in 200..299
    }

    private fun parseNovels(rows: JSONArray): List<Novel> = List(rows.length()) { i ->
        val o = rows.getJSONObject(i)
        Novel(o.getString("id"), o.optString("author_id"), o.optString("title"), o.optString("description"), o.optString("cover_path").takeIf { it.isNotBlank() }, o.optString("primary_category"), o.optString("language"), o.optString("completion_status"), o.optString("status"), o.optInt("chapter_count"), o.optLong("view_count"), o.optInt("like_count"), o.optBoolean("liked"), o.optBoolean("in_library"), o.optString("updated_at"), buildSet { o.optJSONArray("tags")?.let { a -> for (i in 0 until a.length()) add(a.optString(i)) } })
    }
}
