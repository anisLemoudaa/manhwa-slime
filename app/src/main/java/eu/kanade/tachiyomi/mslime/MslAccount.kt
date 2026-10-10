package eu.kanade.tachiyomi.mslime

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Person
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.VolunteerActivism

private fun loginPromptDone(ctx: Context) =
    ctx.getSharedPreferences("msl_profile", Context.MODE_PRIVATE).getBoolean("login_prompt_done", false)

private fun finishLoginPrompt(ctx: Context) {
    ctx.getSharedPreferences("msl_profile", Context.MODE_PRIVATE).edit().putBoolean("login_prompt_done", true).apply()
}

@Composable
fun MslFirstLoginDialog() {
    val ctx = LocalContext.current
    var visible by remember { mutableStateOf(!loginPromptDone(ctx)) }
    if (!visible) return
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(MaterialSymbols.Rounded.Person, null, tint = MslDesignTokens.accentBright, modifier = Modifier.size(42.dp)) },
        title = { Text("مرحبًا بك في Manhwa Slime", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("احفظ المفضلة والعملات والمستوى والصور والتعليقات في حسابك، حتى تبقى بعد إعادة تثبيت التطبيق.")
                Button(
                    onClick = { finishLoginPrompt(ctx); visible = false; MslAuth.startGoogle(ctx, true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(MaterialSymbols.Rounded.Person, null)
                    Text("  تسجيل الدخول بـ Google")
                }
                OutlinedButton(
                    onClick = { finishLoginPrompt(ctx); visible = false; MslAuth.startGoogle(ctx, false) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(MaterialSymbols.Rounded.VolunteerActivism, null)
                    Text("  لدي حساب بالفعل")
                }
                TextButton(
                    onClick = { finishLoginPrompt(ctx); visible = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(MaterialSymbols.Rounded.Person, null)
                    Text("  متابعة بحساب ضيف")
                }
            }
        },
        confirmButton = {},
    )
}

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
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surfaceRaised),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (email.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = Color(0xFF4285F4).copy(alpha = .18f), shape = MaterialTheme.shapes.large) {
                        Icon(MaterialSymbols.Rounded.VolunteerActivism, null, tint = Color(0xFF8AB4F8), modifier = Modifier.padding(12.dp).size(28.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("حساب Google متصل", fontWeight = FontWeight.Bold, color = MslDesignTokens.textPrimary)
                        Text(email, color = MslDesignTokens.textSecondary, maxLines = 1)
                    }
                }
                Text("مزامنة الحساب مفعّلة: بياناتك الأساسية والمفضلة محفوظة سحابيًا.", color = MslDesignTokens.textSecondary)
                TextButton(onClick = { MslAuth.signOut(ctx); rev++ }) { Text("تسجيل الخروج") }
            } else {
                Text("حساب ضيف", fontWeight = FontWeight.Bold, color = MslDesignTokens.textPrimary)
                Text("اربط Google لحفظ تقدمك ومفضلاتك بعد حذف التطبيق.", color = MslDesignTokens.textSecondary)
                Button(onClick = { MslAuth.startGoogle(ctx, true) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(MaterialSymbols.Rounded.Person, null)
                    Text("  ربط حساب Google")
                }
                OutlinedButton(onClick = { MslAuth.startGoogle(ctx, false) }, modifier = Modifier.fillMaxWidth()) {
                    Text("لدي حساب سابق")
                }
            }
            if (MslAds.privacyOptionsRequired) {
                TextButton(onClick = { ctx.findActivity()?.let(MslAds::showPrivacyOptions) }) {
                    Icon(MaterialSymbols.Rounded.Settings, null)
                    Text("  خيارات الخصوصية والإعلانات")
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
