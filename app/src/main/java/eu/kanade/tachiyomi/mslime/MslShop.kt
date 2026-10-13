package eu.kanade.tachiyomi.mslime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Shop reads are user-scoped; purchases and frame state are authoritative server RPCs. */
object MslShop {
    private val _equippedFrameState = MutableStateFlow<String?>(null)
    val equippedFrameState = _equippedFrameState.asStateFlow()

    /** Fits the avatar completely inside the transparent opening of each frame asset. */
    fun frameAvatarFraction(frameId: String?): Float = when (frameId) {
        "frame_dark", "frame_fire" -> 0.40f
        "frame_ice" -> 0.41f
        "frame_galaxy" -> 0.43f
        "frame_king" -> 0.48f
        "frame_samurai" -> 0.46f
        null, "" -> 1f
        else -> 0.40f
    }

    private fun requestPrefs(context: Context) =
        context.getSharedPreferences("msl_shop_requests", Context.MODE_PRIVATE)

    fun useRequestId(context: Context, inventoryId: String): String {
        val prefs = requestPrefs(context)
        val key = "use_$inventoryId"
        return prefs.getString(key, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(key, it).apply()
        }
    }

    fun clearUseRequestId(context: Context, inventoryId: String) {
        requestPrefs(context).edit().remove("use_$inventoryId").apply()
    }

    data class Item(
        val id: String,
        val type: String,
        val name: String,
        val description: String,
        val price: Int,
        val assetRef: String?,
        val rarity: String?,
        val badge: String?,
        val featured: Boolean,
        val effectType: String?,
        val effectValue: Int,
        val owned: Boolean = false,
        val equipped: Boolean = false,
        val quantity: Int = 0,
        val inventoryId: String? = null,
    )

    data class PurchaseResult(val ok: Boolean, val reason: String, val balance: Int?)
    data class RankState(val xp: Int, val rank: String)
    data class UseResult(val ok: Boolean, val reason: String, val rankXp: Int?, val rank: String?, val quantity: Int?)

    fun catalog(context: Context): List<Item>? = runCatching {
        val token = MslSupabase.token(context) ?: return null
        val response = MslSupabase.call("POST", "/rest/v1/rpc/list_shop_catalog", "{}", token)
        if (response.first !in 200..299) return null
        val catalog = JSONArray(response.second)
        val inventoryResponse = MslSupabase.call(
            "GET",
            "/rest/v1/user_inventory?select=id,item_id,quantity,is_equipped&quantity=gt.0&limit=200",
            null,
            token,
        )
        if (inventoryResponse.first !in 200..299) return null
        val inventory = mutableMapOf<String, Triple<Int, Boolean, String>>()
        val rows = JSONArray(inventoryResponse.second)
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("item_id").takeIf(String::isNotBlank) ?: continue
            val inventoryId = row.optString("id").takeIf(String::isNotBlank) ?: continue
            inventory[id] = Triple(row.optInt("quantity"), row.optBoolean("is_equipped"), inventoryId)
        }
        buildList {
            for (index in 0 until catalog.length()) {
                val row = catalog.optJSONObject(index) ?: continue
                val payload = row.optJSONObject("payload") ?: JSONObject()
                val owned = inventory[row.optString("id")]
                add(
                    Item(
                        id = row.optString("id"),
                        type = row.optString("item_type"),
                        name = row.optString("name", "عنصر"),
                        description = row.optString("description"),
                        price = row.optInt("price_coins"),
                        assetRef = payload.optString("asset_ref").takeUnless { it.isBlank() || it == "null" },
                        rarity = row.optString("rarity").takeUnless { it.isBlank() || it == "null" },
                        badge = row.optString("badge_label").takeUnless { it.isBlank() || it == "null" },
                        featured = row.optBoolean("featured"),
                        effectType = payload.optString("effect_type").takeUnless { it.isBlank() || it == "null" },
                        effectValue = payload.optInt("effect_value", 0).coerceAtLeast(0),
                        owned = owned != null && owned.first > 0,
                        equipped = owned?.second == true,
                        quantity = owned?.first ?: 0,
                        inventoryId = owned?.third,
                    ),
                )
            }
        }
    }.getOrNull()

    fun purchase(context: Context, itemId: String, requestId: String = UUID.randomUUID().toString()): PurchaseResult {
        val token = MslSupabase.token(context) ?: return PurchaseResult(false, "auth_required", null)
        val response = MslSupabase.call(
            "POST",
            "/rest/v1/rpc/purchase_shop_item",
            JSONObject().put("p_item_id", itemId).put("p_request_id", requestId).toString(),
            token,
        )
        if (response.first !in 200..299) return PurchaseResult(false, "network_error", null)
        val result =
            runCatching { JSONObject(response.second) }.getOrNull()
                ?: return PurchaseResult(false, "invalid_response", null)
        val balance = result.optInt("balance", -1).takeIf { it >= 0 }
        if (balance != null) MslWallet.updateBalance(balance)
        return PurchaseResult(result.optBoolean("ok"), result.optString("reason", "success"), balance)
    }

    fun equip(context: Context, itemId: String?): Boolean {
        val token = MslSupabase.token(context) ?: return false
        val response = MslSupabase.call(
            "POST",
            "/rest/v1/rpc/equip_profile_frame",
            JSONObject().put("p_item_id", itemId ?: JSONObject.NULL).toString(),
            token,
        )
        val success =
            response.first in 200..299 &&
                runCatching { JSONObject(response.second).optBoolean("ok") }.getOrDefault(false)
        if (success) _equippedFrameState.value = itemId
        return success
    }

    fun rankState(context: Context): RankState? = runCatching {
        val token = MslSupabase.token(context) ?: return null
        val uid = MslSupabase.uid(context).takeIf(String::isNotBlank) ?: return null
        val response = MslSupabase.call(
            "GET",
            "/rest/v1/user_profiles?select=rank_xp,rank&user_id=eq.$uid&limit=1",
            null,
            token,
        )
        if (response.first !in 200..299) return null
        val row = JSONArray(response.second).optJSONObject(0) ?: return null
        RankState(row.optInt("rank_xp", 0).coerceAtLeast(0), row.optString("rank", "F"))
    }.getOrNull()

    fun useRankPotion(
        context: Context,
        inventoryId: String,
        requestId: String = UUID.randomUUID().toString(),
    ): UseResult {
        val token = MslSupabase.token(context) ?: return UseResult(false, "auth_required", null, null, null)
        val body = JSONObject()
            .put("p_inventory_id", inventoryId)
            .put("p_request_id", requestId)
            .toString()
        val response = MslSupabase.call("POST", "/rest/v1/rpc/use_inventory_item", body, token)
        if (response.first !in 200..299) return UseResult(false, "network_error", null, null, null)
        val result = runCatching { JSONObject(response.second) }.getOrNull()
            ?: return UseResult(false, "invalid_response", null, null, null)
        return UseResult(
            ok = result.optBoolean("ok"),
            reason = result.optString("reason", "success"),
            rankXp = result.optInt("rank_xp", -1).takeIf { it >= 0 },
            rank = result.optString("rank").takeIf(String::isNotBlank),
            quantity = result.optInt("quantity", -1).takeIf { it >= 0 },
        )
    }

    fun equippedFrame(context: Context): String? {
        val result = runCatching {
            val token = MslSupabase.token(context) ?: return null
            val uid = MslSupabase.uid(context).takeIf(String::isNotBlank) ?: return null
            val response = MslSupabase.call(
                "GET",
                "/rest/v1/user_profiles?select=equipped_frame_id&user_id=eq.$uid&limit=1",
                null,
                token,
            )
            if (response.first !in 200..299) return null
            JSONArray(response.second).optJSONObject(0)?.optString("equipped_frame_id")
                ?.takeUnless { it.isBlank() || it == "null" }
        }.getOrNull()
        _equippedFrameState.value = result
        return result
    }
}
