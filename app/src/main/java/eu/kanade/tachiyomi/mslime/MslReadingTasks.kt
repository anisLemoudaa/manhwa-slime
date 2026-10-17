package eu.kanade.tachiyomi.mslime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Server-owned daily reading tasks. Coin amounts are never accepted from the client. */
object MslReadingTasks {
    private val _refreshVersion = MutableStateFlow(0)
    val refreshVersion = _refreshVersion.asStateFlow()

    fun invalidate() {
        _refreshVersion.value += 1
    }

    data class ReadingTask(
        val id: String,
        val title: String,
        val description: String,
        val rewardCoins: Int,
        val targetCount: Int,
        val progress: Int,
        val claimed: Boolean,
        val claimable: Boolean,
    )

    data class Snapshot(val date: String, val tasks: List<ReadingTask>)
    data class ClaimResult(val claimed: Boolean, val alreadyClaimed: Boolean, val rewardCoins: Int?, val balance: Int?)

    /** Stable across devices: never use local database row IDs in the account-wide task ledger. */
    fun canonicalChapterKey(sourceId: Long, mangaUrl: String, chapterUrl: String): String? {
        val normalizedMangaUrl = mangaUrl.trim().takeIf { it.isNotEmpty() } ?: return null
        val normalizedChapterUrl = chapterUrl.trim().takeIf { it.isNotEmpty() } ?: return null
        val identity = "$sourceId\u0000$normalizedMangaUrl\u0000$normalizedChapterUrl"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "manga:$digest"
    }

    fun parseSnapshot(payload: String): Snapshot? = runCatching {
        val root = Json.parseToJsonElement(payload) as? JsonObject
        val date = root?.get("date")?.takeUnless { it == JsonNull }
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.takeIf { it.isNotBlank() }
        if (root == null || date == null) null else Snapshot(date, parseTasks(root["tasks"]?.toString() ?: "[]"))
    }.getOrNull()

    fun parseTasks(payload: String): List<ReadingTask> = runCatching {
        val root = Json.parseToJsonElement(payload)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["tasks"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        array.map { element ->
            val item = element as? JsonObject ?: error("task must be an object")
            val id = item["id"]?.takeUnless { it == JsonNull }
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?.takeIf { it.isNotBlank() } ?: error("task id missing")
            val target = item["target_count"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull }
                ?.coerceAtLeast(1) ?: 1
            ReadingTask(
                id = id,
                title = item["title"]?.takeUnless { it == JsonNull }
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    .orEmpty().ifBlank { "مهمة قراءة" },
                description = item["description"]?.takeUnless { it == JsonNull }
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
                rewardCoins = item["reward_coins"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull }
                    ?.coerceAtLeast(0) ?: 0,
                targetCount = target,
                progress = item["progress"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull }
                    ?.coerceIn(0, target) ?: 0,
                claimed = item["claimed"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull } ?: false,
                claimable = item["claimable"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull } ?: false,
            )
        }
    }.getOrDefault(emptyList())

    fun start(context: Context, chapterKey: String): String? {
        if (!isCanonicalChapterKey(chapterKey)) return null
        return runCatching {
            val token = authenticatedToken(context) ?: return@runCatching null
            val sessionId = UUID.randomUUID().toString()
            val response = MslSupabase.call(
                "POST",
                "/rest/v1/rpc/begin_reading_task_session",
                JSONObject().put("p_session_id", sessionId).put("p_chapter_key", chapterKey).toString(),
                token,
            )
            if (response.first !in 200..299) return@runCatching null
            JSONObject(response.second).optString("session_id").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun complete(context: Context, sessionId: String): Boolean = runCatching {
        val token = authenticatedToken(context) ?: return@runCatching false
        val response = MslSupabase.call(
            "POST",
            "/rest/v1/rpc/complete_reading_task_session",
            JSONObject().put("p_session_id", sessionId).toString(),
            token,
        )
        val accepted = response.first in 200..299 && JSONObject(response.second).optBoolean("accepted")
        if (accepted) invalidate()
        accepted
    }.getOrDefault(false)

    fun load(context: Context): Snapshot? = runCatching {
        val token = authenticatedToken(context) ?: return@runCatching null
        val response = MslSupabase.call("POST", "/rest/v1/rpc/reading_tasks_for_today", "{}", token)
        if (response.first !in 200..299) return@runCatching null
        parseSnapshot(response.second)
    }.getOrNull()

    fun claim(context: Context, taskId: String): ClaimResult? = runCatching {
        if (taskId.isBlank() || taskId.length > 80) return@runCatching null
        val token = authenticatedToken(context) ?: return@runCatching null
        val response = MslSupabase.call(
            "POST",
            "/rest/v1/rpc/claim_reading_task",
            JSONObject().put("p_task_id", taskId).toString(),
            token,
        )
        if (response.first !in 200..299) return@runCatching null
        val body = JSONObject(response.second)
        val balance = body.optInt("balance", -1).takeIf { it >= 0 }
        balance?.let(MslWallet::updateBalance)
        if (body.optBoolean("claimed") || body.optBoolean("already_claimed")) invalidate()
        ClaimResult(
            claimed = body.optBoolean("claimed"),
            alreadyClaimed = body.optBoolean("already_claimed"),
            rewardCoins = body.optInt("reward_coins", -1).takeIf { it >= 0 },
            balance = balance,
        )
    }.getOrNull()

    private fun authenticatedToken(context: Context): String? =
        MslSupabase.token(context)?.takeIf { MslSupabase.uid(context).isNotBlank() }

    private fun isCanonicalChapterKey(key: String): Boolean =
        Regex("^manga:(?:[0-9]+:[0-9]+|[0-9a-f]{64})$").matches(key)
}
