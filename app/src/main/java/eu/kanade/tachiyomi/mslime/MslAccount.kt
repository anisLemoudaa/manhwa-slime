package eu.kanade.tachiyomi.mslime

import android.app.Activity
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
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

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
                TextButton(onClick = {
                    MslAuth.signOut(ctx)
                    rev++
                }) { Text("تسجيل الخروج") }
            } else {
                Text(text = "حسابك الحالي مجهول: تعليقاتك مرتبطة بهذا الهاتف فقط.")
                Button(
                    onClick = { MslAuth.startGoogle(ctx, true) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("تسجيل الدخول بحساب Google") }
                TextButton(onClick = { MslAuth.startGoogle(ctx, false) }) { Text("لدي حساب سابق") }
            }
            if (MslAds.privacyOptionsRequired) {
                TextButton(onClick = { ctx.findActivity()?.let(MslAds::showPrivacyOptions) }) {
                    Text(ctx.stringResource(MR.strings.coin_privacy_options))
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
