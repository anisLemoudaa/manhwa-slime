@file:Suppress("ktlint:standard:no-wildcard-imports")
@file:OptIn(ExperimentalMaterial3Api::class)

package eu.kanade.tachiyomi.novel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

@Composable
fun NovelSourcesCatalogDialog(
    entries: List<NovelRepositoryEntry>,
    installedSources: List<NovelSourceRuntime>,
    installingId: String?,
    languageFilter: String,
    loading: Boolean,
    error: String?,
    onLanguageFilter: (String) -> Unit,
    onInstall: (NovelRepositoryEntry) -> Unit,
    onRemoveSource: (String) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val languageEntries = entries.filter { entry ->
        when (languageFilter) {
            "ar" -> entry.lang.toNovelLanguageCode() == "ar"
            "en" -> entry.lang.toNovelLanguageCode() == "en"
            else -> entry.lang.toNovelLanguageCode() != null
        }
    }
    val visibleEntries = languageEntries.filter { entry ->
        query.isBlank() || listOf(entry.name, entry.site, entry.lang, entry.id)
            .filterNotNull()
            .any { it.contains(query.trim(), ignoreCase = true) }
    }
    val groupedEntries = visibleEntries.groupBy { it.repositoryUrl ?: "مستودع غير معروف" }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NovelColors.surface,
        contentColor = NovelColors.textPrimary,
        shape = MaterialTheme.shapes.extraLarge,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(NovelColors.divider),
            )
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("مصادر الروايات", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${visibleEntries.size} مصدرًا من ${languageEntries.size} المعروضة من أصل ${entries.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NovelColors.textSecondary,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "إغلاق مصادر الروايات" },
                ) { Text("×", color = NovelColors.textSecondary, fontSize = 26.dp.value.sp) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "supported" to "عربية + إنجليزية",
                    "ar" to "العربية",
                    "en" to "الإنجليزية",
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = languageFilter == key,
                        onClick = { onLanguageFilter(key) },
                        label = { Text(label, maxLines = 1) },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = NovelColors.surfaceRaised,
                            labelColor = NovelColors.textSecondary,
                            selectedContainerColor = NovelColors.accent.copy(alpha = 0.12f),
                            selectedLabelColor = NovelColors.accent,
                        ),
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = CircleShape,
                placeholder = { Text("ابحث عن مصدر…", color = NovelColors.textMuted) },
                leadingIcon = { Text("⌕", color = NovelColors.accent, fontSize = 22.dp.value.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = NovelColors.textPrimary,
                    unfocusedTextColor = NovelColors.textPrimary,
                    focusedContainerColor = NovelColors.surface,
                    unfocusedContainerColor = NovelColors.surface,
                    focusedBorderColor = NovelColors.accent,
                    unfocusedBorderColor = NovelColors.divider,
                    cursorColor = NovelColors.accent,
                ),
            )

            if (!error.isNullOrBlank()) {
                Text(error, color = NovelColors.danger, style = MaterialTheme.typography.bodySmall)
            }

            when {
                loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NovelColors.accent)
                }
                visibleEntries.isEmpty() -> Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (entries.isEmpty()) "تعذّر تحميل الفهرس أو أنه فارغ." else "لا توجد مصادر تطابق هذا البحث.",
                        color = NovelColors.textSecondary,
                    )
                    if (entries.isEmpty()) {
                        TextButton(onClick = onReload) { Text("إعادة المحاولة", color = NovelColors.accent) }
                    }
                }
                else -> LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    groupedEntries.forEach { (repositoryUrl, repoEntries) ->
                        item(key = "repository:$repositoryUrl") {
                            val repoLabel = repositoryUrl
                                .substringAfter("raw.githubusercontent.com/", repositoryUrl)
                                .split('/')
                                .take(2)
                                .joinToString("/")
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    repoLabel,
                                    Modifier.weight(1f),
                                    color = NovelColors.accent,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${repoEntries.size} مصدر",
                                    color = NovelColors.textMuted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        items(repoEntries, key = { "$repositoryUrl:${it.id}:${it.url}" }) { entry ->
                            val installed = installedSources.firstOrNull { it.id == entry.id }
                            val updateAvailable =
                                installed != null &&
                                    isNovelVersionNewer(entry.version, installed.installed.metadata.version)
                            val isInstalling = installingId == entry.id
                            Card(
                                colors = CardDefaults.cardColors(containerColor = NovelColors.surface),
                                border = BorderStroke(1.dp, NovelColors.divider),
                                shape = NovelColors.compactCardShape,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (!entry.iconUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = entry.iconUrl,
                                            contentDescription = null,
                                            modifier = Modifier.size(42.dp).clip(CircleShape),
                                            contentScale = ContentScale.Crop,
                                        )
                                    } else {
                                        Surface(
                                            modifier = Modifier.size(42.dp),
                                            shape = CircleShape,
                                            color = NovelColors.accent.copy(alpha = 0.10f),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    entry.name.take(1),
                                                    color = NovelColors.accent,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            entry.name,
                                            color = NovelColors.textPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            listOfNotNull(
                                                entry.lang?.uppercase(),
                                                entry.version,
                                                entry.site,
                                            ).joinToString(" • "),
                                            color = NovelColors.textMuted,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    when {
                                        isInstalling -> Box(
                                            Modifier.size(width = 72.dp, height = 44.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(
                                                Modifier.size(20.dp),
                                                color = NovelColors.accent,
                                                strokeWidth = 2.dp,
                                            )
                                        }
                                        installed == null -> Button(
                                            onClick = { onInstall(entry) },
                                            modifier = Modifier.heightIn(min = 44.dp),
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NovelColors.accent,
                                                contentColor = androidx.compose.ui.graphics.Color.White,
                                            ),
                                        ) { Text("تثبيت") }
                                        updateAvailable -> Button(
                                            onClick = { onInstall(entry) },
                                            modifier = Modifier.heightIn(min = 44.dp),
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NovelColors.accent,
                                                contentColor = androidx.compose.ui.graphics.Color.White,
                                            ),
                                        ) { Text("تحديث") }
                                        else -> OutlinedButton(
                                            onClick = { onRemoveSource(entry.id) },
                                            modifier = Modifier.heightIn(min = 44.dp),
                                            shape = CircleShape,
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = NovelColors.textSecondary,
                                            ),
                                            border = BorderStroke(1.dp, NovelColors.divider),
                                        ) { Text("إزالة") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Surface(
                color = NovelColors.surfaceRaised,
                shape = NovelColors.compactCardShape,
            ) {
                Text(
                    "تنبيه أمني: هذه الإضافات تشغّل JavaScript تابعًا لجهة خارجية. ثبّت مصادر تثق بها فقط.",
                    Modifier.fillMaxWidth().padding(12.dp),
                    color = NovelColors.textSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

internal fun isNovelVersionNewer(candidate: String?, installed: String?): Boolean {
    if (candidate.isNullOrBlank() || candidate == installed) return false
    if (installed.isNullOrBlank()) return true
    val candidateParts = Regex("\\d+").findAll(candidate).map { it.value.toIntOrNull() ?: 0 }.toList()
    val installedParts = Regex("\\d+").findAll(installed).map { it.value.toIntOrNull() ?: 0 }.toList()
    if (candidateParts.isEmpty() || installedParts.isEmpty()) return candidate > installed
    for (index in 0 until maxOf(candidateParts.size, installedParts.size)) {
        val candidatePart = candidateParts.getOrElse(index) { 0 }
        val installedPart = installedParts.getOrElse(index) { 0 }
        if (candidatePart != installedPart) return candidatePart > installedPart
    }
    return false
}
