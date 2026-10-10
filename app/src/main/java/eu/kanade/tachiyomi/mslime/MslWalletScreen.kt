package eu.kanade.tachiyomi.mslime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MslWalletScreen : Screen() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        var transactions by remember { mutableStateOf<List<MslWallet.CoinTransaction>?>(null) }
        var loading by remember { mutableStateOf(MslWallet.enabled) }

        LaunchedEffect(context) {
            if (MslWallet.enabled) {
                loading = true
                transactions = withContext(Dispatchers.IO) { MslWallet.transactions(context) }
                loading = false
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(MR.strings.coin_wallet_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                MaterialSymbols.AutoMirroredRounded.ArrowBack,
                                contentDescription = stringResource(MR.strings.action_bar_up_description),
                            )
                        }
                    },
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (MslWallet.enabled) {
                    MslCoinWalletHeader()
                } else {
                    StatusCard(stringResource(MR.strings.coin_wallet_disabled))
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(MR.strings.coin_transactions_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MslDesignTokens.textPrimary,
                    )
                    when {
                        !MslWallet.enabled -> StatusCard(stringResource(MR.strings.coin_transactions_unavailable))
                        loading -> StatusCard(stringResource(MR.strings.coin_transactions_loading))
                        transactions == null -> StatusCard(stringResource(MR.strings.coin_transactions_unavailable))
                        transactions!!.isEmpty() -> StatusCard(stringResource(MR.strings.coin_transactions_empty))
                        else -> transactions!!.forEach { transaction ->
                            WalletTransactionRow(transaction)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MslDesignTokens.cardShape,
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MslDesignTokens.textSecondary,
        )
    }
}

@Composable
private fun WalletTransactionRow(transaction: MslWallet.CoinTransaction) {
    val title = when (transaction.kind) {
        "ad_reward" -> stringResource(MR.strings.coin_transaction_ad_reward)
        "play_purchase" -> stringResource(MR.strings.coin_transaction_purchase)
        "download" -> stringResource(MR.strings.coin_transaction_download)
        "download_refund" -> stringResource(MR.strings.coin_transaction_refund)
        else -> stringResource(MR.strings.coin_transaction_other)
    }
    val date = remember(transaction.createdAt) {
        runCatching {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(transaction.createdAt))
        }.getOrDefault(transaction.createdAt)
    }
    val amountColor = if (transaction.delta > 0) MslDesignTokens.accentBright else MaterialTheme.colorScheme.error

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MslDesignTokens.cardShape,
        colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MslDesignTokens.textPrimary,
                )
                Text(
                    text = date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MslDesignTokens.textSecondary,
                )
            }
            Text(
                text = if (transaction.delta > 0) "+${transaction.delta}" else transaction.delta.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = amountColor,
            )
        }
    }
}
