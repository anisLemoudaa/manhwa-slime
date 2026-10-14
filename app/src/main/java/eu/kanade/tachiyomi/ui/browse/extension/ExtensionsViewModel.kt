package eu.kanade.tachiyomi.ui.browse.extension

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.interactor.GetExtensionStores
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ExtensionsViewModel(
    private val preferences: SourcePreferences,
    basePreferences: BasePreferences,
    private val extensionManager: ExtensionManager,
    private val getExtensions: GetExtensionsByType,
    private val getExtensionStores: GetExtensionStores,
    private val addExtensionStore: AddExtensionStore,
    private val context: Context,
) : ViewModel() {

    private val currentDownloads = MutableStateFlow<Map<String, InstallStep>>(hashMapOf())

    // Public so BrowseTab's search bar can observe it without subscribing to the whole state.
    val searchQuery: StateFlow<String?>
        field = MutableStateFlow(null)

    private val selectedLanguage = MutableStateFlow(ExtensionLanguageFilter.ARABIC_ENGLISH)
    val languageFilter: StateFlow<ExtensionLanguageFilter> = selectedLanguage

    // Public so the tab badge can observe it without subscribing to the whole state.
    val updatesCount = preferences.extensionUpdatesCount.changes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), 0)

    private val isRefreshing = MutableStateFlow(false)

    private fun extensionMapper(map: Map<String, InstallStep>): (Extension) -> ExtensionUiModel.Item = {
        ExtensionUiModel.Item(it, map[it.pkgName] ?: InstallStep.Idle)
    }

    @Suppress("LocalVariableName")
    private val items = combine(
        searchQuery
            .debounce(0.25.seconds)
            .map { searchQueryPredicate(it ?: "") },
        currentDownloads,
        selectedLanguage,
        getExtensions.subscribe(additionalLanguages = setOf("ar", "en")),
    ) { predicate, downloads, languageFilter, (_updates, _loaded, _available, _notLoaded) ->
        buildMap {
            val updates = _updates.filter(predicate).map(extensionMapper(downloads))
            if (updates.isNotEmpty()) {
                put(ExtensionUiModel.Header.Resource(MR.strings.ext_updates_pending), updates)
            }

            val notLoaded = _notLoaded.filter(predicate).map(extensionMapper(downloads))
            if (notLoaded.isNotEmpty()) {
                put(ExtensionUiModel.Header.Resource(MR.strings.ext_not_loaded), notLoaded)
            }

            val loaded = _loaded.filter(predicate).map(extensionMapper(downloads))
            if (loaded.isNotEmpty()) {
                put(ExtensionUiModel.Header.Resource(MR.strings.ext_installed), loaded)
            }

            val languagesWithExtensions = _available
                .filter(predicate)
                .filter { languageFilter.includes(it.lang) }
                .groupBy { it.lang }
                .toSortedMap(LocaleHelper.comparator)
                .map { (lang, exts) ->
                    ExtensionUiModel.Header.Text(LocaleHelper.getSourceDisplayName(lang, context)) to
                        exts.map(extensionMapper(downloads))
                }
            if (languagesWithExtensions.isNotEmpty()) {
                putAll(languagesWithExtensions)
            }
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    val state: StateFlow<State> = combine(
        items,
        searchQuery,
        isRefreshing,
        preferences.extensionUpdatesCount.changes(),
        basePreferences.extensionInstaller.changes(),
    ) { items, searchQuery, isRefreshing, updates, installer ->
        State(
            isLoading = items == null,
            isRefreshing = isRefreshing,
            items = items.orEmpty(),
            updates = updates,
            installer = installer,
            searchQuery = searchQuery,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    init {
        findAvailableExtensions()
    }

    fun searchQueryPredicate(query: String): (Extension) -> Boolean {
        val subqueries = query.split(",")
            .map { it.trim() }
            .filterNot { it.isBlank() }

        if (subqueries.isEmpty()) return { true }

        return { extension ->
            subqueries.any { subquery ->
                if (extension.name.contains(subquery, ignoreCase = true)) return@any true

                when (extension) {
                    is Extension.Loaded -> extension.sources.any { source ->
                        source.name.contains(subquery, ignoreCase = true) ||
                            (source as? HttpSource)?.getHomeUrl()?.contains(subquery, ignoreCase = true) == true ||
                            source.id == subquery.toLongOrNull()
                    }

                    is Extension.Available -> extension.sources.any {
                        it.name.contains(subquery, ignoreCase = true) ||
                            it.baseUrl.contains(subquery, ignoreCase = true) ||
                            it.id == subquery.toLongOrNull()
                    }

                    else -> false
                }
            }
        }
    }

    fun search(query: String?) {
        searchQuery.update { query }
    }

    fun setLanguageFilter(filter: ExtensionLanguageFilter) {
        selectedLanguage.update { filter }
    }

    fun updateAllExtensions() {
        viewModelScope.launchIO {
            state.value.items.values.flatten()
                .map { it.extension }
                .filterIsInstance<Extension.Installed>()
                .filter { it.hasUpdate }
                .forEach(::updateExtension)
        }
    }

    fun installExtension(extension: Extension.Available) {
        viewModelScope.launchIO {
            extensionManager.installExtension(extension).collectToInstallUpdate(extension)
        }
    }

    fun updateExtension(extension: Extension.Installed) {
        viewModelScope.launchIO {
            extensionManager.updateExtension(extension).collectToInstallUpdate(extension)
        }
    }

    fun cancelInstallUpdateExtension(extension: Extension) {
        extensionManager.cancelInstallUpdateExtension(extension)
        removeDownloadState(extension)
    }

    private fun addDownloadState(extension: Extension, installStep: InstallStep) {
        currentDownloads.update { it + Pair(extension.pkgName, installStep) }
    }

    private fun removeDownloadState(extension: Extension) {
        currentDownloads.update { it - extension.pkgName }
    }

    private suspend fun Flow<InstallStep>.collectToInstallUpdate(extension: Extension) =
        this
            .onEach { installStep -> addDownloadState(extension, installStep) }
            .takeWhile { installStep -> installStep != InstallStep.Installed }
            .onCompletion { removeDownloadState(extension) }
            .collect()

    fun uninstallExtension(extension: Extension.Installed) {
        extensionManager.uninstallExtension(extension)
    }

    fun findAvailableExtensions() {
        viewModelScope.launchIO {
            isRefreshing.update { true }
            try {
                ensureKeiyoushiRepository()
                extensionManager.findAvailableExtensions()

                // Fake slower refresh so it doesn't seem like it's not doing anything
                delay(1.seconds)
            } finally {
                isRefreshing.update { false }
            }
        }
    }

    private suspend fun ensureKeiyoushiRepository() {
        val knownRepositories = getExtensionStores.get().mapTo(mutableSetOf()) { it.indexUrl }
        DEFAULT_EXTENSION_REPOSITORY_URLS.forEach { repositoryUrl ->
            if (repositoryUrl !in knownRepositories && addExtensionStore(repositoryUrl).isSuccess) {
                knownRepositories += repositoryUrl
            }
        }
        if (KEIYOUSHI_INDEX_URL in knownRepositories) {
            preferences.keiyoushiRepoAutoAdded.set(true)
        }
    }

    fun trustExtension(extension: Extension.NotLoaded) {
        extensionManager.trust(extension)
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val items: ItemGroups = mutableMapOf(),
        val updates: Int = 0,
        val installer: BasePreferences.ExtensionInstaller? = null,
        val searchQuery: String? = null,
    ) {
        val isEmpty = items.isEmpty()
    }
}

typealias ItemGroups = Map<ExtensionUiModel.Header, List<ExtensionUiModel.Item>>

enum class ExtensionLanguageFilter(val label: String) {
    ARABIC_ENGLISH("العربية والإنجليزية"),
    ARABIC("المصادر العربية"),
    ENGLISH("المصادر الإنجليزية"),
    ;

    fun includes(language: String?): Boolean {
        val primaryLanguage = language.orEmpty().substringBefore('-').substringBefore('_').lowercase()
        return when (this) {
            ARABIC_ENGLISH -> primaryLanguage == "ar" || primaryLanguage == "en"
            ARABIC -> primaryLanguage == "ar"
            ENGLISH -> primaryLanguage == "en"
        }
    }
}

object ExtensionUiModel {
    sealed interface Header {
        data class Resource(val textRes: StringResource) : Header
        data class Text(val text: String) : Header
    }

    data class Item(
        val extension: Extension,
        val installStep: InstallStep,
    )
}

private const val KEIYOUSHI_INDEX_URL = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
private val DEFAULT_EXTENSION_REPOSITORY_URLS = listOf(
    KEIYOUSHI_INDEX_URL,
    "https://raw.githubusercontent.com/mojuru/cursed-manga-repo/repo/index.pb",
)
