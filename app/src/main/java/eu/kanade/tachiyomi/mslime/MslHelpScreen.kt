package eu.kanade.tachiyomi.mslime

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toast

private const val SUPPORT_EMAIL = "anislemoudaa2741@gmail.com"
private val HELP_QUESTIONS = listOf(
    "كيف أسجل الدخول؟" to "افتح الحساب واختر تسجيل الدخول، ثم أكمل خطوات Google Sign-In.",
    "كيف أضيف مانهو إلى المفضلة؟" to "افتح صفحة المانهو واضغط رمز المفضلة لإضافتها إلى مكتبتك.",
    "كيف أتابع مستخدماً؟" to "افتح البحث عن المستخدمين أو ملفه العام واضغط متابعة.",
    "كيف أبحث عن مستخدم؟" to "استخدم البحث عن المستخدمين بالاسم الظاهر، أو بالبريد إذا سمحت إعدادات الخصوصية.",
    "لماذا لا يظهر الإعلان؟" to "قد لا يتوفر إعلان حالياً أو قد تمنعه إعدادات الشبكة والجهاز. حاول لاحقاً.",
    "كيف أستعيد بياناتي بعد إعادة تثبيت التطبيق؟" to "سجّل الدخول بالحساب نفسه وانتظر اكتمال المزامنة السحابية.",
    "كيف أبلغ عن تعليق مخالف؟" to "استخدم خيار الإبلاغ بجوار التعليق واختر السبب المناسب.",
    "كيف أغير إعدادات الخصوصية؟" to "من الملف الشخصي افتح إعدادات الخصوصية وحدد مستوى الظهور لكل حقل.",
    "كيف أطلب المساعدة من المشرفين؟" to "أرسل رسالة إلى بريد الدعم مع وصف المشكلة، أو أبلغ عنها داخل التطبيق عند توفر الخيار.",
)

class MslHelpScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("مركز المساعدة", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("بريد الدعم: $SUPPORT_EMAIL", color = MslDesignTokens.accentBright)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HelpAction("نسخ البريد") {
                    context.copyToClipboard("Support email", SUPPORT_EMAIL)
                    context.toast("تم النسخ.")
                }
                HelpAction("فتح تطبيق البريد") { openSupportEmail(context) }
                HelpAction("إرسال رسالة") { openSupportEmail(context) }
            }
            Text("الأسئلة الشائعة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(HELP_QUESTIONS) { (question, answer) ->
                    var expanded by remember(question) { mutableStateOf(false) }
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surfaceRaised),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(question, fontWeight = FontWeight.SemiBold)
                            if (expanded) Text(answer, color = MslDesignTokens.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpAction(label: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surfaceRaised),
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
    }
}

private fun openSupportEmail(context: android.content.Context) {
    val subject = Uri.encode("Manhwa Slime Support")
    val body = Uri.encode("مرحباً، أواجه المشكلة التالية في تطبيق Manhwa Slime:")
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL?subject=$subject&body=$body"))
    runCatching { context.startActivity(intent) }
        .onFailure {
            context.copyToClipboard("Support email", SUPPORT_EMAIL)
            context.toast("لا يوجد تطبيق بريد. تم نسخ البريد.")
        }
}
