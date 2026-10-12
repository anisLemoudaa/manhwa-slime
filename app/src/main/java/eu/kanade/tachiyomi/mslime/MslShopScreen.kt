package eu.kanade.tachiyomi.mslime

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.rounded.Info
import java.util.Locale

class MslShopScreen : Screen() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val snackbars = remember { SnackbarHostState() }
        var items by remember { mutableStateOf<List<MslShop.Item>?>(null) }
        var balance by remember { mutableStateOf<Int?>(null) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf(false) }
        var selectedCategory by remember { mutableStateOf("الكل") }
        var pendingPurchase by remember { mutableStateOf<MslShop.Item?>(null) }
        var busyItem by remember { mutableStateOf<String?>(null) }

        suspend fun reload() {
            loading = true
            error = false
            val result = withContext(Dispatchers.IO) {
                MslWallet.refresh(context) to MslShop.catalog(context)
            }
            balance = result.first
            items = result.second
            error = result.second == null
            loading = false
        }
        LaunchedEffect(context) { reload() }

        val visibleItems = items.orEmpty().filter { item ->
            item.type == "profile_frame" && (selectedCategory == "الكل" || selectedCategory == "إطارات الملف الشخصي")
        }
        CompositionLocalProviderRtl {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbars) },
                topBar = {
                    TopAppBar(
                        title = { Text("متجر Manhwa Slime", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { navigator.pop() }) {
                                Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, contentDescription = "رجوع")
                            }
                        },
                    )
                },
            ) { padding ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 164.dp),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Card(
                                shape = MslDesignTokens.cardShape,
                                colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("رصيدك الحالي", color = MslDesignTokens.textSecondary, style = MaterialTheme.typography.labelMedium)
                                        Text(balance?.let { String.format(Locale.getDefault(), "%,d", it) } ?: "—", color = MslDesignTokens.textPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    }
                                    Text("●", color = Color(0xFFFFC857), style = MaterialTheme.typography.headlineSmall)
                                    OutlinedButton(
                                        onClick = { navigator.push(MslWalletScreen(openEarnOnOpen = true)) },
                                        enabled = MslWallet.enabled,
                                    ) { Text("الحصول على العملات", textAlign = TextAlign.Center) }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("الكل", "تطوير المستوى", "إطارات الملف الشخصي", "الجرعات", "أخرى").forEach { category ->
                                    val selected = selectedCategory == category
                                    Surface(
                                        modifier = Modifier.clip(MslDesignTokens.pillShape).clickableNoIndication { selectedCategory = category },
                                        color = if (selected) MslDesignTokens.accent else MslDesignTokens.surface,
                                        shape = MslDesignTokens.pillShape,
                                    ) {
                                        Text(category, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), color = MslDesignTokens.textPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                    }
                                }
                            }
                            Text("إطارات الملف الشخصي", color = MslDesignTokens.textPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    when {
                        loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MslDesignTokens.accentBright)
                            }
                        }
                        error -> item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                                Text("تعذر تحميل المتجر. تحقق من الاتصال وحاول مرة أخرى.", color = MslDesignTokens.textSecondary, textAlign = TextAlign.Center)
                                TextButton(onClick = { scope.launch { reload() } }) { Text("إعادة المحاولة") }
                            }
                        }
                        visibleItems.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("لا توجد عناصر متاحة حالياً.", modifier = Modifier.fillMaxWidth().padding(28.dp), textAlign = TextAlign.Center, color = MslDesignTokens.textSecondary)
                        }
                        else -> items(visibleItems, key = { it.id }) { item ->
                            ShopFrameCard(
                                item = item,
                                balance = balance,
                                busy = busyItem == item.id,
                                onBuy = { pendingPurchase = item },
                                onEquip = {
                                    busyItem = item.id
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { MslShop.equip(context, item.id) }
                                        busyItem = null
                                        if (ok) {
                                            reload()
                                            snackbars.showSnackbar("تم تجهيز الإطار.")
                                        } else snackbars.showSnackbar("تعذر تجهيز الإطار. حاول مرة أخرى.")
                                    }
                                },
                                onRemove = {
                                    busyItem = item.id
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { MslShop.equip(context, null) }
                                        busyItem = null
                                        if (ok) {
                                            reload()
                                            snackbars.showSnackbar("تمت إزالة الإطار.")
                                        } else snackbars.showSnackbar("حدث خطأ، حاول مرة أخرى.")
                                    }
                                },
                            )
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Card(
                            shape = MslDesignTokens.cardShape,
                            colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MslDesignTokens.border),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(MaterialSymbols.Rounded.Info, contentDescription = null, tint = MslDesignTokens.accentBright)
                                Text("يمكنك تغيير إطار صورتك الشخصية من صفحة ملفك الشخصي بعد الشراء.", color = MslDesignTokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        pendingPurchase?.let { item ->
            val after = ((balance ?: 0) - item.price).coerceAtLeast(0)
            AlertDialog(
                onDismissRequest = { if (busyItem == null) pendingPurchase = null },
                title = { Text("تأكيد الشراء") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FramePreview(item, Modifier.fillMaxWidth().height(150.dp))
                        Text("هل تريد شراء «${item.name}» مقابل ${item.price} عملة؟")
                        Text("رصيدك الحالي: ${balance?.let { String.format(Locale.getDefault(), "%,d", it) } ?: "—"}")
                        Text("الرصيد بعد الشراء: ${String.format(Locale.getDefault(), "%,d", after)}")
                    }
                },
                confirmButton = {
                    Button(
                        enabled = busyItem == null && balance != null && balance!! >= item.price,
                        onClick = {
                            busyItem = item.id
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { MslShop.purchase(context, item.id) }
                                busyItem = null
                                pendingPurchase = null
                                when {
                                    result.ok -> {
                                        balance = result.balance ?: withContext(Dispatchers.IO) { MslWallet.refresh(context) }
                                        reload()
                                        snackbars.showSnackbar("تم شراء الإطار بنجاح.")
                                    }
                                    result.reason == "insufficient_funds" -> {
                                        balance = result.balance
                                        snackbars.showSnackbar("عملات غير كافية.")
                                    }
                                    result.reason == "owned" -> {
                                        reload()
                                        snackbars.showSnackbar("هذا الإطار مملوك مسبقاً.")
                                    }
                                    result.reason == "unavailable" -> snackbars.showSnackbar("هذا العنصر غير متاح حالياً.")
                                    else -> snackbars.showSnackbar("حدث خطأ، حاول مرة أخرى.")
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MslDesignTokens.accent),
                    ) { Text(if (busyItem == item.id) "جارٍ الشراء…" else "تأكيد الشراء") }
                },
                dismissButton = { TextButton(onClick = { pendingPurchase = null }, enabled = busyItem == null) { Text("إلغاء") } },
            )
        }
    }
}

@Composable
private fun CompositionLocalProviderRtl(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
}

@Composable
private fun ShopFrameCard(
    item: MslShop.Item,
    balance: Int?,
    busy: Boolean,
    onBuy: () -> Unit,
    onEquip: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface.copy(alpha = 0.96f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MslDesignTokens.border.copy(alpha = 0.75f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
                item.badge?.let { Badge(it, item.rarity) }
                if (item.equipped) Badge("مجهز", "equipped", Modifier.align(Alignment.CenterEnd))
                else if (item.owned) Badge("مملوك", "owned", Modifier.align(Alignment.CenterEnd))
            }
            FramePreview(item, Modifier.fillMaxWidth().height(142.dp))
            Text(item.name, color = MslDesignTokens.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, minLines = 1)
            Text(item.description, color = MslDesignTokens.textSecondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 2, minLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("●", color = Color(0xFFFFC857), style = MaterialTheme.typography.titleMedium)
                Text(String.format(Locale.getDefault(), "%,d", item.price), color = MslDesignTokens.textPrimary, fontWeight = FontWeight.Bold)
            }
            when {
                item.equipped -> OutlinedButton(onClick = onRemove, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "…" else "إزالة الإطار") }
                item.owned -> Button(onClick = onEquip, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MslDesignTokens.accent)) { Text(if (busy) "…" else "تجهيز") }
                balance != null && balance < item.price -> Button(onClick = onBuy, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("عملات غير كافية") }
                else -> Button(onClick = onBuy, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MslDesignTokens.accent)) { Text(if (busy) "جارٍ الشراء…" else "🛒  شراء") }
            }
        }
    }
}

@Composable
private fun FramePreview(item: MslShop.Item, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resourceId = remember(item.assetRef) {
        item.assetRef?.let { context.resources.getIdentifier(it, "drawable", context.packageName) } ?: 0
    }
    Box(modifier = modifier.clip(RoundedCornerShape(14.dp)).background(MslDesignTokens.backgroundRaised), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.size(62.dp).clip(CircleShape).background(MslDesignTokens.surfaceHighest),
            contentAlignment = Alignment.Center,
        ) { Text("人", color = MslDesignTokens.textSecondary, style = MaterialTheme.typography.headlineMedium) }
        if (resourceId != 0) {
            Image(
                painter = painterResource(resourceId),
                contentDescription = "معاينة ${item.name}",
                modifier = Modifier.fillMaxSize().padding(2.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("إطار", color = MslDesignTokens.textMuted)
        }
    }
}

@Composable
private fun Badge(text: String, rarity: String?, modifier: Modifier = Modifier) {
    val color = when (rarity) {
        "popular" -> Color(0xFFFFA62B)
        "new" -> Color(0xFF20B997)
        "exclusive", "legendary" -> Color(0xFF7549E8)
        "equipped" -> Color(0xFF1AA87D)
        else -> Color(0xFF596D91)
    }
    Surface(modifier = modifier, shape = MslDesignTokens.pillShape, color = color) {
        Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
