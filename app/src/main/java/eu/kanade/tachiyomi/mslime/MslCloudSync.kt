package eu.kanade.tachiyomi.mslime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import eu.kanade.tachiyomi.novel.NovelFavoriteEntry
import eu.kanade.tachiyomi.novel.NovelFavoritesStore

/** مزامنة بيانات الحساب التي يجب أن تبقى بعد حذف التطبيق وإعادة تثبيته. */
object MslCloudSync {
    data class Profile(
        val name: String,
        val avatar: String?,
        val cover: String?,
        val level: Int,
        val rank: String,
    )

    private fun profilePrefs(ctx: Context) =
        ctx.getSharedPreferences("msl_profile", Context.MODE_PRIVATE)

    fun profile(ctx: Context): Profile? {
        val token = MslSupabase.token(ctx) ?: return null
        val uid = MslSupabase.uid(ctx)
        if (uid.isEmpty()) return null
        return runCatching {
            val result = MslSupabase.call(
                "GET",
                "/rest/v1/user_profiles?select=display_name,avatar_data,cover_data,level,rank&user_id=eq.$uid&limit=1",
                null,
                token,
            )
            if (result.first !in 200..299) return null
            val rows = JSONArray(result.second)
            if (rows.length() == 0) return null
            val row = rows.getJSONObject(0)
            Profile(
                name = row.optString("display_name", "قارئ السلايم"),
                avatar = row.optString("avatar_data").ifEmpty { null },
                cover = row.optString("cover_data").ifEmpty { null },
                level = row.optInt("level", 1).coerceAtLeast(1),
                rank = row.optString("rank", "F"),
            )
        }.getOrNull()
    }

    fun saveProfile(
        ctx: Context,
        name: String,
        avatar: String?,
        cover: String?,
        level: Int,
        rank: String,
    ): Boolean {
        val token = MslSupabase.token(ctx) ?: return false
        val uid = MslSupabase.uid(ctx)
        if (uid.isEmpty()) return false
        val body = JSONObject()
            .put("user_id", uid)
            .put("display_name", name.trim().ifEmpty { "قارئ السلايم" })
            .put("avatar_data", avatar ?: JSONObject.NULL)
            .put("cover_data", cover ?: JSONObject.NULL)
            .put("level", level.coerceAtLeast(1))
            .put("rank", rank.ifEmpty { "F" })
        val result = MslSupabase.call(
            "POST",
            "/rest/v1/user_profiles?on_conflict=user_id",
            body.toString(),
            token,
            "resolution=merge-duplicates,return=minimal",
        )
        return result.first in 200..299
    }

    fun applyProfile(ctx: Context, remote: Profile) {
        profilePrefs(ctx).edit()
            .putString("name", remote.name)
            .putInt("cloud_level", remote.level)
            .putString("cloud_rank", remote.rank)
            .apply()
        if (remote.avatar?.startsWith("data:image/") == true) writeImageData(ctx, remote.avatar, "msl_avatar.img")
        if (remote.cover?.startsWith("data:image/") == true) writeImageData(ctx, remote.cover, "msl_cover.img")
    }

    fun imageData(ctx: Context, fileName: String): String? {
        return runCatching {
            val file = File(ctx.filesDir, fileName)
            if (!file.exists()) return null
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val scaled = if (bitmap.width > 640 || bitmap.height > 640) {
                val ratio = minOf(640f / bitmap.width, 640f / bitmap.height)
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
            } else bitmap
            val bytes = ByteArrayOutputStream().also {
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, it)
            }.toByteArray()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun writeImageData(ctx: Context, data: String, fileName: String) {
        runCatching {
            val raw = data.substringAfter("base64,", data)
            File(ctx.filesDir, fileName).writeBytes(Base64.decode(raw, Base64.DEFAULT))
        }
    }

    fun syncAfterLogin(ctx: Context) {
        Thread {
            runCatching {
                val remote = profile(ctx)
                if (remote != null) {
                    applyProfile(ctx, remote)
                } else {
                    val p = profilePrefs(ctx)
                    val auth = ctx.getSharedPreferences("msl_auth", Context.MODE_PRIVATE)
                    saveProfile(
                        ctx,
                        p.getString("name", auth.getString("name", "قارئ السلايم")) ?: "قارئ السلايم",
                        imageData(ctx, "msl_avatar.img") ?: auth.getString("picture", null),
                        imageData(ctx, "msl_cover.img"),
                        p.getInt("cloud_level", 1),
                        p.getString("cloud_rank", "F") ?: "F",
                    )
                }
                syncNovelFavorites(ctx)
            }
        }.start()
    }

    private fun syncNovelFavorites(ctx: Context) {
        val token = MslSupabase.token(ctx) ?: return
        val uid = MslSupabase.uid(ctx)
        if (uid.isEmpty()) return
        val local = NovelFavoritesStore(ctx).all()
        val remoteResult = MslSupabase.call(
            "GET",
            "/rest/v1/user_favorites?select=item_key,title,cover,source_id,added_at&user_id=eq.$uid&item_type=eq.novel&order=added_at.desc",
            null,
            token,
        )
        if (remoteResult.first !in 200..299) return
        val remote = JSONArray(remoteResult.second).let { rows ->
            List(rows.length()) { i ->
                val row = rows.getJSONObject(i)
                val key = row.optString("item_key")
                val split = key.split("::", limit = 2)
                NovelFavoriteEntry(
                    sourceId = row.optString("source_id").ifEmpty { split.getOrNull(0).orEmpty() },
                    novelPath = split.getOrNull(1).orEmpty(),
                    title = row.optString("title"),
                    cover = row.optString("cover").ifEmpty { null },
                    addedAt = row.optLong("added_at", System.currentTimeMillis()),
                )
            }.filter { it.sourceId.isNotEmpty() && it.novelPath.isNotEmpty() }
        }
        if (local.isEmpty() && remote.isNotEmpty()) {
            NovelFavoritesStore(ctx).replace(remote)
            return
        }
        if (local.isEmpty() && remote.isEmpty()) return
        MslSupabase.call("DELETE", "/rest/v1/user_favorites?user_id=eq.$uid&item_type=eq.novel", null, token)
        val payload = JSONArray()
        local.forEach { item ->
            payload.put(
                JSONObject()
                    .put("user_id", uid)
                    .put("item_type", "novel")
                    .put("item_key", "${item.sourceId}::${item.novelPath}")
                    .put("title", item.title)
                    .put("cover", item.cover ?: JSONObject.NULL)
                    .put("source_id", item.sourceId)
                    .put("added_at", item.addedAt),
            )
        }
        MslSupabase.call("POST", "/rest/v1/user_favorites", payload.toString(), token, "return=minimal")
    }

    fun syncNovelFavoritesNow(ctx: Context) {
        Thread { runCatching { syncNovelFavorites(ctx) } }.start()
    }
}
