package eu.kanade.tachiyomi.mslime

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private fun findActivity(ctx: Context): Activity? {
    var c: Context = ctx
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun MslReaderSettingsCard() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(MslHook.enabled(ctx)) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "زر الترجمة «ع» في القارئ", fontWeight = FontWeight.Bold)
                    Text(text = "اسحبه لأي مكان، أو أخفِه من هنا", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = on, onCheckedChange = {
                    on = it
                    MslHook.setEnabled(ctx, it)
                })
            }
            TextButton(onClick = {
                findActivity(ctx)?.let { MslTranslate.askKey(it) }
            }) { Text("مفتاح الترجمة الذكية") }
            TextButton(
                onClick = {
                    MslHook.resetPosition(ctx)
                    Toast.makeText(ctx, "أُعيد الزر لمكانه الأصلي", Toast.LENGTH_SHORT).show()
                },
            ) { Text("إعادة الزر لمكانه الأصلي") }
        }
    }
}
