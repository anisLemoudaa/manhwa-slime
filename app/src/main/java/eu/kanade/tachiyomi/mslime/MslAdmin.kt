package eu.kanade.tachiyomi.mslime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object MslAdmin {
    data class Report(
        val id: String,
        val commentId: String,
        val reason: String,
        val description: String,
        val status: String,
        val createdAt: String,
    )

    data class Snapshot(
        val role: String?,
        val canViewReports: Boolean,
        val canModerate: Boolean,
        val canResolve: Boolean,
        val reports: List<Report>?,
    )

    fun role(context: android.content.Context): String? = runCatching {
        val token = MslSupabase.token(context) ?: return null
        val response = MslSupabase.call("POST", "/rest/v1/rpc/get_my_admin_role", "{}", token)
        if (response.first !in 200..299) return null
        response.second.trim().removeSurrounding("\"").takeIf(String::isNotBlank)
    }.getOrNull()

    fun hasPermission(context: android.content.Context, permission: String): Boolean = runCatching {
        val token = MslSupabase.token(context) ?: return false
        val response = MslSupabase.call(
            "POST",
            "/rest/v1/rpc/has_admin_permission",
            JSONObject().put("p_permission", permission).toString(),
            token,
        )
        response.first in 200..299 && response.second.trim().removeSurrounding("\"").toBoolean()
    }.getOrDefault(false)

    fun reports(context: android.content.Context): List<Report>? = runCatching {
        val token = MslSupabase.token(context) ?: return null
        val path = "/rest/v1/reports?select=id,comment_id,reason,description,status,created_at" +
            "&status=in.(open,reviewing)&order=created_at.desc&limit=50"
        val response = MslSupabase.call(
            "GET",
            path,
            null,
            token,
        )
        if (response.first !in 200..299) return null
        val rows = JSONArray(response.second)
        List(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            Report(
                id = row.optString("id"),
                commentId = row.optString("comment_id"),
                reason = row.optString("reason", "other"),
                description = row.optString("description"),
                status = row.optString("status", "open"),
                createdAt = row.optString("created_at"),
            )
        }
    }.getOrNull()

    fun moderateComment(
        context: android.content.Context,
        commentId: String,
        action: String,
        reason: String,
    ): Boolean = runCatching {
        val token = MslSupabase.token(context) ?: return false
        val body = JSONObject()
            .put("p_comment_id", commentId)
            .put("p_action", action)
            .put("p_reason", reason)
            .toString()
        val response = MslSupabase.call("POST", "/rest/v1/rpc/moderate_comment", body, token)
        response.first in 200..299 && response.second.trim().removeSurrounding("\"").toBoolean()
    }.getOrDefault(false)

    fun resolveReport(
        context: android.content.Context,
        reportId: String,
        status: String,
        note: String,
    ): Boolean = runCatching {
        val token = MslSupabase.token(context) ?: return false
        val response = MslSupabase.call(
            "POST",
            "/rest/v1/rpc/resolve_comment_report",
            JSONObject().put("p_report_id", reportId).put("p_status", status).put("p_note", note).toString(),
            token,
        )
        response.first in 200..299 && response.second.trim().removeSurrounding("\"").toBoolean()
    }.getOrDefault(false)
}

class MslAdminScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = androidx.compose.ui.platform.LocalContext.current
        val scope = rememberCoroutineScope()
        var role by remember { mutableStateOf<String?>(null) }
        var canViewReports by remember { mutableStateOf(false) }
        var canModerate by remember { mutableStateOf(false) }
        var canResolve by remember { mutableStateOf(false) }
        var reports by remember { mutableStateOf<List<MslAdmin.Report>?>(null) }
        var loading by remember { mutableStateOf(true) }
        var busyId by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf("") }
        var refresh by remember { mutableStateOf(0) }

        LaunchedEffect(refresh) {
            loading = true
            error = false
            val result = withContext(Dispatchers.IO) {
                val currentRole = MslAdmin.role(context)
                val viewReports = MslAdmin.hasPermission(context, "view_reports")
                val moderate = MslAdmin.hasPermission(context, "moderate_comments")
                val resolve = MslAdmin.hasPermission(context, "resolve_reports")
                val list = if (viewReports) MslAdmin.reports(context) else emptyList()
                MslAdmin.Snapshot(currentRole, viewReports, moderate, resolve, list)
            }
            role = result.role
            canViewReports = result.canViewReports
            canModerate = result.canModerate
            canResolve = result.canResolve
            reports = result.reports
            error = canViewReports && reports == null
            loading = false
        }

        Column(
            modifier = Modifier.fillMaxSize().background(MslDesignTokens.background).padding(horizontal = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navigator.pop() }) {
                    Text("‹", style = MaterialTheme.typography.headlineMedium, color = MslDesignTokens.textPrimary)
                }
                Column {
                    Text(
                        "لوحة الإدارة",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MslDesignTokens.textPrimary,
                    )
                    Text(
                        "الدور: ${role ?: "جارٍ التحقق"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MslDesignTokens.textSecondary,
                    )
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { refresh++ }, enabled = !loading) { Text("تحديث") }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                role == null || role == "user" -> Text(
                    "لا تملك صلاحية الوصول إلى لوحة الإدارة.",
                    color = MslDesignTokens.textSecondary,
                )
                !canViewReports -> Text(
                    "لا توجد أقسام مفعلة لهذا الدور حالياً.",
                    color = MslDesignTokens.textSecondary,
                )
                error -> Column {
                    Text(
                        "تعذّر تحميل البلاغات. تحقق من الاتصال والصلاحية.",
                        color = MslDesignTokens.textSecondary,
                    )
                    Button(onClick = { refresh++ }) { Text("إعادة المحاولة") }
                }
                reports.isNullOrEmpty() -> Text(
                    "لا توجد بلاغات مفتوحة حالياً.",
                    color = MslDesignTokens.textSecondary,
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Text(
                            "البلاغات المفتوحة",
                            color = MslDesignTokens.textPrimary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(reports.orEmpty(), key = { it.id }) { report ->
                        var reason by remember(report.id) { mutableStateOf("") }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MslDesignTokens.surface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "السبب: ${report.reason}",
                                    color = MslDesignTokens.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (report.description.isNotBlank()) {
                                    Text(report.description, color = MslDesignTokens.textSecondary)
                                }
                                Text(
                                    "الحالة: ${report.status}",
                                    color = MslDesignTokens.textMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (canModerate) {
                                    val hideLabel = if (busyId == report.id) "جارٍ التنفيذ…" else "إخفاء التعليق"
                                    OutlinedTextField(
                                        value = reason,
                                        onValueChange = { reason = it.take(500) },
                                        label = { Text("سبب الإجراء مطلوب") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = false,
                                        minLines = 2,
                                    )
                                    Button(
                                        enabled = busyId == null && reason.trim().length >= 3,
                                        onClick = {
                                            busyId = report.id
                                            scope.launch {
                                                val ok = withContext(Dispatchers.IO) {
                                                    MslAdmin.moderateComment(
                                                        context,
                                                        report.commentId,
                                                        "hide",
                                                        reason.trim(),
                                                    )
                                                }
                                                busyId = null
                                                notice = if (ok) {
                                                    "تم إخفاء التعليق مع تسجيل السبب."
                                                } else {
                                                    "تعذّر الإخفاء؛ تحقق من الصلاحية."
                                                }
                                                if (ok) refresh++
                                            }
                                        },
                                    ) {
                                        Text(hideLabel)
                                    }
                                }
                                if (canResolve) {
                                    OutlinedButton(
                                        enabled = busyId == null,
                                        onClick = {
                                            busyId = report.id
                                            scope.launch {
                                                val ok = withContext(Dispatchers.IO) {
                                                    MslAdmin.resolveReport(
                                                        context,
                                                        report.id,
                                                        "dismissed",
                                                        "تمت مراجعة البلاغ.",
                                                    )
                                                }
                                                busyId = null
                                                notice = if (ok) {
                                                    "تم إغلاق البلاغ."
                                                } else {
                                                    "تعذّر تحديث البلاغ؛ تحقق من صلاحيتك."
                                                }
                                                if (ok) refresh++
                                            }
                                        },
                                    ) { Text("تجاهل البلاغ") }
                                }
                            }
                        }
                    }
                    if (notice.isNotBlank()) item { Text(notice, color = MslDesignTokens.textSecondary) }
                }
            }
            if (notice.isNotBlank() && (loading || reports.isNullOrEmpty() || !canViewReports)) {
                Spacer(Modifier.height(8.dp))
                Text(notice, color = MslDesignTokens.textSecondary)
            }
        }
    }
}
