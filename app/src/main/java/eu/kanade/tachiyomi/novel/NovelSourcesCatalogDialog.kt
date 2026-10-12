@file:Suppress("ktlint:standard:no-wildcard-imports")

package eu.kanade.tachiyomi.novel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.mslime.MslDesignTokens

@Composable
fun NovelSourcesCatalogDialog(
    entries: List<NovelRepositoryEntry>,
    installedIds: Set<String>,
    languageFilter: String,
    loading: Boolean,
    error: String?,
    onLanguageFilter: (String) -> Unit,
    onInstall: (NovelRepositoryEntry) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val visibleEntries = when (languageFilter) {
        "ar" -> entries.filter { it.lang.orEmpty().startsWith("ar", ignoreCase = true) }
        "en" -> entries.filter { it.lang.orEmpty().startsWith("en", ignoreCase = true) }
        else -> entries
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MslDesignTokens.cardShape,
        containerColor = MslDesignTokens.surface,
        titleContentColor = MslDesignTokens.textPrimary,
        textContentColor = MslDesignTokens.textSecondary,
        title = { Text("مصادر الروايات", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("مستودع LNReader • ${entries.size} مصدرًا")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("all" to "الكل", "ar" to "العربية", "en" to "الإنجليزية").forEach { (key, label) ->
                        FilterChip(
                            selected = languageFilter == key,
                            onClick = { onLanguageFilter(key) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MslDesignTokens.accent.copy(alpha = 0.28f),
                                selectedLabelColor = MslDesignTokens.textPrimary,
                                labelColor = MslDesignTokens.textSecondary,
                            ),
                        )
                    }
                }
                if (error != null) Text(error, color = MslDesignTokens.danger, style = MaterialTheme.typography.bodySmall)
                when {
                    loading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = MslDesignTokens.accentBright)
                    }
                    visibleEntries.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (entries.isEmpty()) "تعذر تحميل الفهرس أو أنه فارغ." else "لا توجد مصادر ضمن هذا الفلتر.", color = MslDesignTokens.textMuted)
                        if (entries.isEmpty()) TextButton(onClick = onReload) { Text("إعادة المحاولة") }
                    }
                    else -> LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(visibleEntries, key = { "${it.id}:${it.url}" }) { entry ->
                            val installed = entry.id in installedIds
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surfaceRaised),
                                border = BorderStroke(1.dp, MslDesignTokens.border),
                                shape = MslDesignTokens.compactCardShape,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (!entry.iconUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = entry.iconUrl,
                                            contentDescription = null,
                                            modifier = Modifier.size(36.dp).clip(MslDesignTokens.compactCardShape),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        )
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name, color = MslDesignTokens.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOfNotNull(entry.lang?.uppercase(), entry.version, entry.site).joinToString(" • "),
                                            color = MslDesignTokens.textMuted,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Button(
                                        onClick = { onInstall(entry) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (installed) MslDesignTokens.surfaceHighest else MslDesignTokens.accent,
                                            contentColor = if (installed) MslDesignTokens.accentBright else MslDesignTokens.textPrimary,
                                        ),
                                    ) { Text(if (installed) "تحديث" else "تنزيل") }
                                }
                            }
                        }
                    }
                }
                Text(
                    "الإضافات تشغّل JavaScript تابعًا لجهة خارجية؛ ثبّت فقط ما تثق به.",
                    color = MslDesignTokens.textMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = MslDesignTokens.accentBright)) {
                Text("إغلاق")
            }
        },
    )
}
