package eu.kanade.tachiyomi.mslime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class MslComment(
    val id: String,
    val userId: String,
    val name: String,
    val body: String,
    val at: String,
    val spoiler: Boolean,
    val level: Int,
    val rank: String,
    val avatar: String,
    val likes: Int,
    val dislikes: Int,
)

object MslRank {
    private val names = listOf("F", "E", "D", "C", "B", "A", "S", "SS", "SS+")
    private val mins = listOf(0, 50, 150, 350, 700, 1200, 2000, 3500, 5500)
    fun of(read: Int): String = names[mins.indexOfLast { read >= it }.coerceAtLeast(0)]
    fun level(read: Int): Int = read / 25 + 1
}

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

    /** جلسة (مجهولة تلقائياً أو Google)، تُجدَّد عند الانتهاء. */
    fun token(ctx: Context): String? {
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

    fun list(key: String, sort: Int): List<MslComment>? {
        return try {
            val q = URLEncoder.encode(key, "UTF-8").replace("+", "%20")
            val order = if (sort == 1) "likes.desc,created_at.desc" else "created_at.desc"
            val fields = "id,user_id,author_name,body,created_at,is_spoiler,level,rank,author_avatar,likes,dislikes"
            val r = call("GET", "/rest/v1/comments?title_key=eq.$q&select=$fields&order=$order&limit=50", null, null)
            if (r.first !in 200..299) return null
            val a = JSONArray(r.second)
            List(a.length()) {
                val o = a.getJSONObject(it)
                MslComment(
                    id = o.getString("id"),
                    userId = o.getString("user_id"),
                    name = o.getString("author_name"),
                    body = o.getString("body"),
                    at = o.getString("created_at"),
                    spoiler = o.optBoolean("is_spoiler", false),
                    level = o.optInt("level", 1),
                    rank = o.optString("rank", "F"),
                    avatar = if (o.isNull("author_avatar")) "" else o.optString("author_avatar", ""),
                    likes = o.optInt("likes", 0),
                    dislikes = o.optInt("dislikes", 0),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** تصويتاتي على هذه التعليقات: id -> 1 أو -1. */
    fun myVotes(ctx: Context, ids: List<String>): Map<String, Int> {
        if (ids.isEmpty() || sp(ctx).getString("access", null) == null) return emptyMap()
        return try {
            val tok = token(ctx) ?: return emptyMap()
            val r = call("GET", "/rest/v1/comment_votes?select=comment_id,value&comment_id=in.(${ids.joinToString(",")})", null, tok)
            if (r.first !in 200..299) return emptyMap()
            val a = JSONArray(r.second)
            val m = HashMap<String, Int>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                m[o.getString("comment_id")] = o.getInt("value")
            }
            m
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** يعيد null عند النجاح، أو رسالة الخطأ. */
    fun post(ctx: Context, key: String, name: String, body: String, spoiler: Boolean, level: Int, rank: String, avatar: String): String? {
        return try {
            val tok = token(ctx) ?: return "تعذّر إنشاء الجلسة المجهولة (فعّل Anonymous sign-ins)"
            val j = JSONObject()
                .put("title_key", key)
                .put("author_name", name)
                .put("body", body)
                .put("is_spoiler", spoiler)
                .put("level", level)
                .put("rank", rank)
            if (avatar.startsWith("https://lh3.googleusercontent.com/")) j.put("author_avatar", avatar)
            val r = call("POST", "/rest/v1/comments", j.toString(), tok, "return=minimal")
            when {
                r.first in 200..299 -> null
                r.second.contains("slow down") -> "انتظر 10 ثوانٍ بين التعليقات"
                else -> "تعذّر الإرسال (${r.first}): " + r.second.take(100)
            }
        } catch (e: Exception) {
            "تعذّر الاتصال بالإنترنت"
        }
    }

    /** value: 1 إعجاب، -1 عدم إعجاب، 0 إزالة التصويت. */
    fun vote(ctx: Context, id: String, value: Int): String? {
        return try {
            val tok = token(ctx) ?: return "تعذّر إنشاء الجلسة"
            val r = if (value == 0) {
                call("DELETE", "/rest/v1/comment_votes?comment_id=eq.$id&user_id=eq.${uid(ctx)}", null, tok)
            } else {
                val j = JSONObject().put("comment_id", id).put("value", value)
                call("POST", "/rest/v1/comment_votes?on_conflict=comment_id,user_id", j.toString(), tok, "resolution=merge-duplicates,return=minimal")
            }
            if (r.first in 200..299) null else "تعذّر التصويت (${r.first})"
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

object MslImages {
    private val cache = android.util.LruCache<String, Bitmap>(40)

    fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            val b = c.inputStream.use { BitmapFactory.decodeStream(it) }
            if (b != null) cache.put(url, b)
            b
        } catch (e: Exception) {
            null
        }
    }
}

@Composable
private fun Tx(
    text: String,
    size: TextUnit = 14.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = weight,
        fontFamily = MslUiFont,
        lineHeight = size * 1.6f,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun rankColor(r: String): Color = when (r) {
    "F" -> Color(0xFF8E9AA5)
    "E" -> Color(0xFF66BB6A)
    "D" -> Color(0xFF43A047)
    "C" -> Color(0xFF26A69A)
    "B" -> Color(0xFF2DB8FD)
    "A" -> Color(0xFF9C6BFF)
    "S" -> Color(0xFFFFB300)
    "SS" -> Color(0xFFFF7043)
    else -> Color(0xFFFF4D6D)
}

private fun timeAgo(iso: String): String {
    return try {
        val t = java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        val m = (System.currentTimeMillis() - t) / 60000
        when {
            m < 1 -> "الآن"
            m < 60 -> "قبل $m دقيقة"
            m < 1440 -> "قبل ${m / 60} ساعة"
            m < 43200 -> "قبل ${m / 1440} يوم"
            m < 525600 -> "قبل ${m / 43200} شهر"
            else -> "قبل ${m / 525600} سنة"
        }
    } catch (e: Exception) {
        iso.take(10)
    }
}

@Composable
private fun MslAvatar(url: String, name: String, size: Dp, mine: Boolean) {
    val ctx = LocalContext.current
    var bmp by remember(url, mine) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url, mine) {
        bmp = withContext(Dispatchers.IO) {
            val local = File(ctx.filesDir, "msl_avatar.img")
            if (mine && local.exists()) {
                val o = BitmapFactory.Options()
                o.inSampleSize = 4
                BitmapFactory.decodeFile(local.absolutePath, o)
            } else if (url.startsWith("https://lh3.googleusercontent.com/")) {
                MslImages.load(url)
            } else {
                null
            }
        }
    }
    val palette = listOf(0xFF2DB8FD, 0xFF9C6BFF, 0xFF26A69A, 0xFFFFB300, 0xFFFF7043, 0xFFFF4D6D)
    val bg = Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])
    val b = bmp
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Tx(name.take(1).uppercase(), 18.sp, FontWeight.Bold, Color.White)
        }
    }
}

@Composable
private fun CommentMenu(mine: Boolean, onAction: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(text = "⋯", fontSize = 22.sp, modifier = Modifier.clickable { open = true }.padding(horizontal = 8.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Tx(if (mine) "حذف تعليقي" else "إبلاغ عن التعليق") },
                onClick = {
                    open = false
                    onAction()
                },
            )
        }
    }
}

@Composable
fun MslCommentsDialog(title: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val key = remember(title) { MslSupabase.titleKey(title) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<MslComment>?>(null) }
    var votes by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var revealed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var sort by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var spoiler by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    val auth = ctx.getSharedPreferences("msl_auth", Context.MODE_PRIVATE)
    val prof = ctx.getSharedPreferences("msl_profile", Context.MODE_PRIVATE)
    val signedIn = !auth.getString("email", "").isNullOrEmpty()
    val myPicture = if (signedIn) auth.getString("picture", "") ?: "" else ""
    val myName = prof.getString("name", "قارئ السلايم") ?: "قارئ السلايم"
    val me = MslSupabase.uid(ctx)

    LaunchedEffect(refresh, sort) {
        val r = withContext(Dispatchers.IO) { MslSupabase.list(key, sort) }
        items = r
        failed = r == null
        if (r != null) votes = withContext(Dispatchers.IO) { MslSupabase.myVotes(ctx, r.map { it.id }) }
    }

    fun vote(c: MslComment, v: Int) {
        if (!signedIn) {
            msg = "سجّل الدخول بحساب Google من «الملف الشخصي» لتتمكن من التصويت"
            return
        }
        val cur = votes[c.id] ?: 0
        val nv = if (cur == v) 0 else v
        var l = c.likes
        var d = c.dislikes
        if (cur == 1) l--
        if (cur == -1) d--
        if (nv == 1) l++
        if (nv == -1) d++
        items = items?.map { if (it.id == c.id) it.copy(likes = l, dislikes = d) else it }
        votes = if (nv == 0) votes - c.id else votes + (c.id to nv)
        scope.launch {
            val err = withContext(Dispatchers.IO) { MslSupabase.vote(ctx, c.id, nv) }
            if (err != null) {
                msg = err
                refresh++
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Tx("التعليقات ${items?.size?.let { "($it)" } ?: ""}", 20.sp, FontWeight.Bold)
                        Tx(title, 13.sp, FontWeight.Normal, MaterialTheme.colorScheme.primary, 1)
                    }
                    Tx("إغلاق", 15.sp, FontWeight.Bold, MaterialTheme.colorScheme.primary, 1, Modifier.clickable { onDismiss() }.padding(8.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((i, label) in listOf("الأحدث", "الأكثر إعجاباً").withIndex()) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (sort == i) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { sort = i },
                        ) {
                            Tx(label, 13.sp, FontWeight.Bold, Color.Unspecified, 1, Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MslAvatar(myPicture, myName, 40.dp, true)
                    Spacer(modifier = Modifier.width(10.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { if (it.length <= 500) text = it },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        textStyle = TextStyle(fontFamily = MslUiFont, fontSize = 15.sp, lineHeight = 24.sp),
                        placeholder = { Tx("أضف تعليقك...", 15.sp) },
                        maxLines = 4,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (spoiler) Color(0xFFFF7043) else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { spoiler = !spoiler },
                    ) {
                        Tx("🔥 حرق", 14.sp, FontWeight.Bold, Color.Unspecified, 1, Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        shape = RoundedCornerShape(24.dp),
                        onClick = {
                            val body = text.trim()
                            if (body.isNotEmpty()) {
                                val read = prof.getInt("read", 0)
                                scope.launch {
                                    val err = withContext(Dispatchers.IO) {
                                        MslSupabase.post(ctx, key, myName, body, spoiler, MslRank.level(read), MslRank.of(read), myPicture)
                                    }
                                    if (err == null) {
                                        text = ""
                                        spoiler = false
                                        msg = ""
                                        refresh++
                                    } else {
                                        msg = err
                                    }
                                }
                            }
                        },
                    ) { Tx("نشر", 15.sp, FontWeight.Bold) }
                }
                if (msg.isNotEmpty()) Tx(msg, 13.sp, FontWeight.Normal, MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val list = items
                    when {
                        failed -> Tx("تعذّر تحميل التعليقات")
                        list == null -> CircularProgressIndicator()
                        list.isEmpty() -> Tx("لا توجد تعليقات بعد. كن أول من يعلّق!")
                        else -> LazyColumn {
                            items(list, key = { it.id }) { c ->
                                val mine = c.userId == me
                                val my = votes[c.id] ?: 0
                                val hidden = c.spoiler && !revealed.contains(c.id)
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        MslAvatar(c.avatar, c.name, 44.dp, mine)
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Tx(c.name, 15.sp, FontWeight.Bold, Color.Unspecified, 1, Modifier.weight(1f, fill = false))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Surface(shape = RoundedCornerShape(50), color = rankColor(c.rank).copy(alpha = 0.18f)) {
                                                    Tx("Lv.${c.level} • ${c.rank}", 12.sp, FontWeight.Bold, rankColor(c.rank), 1, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Tx(timeAgo(c.at), 12.sp, FontWeight.Normal, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), 1)
                                                Spacer(modifier = Modifier.weight(1f))
                                                CommentMenu(mine) {
                                                    scope.launch {
                                                        withContext(Dispatchers.IO) {
                                                            if (mine) MslSupabase.delete(ctx, c.id) else MslSupabase.report(ctx, c.id)
                                                        }
                                                        refresh++
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            if (hidden) {
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                    modifier = Modifier.fillMaxWidth().clickable { revealed = revealed + c.id },
                                                ) {
                                                    Tx("🔥 هذا التعليق يحتوي حرقاً — اضغط لإظهاره", 13.sp, FontWeight.Bold, Color.Unspecified, Int.MAX_VALUE, Modifier.padding(12.dp))
                                                }
                                            } else {
                                                Tx(c.body, 15.sp)
                                            }
                                            Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Tx(
                                                    "♥ ${c.likes}",
                                                    14.sp,
                                                    if (my == 1) FontWeight.Bold else FontWeight.Normal,
                                                    if (my == 1) Color(0xFFFF4D6D) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                    1,
                                                    Modifier.clickable { vote(c, 1) }.padding(end = 24.dp, top = 6.dp, bottom = 6.dp),
                                                )
                                                Tx(
                                                    "👎 ${c.dislikes}",
                                                    14.sp,
                                                    if (my == -1) FontWeight.Bold else FontWeight.Normal,
                                                    if (my == -1) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                    1,
                                                    Modifier.clickable { vote(c, -1) }.padding(top = 6.dp, bottom = 6.dp),
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
