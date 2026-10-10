@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.kanade.tachiyomi.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import coil3.compose.AsyncImage
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.mslime.MslDesignTokens
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.library.LibraryViewModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.EmptyScreen

class LibraryCategoryBrowserScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val tabNavigator = LocalTabNavigator.current
        val libraryViewModel = metroViewModel<LibraryViewModel>()
        val state = libraryViewModel.state.collectAsStateWithLifecycle().value
        val categories = state.displayedCategories

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("التصنيفات") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, contentDescription = "رجوع")
                        }
                    },
                )
            },
        ) { contentPadding ->
            when {
                state.isLoading -> androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
                categories.isEmpty() -> EmptyScreen(
                    stringRes = MR.strings.information_empty_library,
                    modifier = Modifier.padding(contentPadding),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(categories, key = { _, category -> category.id }) { index, category ->
                        val items = state.getItemsForCategory(category)
                        val cover = items.firstOrNull()?.libraryManga?.manga?.thumbnailUrl
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(184.dp)
                                .clickable {
                                    libraryViewModel.updateActiveCategoryIndex(index)
                                    tabNavigator.current = LibraryTab
                                    navigator.pop()
                                },
                            shape = MslDesignTokens.cardShape,
                            colors = CardDefaults.cardColors(containerColor = MslDesignTokens.surface),
                        ) {
                            Box(Modifier.fillMaxSize().clip(MslDesignTokens.cardShape)) {
                                if (cover != null) {
                                    AsyncImage(
                                        model = cover,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                Box(
                                    Modifier.fillMaxSize().background(
                                        Brush.verticalGradient(
                                            listOf(Color.Transparent, MslDesignTokens.background.copy(alpha = 0.95f)),
                                        ),
                                    ),
                                )
                                Column(
                                    modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        category.name.ifBlank { "المكتبة" },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${items.size} عنوان",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MslDesignTokens.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
