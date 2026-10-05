package eu.kanade.tachiyomi.mslime

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class MslComment(val id: String, val userId: String, val name: String, val body: String, val at: String)

object MslSupabase {
    private const val BASE = "https://fqzdvcjekjgpdvomobre.supabase.co"
    private const val KEY = "sb_publishable_NpIFxgCHhuVs3yYqm8ynIA__DQ2A0xs"

    /** مفتاح العنوان: اسم المانهوا بحروف صغيرة بلا رموز، لتتوحّد التعليقات بين المصادر. */
    fun titleKey(title: String): String =
        title.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim().take(200)

    private fun call(method: String, path: String, body: String?, token: String?, prefer: String? = null): Pair<Int, String> {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("apikey", KEY)
        c.setRequestProperty("content-type", "application/json")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (prefer != null) c.setRequestProperty("Prefer", prefer)
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.readText() ?: ""
        return code to text
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("msl_auth", Context.MODE_PRIVATE)

    private fun saveSession(ctx: Context, j: JSONObject): String? {
        val tok = j.optString("access_token")
        if (tok.isEmpty()) return null
        val exp = System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600)
        val uid = j.optJSONObject("user")?.optString("id") ?: ""
        sp(ctx).edit()
            .putString("access", tok)
            .putString("refresh", j.optString("refresh_token"))
            .putLong("exp", exp)
            .putString("uid", uid)
            .apply()
        return tok
    }

    fun uid(ctx: Context): String = sp(ctx).getString("uid", "") ?: ""

    /** جلسة مجهولة تلقائية، تُجدَّد عند الانتهاء. */
    private fun token(ctx: Context): String? {
        val p = sp(ctx)
        val access = p.getString("access", null)
        if (access != null && p.getLong("exp", 0) > System.currentTimeMillis() / 1000 + 60) return access
        val refresh = p.getString("refresh", null)
        if (!refresh.isNullOrEmpty()) {
            val r = call("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh).toString(), null)
            if (r.first in 200..299) return saveSession(ctx, JSONObject(r.second))
        }
        val s = call("POST", "/auth/v1/signup", "{\"data\":{}}", null)
        return if (s.first in 200..299) saveSession(ctx, JSONObject(s.second)) else null
    }

    fun list(key: String): List<MslComment>? {
        return try {
            val q = URLEncoder.encode(key, "UTF-8").replace("+", "%20")
            val r = call(
                "GET",
                "/rest/v1/comments?title_key=eq.$q&select=id,user_id,author_name,body,created_at&order=created_at.desc&limit=50",
                null,
                null,
            )
            if (r.first !in 200..299) return null
            val a = JSONArray(r.second)
            List(a.length()) {
                val o = a.getJSONObject(it)
                MslComment(o.getString("id"), o.getString("user_id"), o.getString("author_name"), o.getString("body"), o.getString("created_at"))
            }
        } catch (e: Exception) {
            null
        }
    }

    /** يعيد null عند النجاح، أو رسالة الخطأ. */
    fun post(ctx: Context, key: String, name: String, body: String): String? {
        return try {
            val tok = token(ctx) ?: return "تعذّر إنشاء الجلسة المجهولة (فعّل Anonymous sign-ins)"
            val j = JSONObject().put("title_key", key).put("author_name", name).put("body", body)
            val r = call("POST", "/rest/v1/comments", j.toString(), tok, "return=minimal")
            when {
                r.first in 200..299 -> null
                r.second.contains("slow down") -> "انتظر 10 ثوانٍ بين التعليقات"
                else -> "تعذّر الإرسال (${r.first})"
            }
        } catch (e: Exception) {
            "تعذّر الاتصال بالإنترنت"
        }
    }

    fun delete(ctx: Context, id: String): Boolean {
        return try {
            val tok = token(ctx) ?: return false
            call("DELETE", "/rest/v1/comments?id=eq.$id", null, tok).first in 200..299
        } catch (e: Exception) {
            false
        }
    }

    fun report(ctx: Context, id: String): Boolean {
        return try {
            val tok = token(ctx) ?: return false
            val j = JSONObject().put("comment_id", id).put("reason", "abuse")
            call("POST", "/rest/v1/reports", j.toString(), tok, "return=minimal").first in 200..299
        } catch (e: Exception) {
            false
        }
    }
}

@Composable
fun MslCommentsDialog(title: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val key = remember(title) { MslSupabase.titleKey(title) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<MslComment>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(refresh) {
        val r = withContext(Dispatchers.IO) { MslSupabase.list(key) }
        items = r
        failed = r == null
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "التعليقات: $title", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val list = items
                    val me = MslSupabase.uid(ctx)
                    when {
                        failed -> Text("تعذّر تحميل التعليقات")
                        list == null -> CircularProgressIndicator()
                        list.isEmpty() -> Text("لا توجد تعليقات بعد. كن أول من يعلّق!")
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(list) { c ->
                                val mine = c.userId == me
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(text = c.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        Text(text = c.body)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = c.at.take(10), style = MaterialTheme.typography.bodySmall)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            TextButton(
                                                onClick = {
                                                    scope.launch {
                                                        withContext(Dispatchers.IO) {
                                                            if (mine) MslSupabase.delete(ctx, c.id) else MslSupabase.report(ctx, c.id)
                                                        }
                                                        refresh++
                                                    }
                                                },
                                            ) { Text(if (mine) "حذف" else "إبلاغ") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (msg.isNotEmpty()) Text(text = msg, color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { if (it.length <= 500) text = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("اكتب تعليقاً...") },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val body = text.trim()
                            if (body.isNotEmpty()) {
                                val name = ctx.getSharedPreferences("msl_profile", Context.MODE_PRIVATE)
                                    .getString("name", "قارئ السلايم") ?: "قارئ السلايم"
                                scope.launch {
                                    val err = withContext(Dispatchers.IO) { MslSupabase.post(ctx, key, name, body) }
                                    if (err == null) {
                                        text = ""
                                        msg = ""
                                        refresh++
                                    } else {
                                        msg = err
                                    }
                                }
                            }
                        },
                    ) { Text("إرسال") }
                }
                TextButton(onClick = onDismiss) { Text("إغلاق") }
            }
        }
    }
}
