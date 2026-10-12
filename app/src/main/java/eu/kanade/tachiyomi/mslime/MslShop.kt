package eu.kanade.tachiyomi.mslime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Shop reads are user-scoped; purchases and frame state are authoritative server RPCs. */
object MslShop {
    private val _equippedFrameState = MutableStateFlow<String?>(null)
    val equippedFrameState = _equippedFrameState.asStateFlow()

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
        val owned: Boolean = false,
        val equipped: Boolean = false,
        val quantity: Int = 0,
    )

    data class PurchaseResult(val ok: Boolean, val reason: String, val balance: Int?)

    fun catalog(context: Context): List<Item>? = runCatching {
        val token = MslSupabase.token(context) ?: return null
        val response = MslSupabase.call("POST", "/rest/v1/rpc/list_shop_catalog", "{}", token)
        if (response.first !in 200..299) return null
        val catalog = JSONArray(response.second)
        val inventoryResponse = MslSupabase.call(
            "GET",
            "/rest/v1/user_inventory?select=item_id,quantity,is_equipped&quantity=gt.0&limit=200",
            null,
            token,
        )
        if (inventoryResponse.first !in 200..299) return null
        val inventory = mutableMapOf<String, Pair<Int, Boolean>>()
        val rows = JSONArray(inventoryResponse.second)
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("item_id").takeIf(String::isNotBlank) ?: continue
            inventory[id] = row.optInt("quantity") to row.optBoolean("is_equipped")
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
                        owned = owned != null && owned.first > 0,
                        equipped = owned?.second == true,
                        quantity = owned?.first ?: 0,
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
        val result = runCatching { JSONObject(response.second) }.getOrNull() ?: return PurchaseResult(false, "invalid_response", null)
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
        val success = response.first in 200..299 && runCatching { JSONObject(response.second).optBoolean("ok") }.getOrDefault(false)
        if (success) _equippedFrameState.value = itemId
        return success
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
