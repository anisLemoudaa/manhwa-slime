#!/bin/bash
# تسجيل الدخول بحساب Google عبر Supabase (ربط الحساب المجهول بحساب Google)
cd "$(dirname "$0")" || exit 1
K=app/src/main/java/eu/kanade/tachiyomi/mslime
M=app/src/main/AndroidManifest.xml
P=app/src/main/java/eu/kanade/tachiyomi/ui/profile/ProfileTab.kt
[ -f "$K/MslComments.kt" ] || { echo "[!!] MslComments.kt missing: run msl_comments.sh first"; exit 1; }
[ -f "$P" ] || { echo "[!!] ProfileTab.kt missing: run msl_profile.sh first"; exit 1; }
[ -f "$M" ] || { echo "[!!] manifest not found"; exit 1; }

cat > $K/MslAuth.kt <<'EOF'
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
EOF

cat > $K/MslAccount.kt <<'EOF'
package eu.kanade.tachiyomi.mslime

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

@Composable
fun MslAccountCard() {
    val ctx = LocalContext.current
    var rev by remember { mutableIntStateOf(0) }
    val owner = remember {
        var c: Context = ctx
        while (c is ContextWrapper && c !is LifecycleOwner) c = c.baseContext
        c as? LifecycleOwner
    }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) rev++ }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    val email = remember(rev) { MslAuth.email(ctx) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (email.isNotEmpty()) {
                Text(text = "متصل بحساب Google", fontWeight = FontWeight.Bold)
                Text(text = email)
                TextButton(onClick = { MslAuth.signOut(ctx); rev++ }) { Text("تسجيل الخروج") }
            } else {
                Text(text = "حسابك الحالي مجهول: تعليقاتك مرتبطة بهذا الهاتف فقط.")
                Button(
                    onClick = { MslAuth.startGoogle(ctx, true) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("تسجيل الدخول بحساب Google") }
                TextButton(onClick = { MslAuth.startGoogle(ctx, false) }) { Text("لدي حساب سابق") }
            }
        }
    }
}
EOF

python3 - <<'EOF'
import re
# 1) جعل token() عامة
p='app/src/main/java/eu/kanade/tachiyomi/mslime/MslComments.kt'
s=open(p).read()
if 'private fun token(ctx: Context): String? {' in s:
    s=s.replace('private fun token(ctx: Context): String? {','fun token(ctx: Context): String? {',1)
    open(p,'w').write(s); print('[ok] token() is public')
else:
    print('[ok] token() already public or not found')
# 2) الـ Manifest
m='app/src/main/AndroidManifest.xml'
t=open(m).read()
if 'AuthCallbackActivity' in t:
    print('[ok] manifest already has callback activity')
else:
    add='''        <activity
            android:name=".mslime.AuthCallbackActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:noHistory="true"
            android:theme="@android:style/Theme.Translucent.NoTitleBar">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="manhwaslime" android:host="auth" />
            </intent-filter>
        </activity>
'''
    if '</application>' not in t: print('[!!] </application> not found'); raise SystemExit(1)
    t=t.replace('</application>',add+'    </application>',1)
    open(m,'w').write(t); print('[ok] manifest patched')
# 3) بطاقة الحساب في الملف الشخصي
pt='app/src/main/java/eu/kanade/tachiyomi/ui/profile/ProfileTab.kt'
u=open(pt).read()
if 'MslAccountCard' in u:
    print('[ok] ProfileTab already patched')
else:
    mm=re.search(r'\n([ \t]*)if \(s is StatsScreenState\.Success\) \{\n[ \t]*val ms',u)
    if not mm: print('[!!] insertion point not found in ProfileTab.kt'); raise SystemExit(1)
    ind=mm.group(1)
    u=u[:mm.start()]+'\n'+ind+'eu.kanade.tachiyomi.mslime.MslAccountCard()'+u[mm.start():]
    open(pt,'w').write(u); print('[ok] ProfileTab patched')
EOF
if [ -z "$SKIP_GIT" ]; then
  git add -A && git commit -m "Google sign-in via Supabase" && git push && echo "[ok] pushed"
fi
