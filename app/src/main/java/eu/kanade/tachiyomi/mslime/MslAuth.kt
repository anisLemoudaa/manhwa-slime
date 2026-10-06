package eu.kanade.tachiyomi.mslime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import eu.kanade.tachiyomi.ui.main.MainActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

object MslAuth {
    private const val BASE = "https://fqzdvcjekjgpdvomobre.supabase.co"
    private const val KEY = "sb_publishable_NpIFxgCHhuVs3yYqm8ynIA__DQ2A0xs"
    private const val REDIRECT = "manhwaslime://auth"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("msl_auth", Context.MODE_PRIVATE)

    fun email(ctx: Context): String = sp(ctx).getString("email", "") ?: ""

    fun signOut(ctx: Context) {
        sp(ctx).edit().clear().apply()
    }

    private fun payload(token: String): JSONObject? {
        return try {
            val part = token.split(".")[1]
            JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
        } catch (e: Exception) {
            null
        }
    }

    /** يعالج الرابط الراجع من المتصفح. يعيد null عند النجاح أو رسالة الخطأ. */
    fun handleRedirect(ctx: Context, uri: Uri): String? {
        val raw = uri.encodedFragment ?: uri.encodedQuery ?: return "رابط غير صالح"
        val map = HashMap<String, String>()
        for (kv in raw.split("&")) {
            val i = kv.indexOf('=')
            if (i > 0) {
                map[URLDecoder.decode(kv.substring(0, i), "UTF-8")] = URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            }
        }
        val error = map["error_description"]
        if (error != null) return error
        val access = map["access_token"] ?: return "لم يصل رمز الدخول"
        val p = payload(access)
        val exp = System.currentTimeMillis() / 1000 + (map["expires_in"]?.toLongOrNull() ?: 3600L)
        sp(ctx).edit()
            .putString("access", access)
            .putString("refresh", map["refresh_token"] ?: "")
            .putLong("exp", exp)
            .putString("uid", p?.optString("sub") ?: "")
            .putString("email", p?.optString("email") ?: "")
            .apply()
        return null
    }

    /** link=true: يربط حسابك المجهول بـ Google (تبقى تعليقاتك). link=false: دخول بحساب Google موجود. */
    fun startGoogle(ctx: Context, link: Boolean) {
        Thread {
            var url: String? = null
            var err: String? = null
            try {
                val redirect = URLEncoder.encode(REDIRECT, "UTF-8")
                if (link) {
                    val tok = MslSupabase.token(ctx)
                    if (tok == null) {
                        err = "تعذّر إنشاء الجلسة المجهولة"
                    } else {
                        val c = URL("$BASE/auth/v1/user/identities/authorize?provider=google&redirect_to=$redirect&skip_http_redirect=true")
                            .openConnection() as HttpURLConnection
                        c.connectTimeout = 15000
                        c.readTimeout = 20000
                        c.setRequestProperty("apikey", KEY)
                        c.setRequestProperty("Authorization", "Bearer $tok")
                        val code = c.responseCode
                        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                        if (code in 200..299) {
                            url = JSONObject(body).optString("url")
                        } else {
                            err = "تعذّر بدء الربط ($code): " + body.take(120)
                        }
                    }
                } else {
                    url = "$BASE/auth/v1/authorize?provider=google&redirect_to=$redirect"
                }
            } catch (e: Exception) {
                err = "تعذّر الاتصال بالإنترنت"
            }
            val u = url
            val e = err
            Handler(Looper.getMainLooper()).post {
                if (!u.isNullOrEmpty()) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    Toast.makeText(ctx, e ?: "تعذّر بدء تسجيل الدخول", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}

/** يستقبل الرابط manhwaslime://auth الراجع من متصفح Google. */
class AuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val uri = i?.data
        val err = if (uri != null) MslAuth.handleRedirect(this, uri) else "لا توجد بيانات"
        Toast.makeText(this, if (err == null) "تم تسجيل الدخول بنجاح" else "فشل تسجيل الدخول: $err", Toast.LENGTH_LONG).show()
        val back = Intent(this, MainActivity::class.java)
        back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(back)
        finish()
    }
}
