package eu.kanade.tachiyomi.mslime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Server-authoritative chapter unlocks with a per-account local mirror for offline reading.
 * A missing entitlement is locked; network failures never grant access to an uncached chapter.
 */
object MslChapterAccess {
    private const val PREFS = "msl_chapter_access"
    private const val KEYS = "unlocked_keys"
    private const val USER = "user_id"
    private const val MAX_UNLOCK_BATCH = 100
    private val unlocked = MutableStateFlow<Set<String>>(emptySet())
    val unlockedKeys = unlocked.asStateFlow()

    data class UnlockResult(
        val allowed: Boolean,
        val balance: Int?,
        val charged: Int,
        val required: Int,
        val status: String,
    )

    /** Stable across devices and independent of local database IDs, titles, or chapter numbers. */
    fun mangaKey(sourceId: Long, mangaUrl: String, chapterUrl: String): String? =
        hashKey("manga", "$sourceId\u0000${mangaUrl.trim()}\u0000${chapterUrl.trim()}")

    fun novelKey(sourceId: String, novelPath: String, chapterPath: String): String? =
        hashKey("novel", "${sourceId.trim()}\u0000${novelPath.trim()}\u0000${chapterPath.trim()}")

    fun communityKey(novelId: String, chapterId: String): String? {
        val novel = novelId.trim().lowercase()
        val chapter = chapterId.trim().lowercase()
        if (!UUID_PATTERN.matches(novel) || !UUID_PATTERN.matches(chapter)) return null
        return "community:$novel:$chapter"
    }

    private fun hashKey(kind: String, identity: String): String? {
        if (identity.split('\u0000').any(String::isBlank)) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$kind:$digest"
    }

    /** Reads this account's persisted mirror; a different account never inherits unlocks. */
    fun cachedKeys(context: Context): Set<String> {
        val userId = MslSupabase.uid(context)
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (userId.isBlank() || preferences.getString(USER, null) != userId) {
            unlocked.value = emptySet()
            return emptySet()
        }
        val values = preferences.getStringSet(KEYS, emptySet()).orEmpty().toSet()
        unlocked.value = values
        return values
    }

    fun isUnlocked(context: Context, key: String): Boolean = key in cachedKeys(context)

    /**
     * Refresh the current account's complete unlock mirror. Returns null when offline/unauthenticated;
     * callers must keep the existing cache and must not infer a grant from a failed request.
     */
    fun refresh(context: Context): Set<String>? = runCatching {
        if (!MslWallet.enabled) return@runCatching emptySet()
        val token = authenticatedToken(context) ?: return@runCatching null
        val keys = mutableSetOf<String>()
        var offset = 0
        while (offset < 50_000) {
            val response = MslSupabase.call(
                "GET",
                "/rest/v1/chapter_unlocks?select=chapter_key&order=unlocked_at.desc&limit=1000&offset=$offset",
                null,
                token,
            )
            if (response.first !in 200..299) return@runCatching null
            val rows = JSONArray(response.second)
            repeat(rows.length()) { index -> keys += rows.getJSONObject(index).getString("chapter_key") }
            if (rows.length() < 1000) break
            offset += 1000
        }
        if (offset >= 50_000) return@runCatching null
        saveCache(context, MslSupabase.uid(context), keys)
        keys
    }.getOrNull()

    /**
     * Reads the server when the chapter is not already in the offline mirror. This is the fail-closed
     * gate used by readers, download workers, and preloading paths.
     */
    fun hasAccess(context: Context, key: String?): Boolean {
        if (!MslWallet.enabled) return true
        if (key.isNullOrBlank()) return false
        if (key in cachedKeys(context)) return true
        return key in (refresh(context) ?: return false)
    }

    /** One RPC handles both one chapter and up to 100 selected chapters atomically. */
    fun unlock(context: Context, keys: Collection<String>): UnlockResult? = runCatching {
        if (!MslWallet.enabled || keys.isEmpty() || keys.size > MAX_UNLOCK_BATCH) return@runCatching null
        val distinct = keys.toSet()
        if (distinct.any { !isValidKey(it) }) return@runCatching null
        val token = authenticatedToken(context) ?: return@runCatching null
        val response = MslSupabase.call(
            "POST",
            "/rest/v1/rpc/unlock_chapters",
            JSONObject().put("p_chapter_keys", JSONArray(distinct.toList())).toString(),
            token,
        )
        if (response.first !in 200..299) return@runCatching null
        val payload = JSONObject(response.second)
        val balance = payload.optInt("balance", -1).takeIf { it >= 0 }
        balance?.let(MslWallet::updateBalance)
        val granted = payload.optJSONArray("unlocked")?.let(::stringSet).orEmpty()
        val already = payload.optJSONArray("already_unlocked")?.let(::stringSet).orEmpty()
        val old = cachedKeys(context)
        saveCache(context, MslSupabase.uid(context), old + granted + already)
        UnlockResult(
            allowed = payload.optBoolean("allowed"),
            balance = balance,
            charged = payload.optInt("charged", 0).coerceAtLeast(0),
            required = payload.optInt("required", 0).coerceAtLeast(0),
            status = payload.optString("status", "unknown"),
        )
    }.getOrNull()

    private fun stringSet(values: JSONArray): Set<String> =
        buildSet { repeat(values.length()) { index -> values.optString(index).takeIf(String::isNotBlank)?.let(::add) } }

    private fun authenticatedToken(context: Context): String? =
        MslSupabase.token(context)?.takeIf { MslSupabase.uid(context).isNotBlank() }

    private fun saveCache(context: Context, userId: String, keys: Set<String>) {
        if (userId.isBlank()) return
        val safe = keys.filterTo(linkedSetOf(), ::isValidKey)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(USER, userId)
            .putStringSet(KEYS, safe)
            .apply()
        unlocked.value = safe
    }

    private fun isValidKey(key: String): Boolean =
        MANGA_OR_NOVEL_PATTERN.matches(key) || COMMUNITY_PATTERN.matches(key)

    private val MANGA_OR_NOVEL_PATTERN = Regex("^(manga|novel):[0-9a-f]{64}$")
    private val COMMUNITY_PATTERN = Regex("^community:[0-9a-f-]{36}:[0-9a-f-]{36}$")
    private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
}
