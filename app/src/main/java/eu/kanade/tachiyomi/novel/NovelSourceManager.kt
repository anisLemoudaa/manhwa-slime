package eu.kanade.tachiyomi.novel

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private data class BundledNovelSource(
    val manifest: NovelRepositoryEntry,
    val assetPath: String,
)

class NovelSourceManager(
    context: Context,
    private val store: NovelPluginStore = NovelPluginStore(context.applicationContext),
    private val network: NetworkHelper = Injekt.get(),
    private val host: NovelPluginHost = NovelPluginHost(context.applicationContext, store, network),
) {
    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val bundledSources = listOf(
        BundledNovelSource(
            NovelRepositoryEntry("cenele", "فضاء الروايات", "https://cenele.com", "ar", "1.0.0", "builtin://cenele"),
            "novel-plugins/cenele.js",
        ),
        BundledNovelSource(
            NovelRepositoryEntry("kolnovel", "ملوك الروايات", "https://kolnovel.com", "ar", "1.0.0", "builtin://kolnovel"),
            "novel-plugins/kolnovel.js",
        ),
        BundledNovelSource(
            NovelRepositoryEntry("sunovels", "شمس الروايات", "https://sunovels.com", "ar", "1.0.0", "builtin://sunovels"),
            "novel-plugins/sunovels.js",
        ),
        BundledNovelSource(
            NovelRepositoryEntry("mknov", "مملكة الروايات", "https://mknov.com", "ar", "1.0.0", "builtin://mknov"),
            "novel-plugins/mknov.js",
        ),
    )

    suspend fun repositories(): List<String> = store.repositories()

    fun installed(): List<NovelSourceRuntime> = store.installed().map(::NovelSourceRuntime)

    suspend fun loadRepository(url: String): List<NovelRepositoryEntry> = withContext(Dispatchers.IO) {
        val normalized = normalizeHttpUrl(url)
        val request = Request.Builder().url(normalized).get().build()
        network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("تعذر تحميل المستودع: HTTP ${response.code}")
            val entries = json.decodeFromString<List<NovelRepositoryEntry>>(response.body.string())
            val repos = store.repositories().toMutableList().apply {
                if (!contains(normalized)) add(normalized)
            }
            store.setRepositories(repos)
            entries
        }
    }

    suspend fun install(entry: NovelRepositoryEntry, repositoryUrl: String? = null): NovelSourceRuntime =
        installFromUrl(entry.url, entry, repositoryUrl)

    suspend fun installFromUrl(
        pluginUrl: String,
        manifest: NovelRepositoryEntry? = null,
        repositoryUrl: String? = null,
    ): NovelSourceRuntime = withContext(Dispatchers.IO) {
        val normalized = normalizeHttpUrl(pluginUrl)
        val request = Request.Builder().url(normalized).get().build()
        val code = network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("تعذر تنزيل مصدر الرواية: HTTP ${response.code}")
            response.body.string()
        }
        require(
            code.contains("module.exports") ||
                code.contains("exports.default") ||
                code.contains("Object.defineProperty(exports"),
        ) {
            "الرابط لا يبدو كملف LNReader JavaScript صالح"
        }
        val seed = manifest ?: NovelRepositoryEntry(
            id = "url-${NovelPluginStore.sha256(normalized).take(12)}",
            name = "مصدر رواية",
            site = null,
            lang = null,
            version = null,
            url = normalized,
            iconUrl = null,
        )
        val pluginFile = store.savePlugin(normalized, code)
        val info = host.loadPlugin(seed, code)
        val installed = store.installed().toMutableList()
        installed.removeAll { it.metadata.id == info.id }
        installed += NovelInstalledPlugin(normalized, info, repositoryUrl, pluginFile.name)
        store.setInstalled(installed)
        NovelSourceRuntime(installed.last { it.metadata.id == info.id })
    }

    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        val installed = store.installed().toMutableList()
        val loadedBundledIds = mutableSetOf<String>()
        bundledSources.forEach { source ->
            if (installed.any { it.metadata.id == source.manifest.id }) return@forEach
            runCatching {
                val code = appContext.assets.open(source.assetPath).bufferedReader().use { it.readText() }
                val info = host.loadPlugin(source.manifest, code)
                val file = store.savePlugin(source.manifest.url, code)
                installed += NovelInstalledPlugin(source.manifest.url, info, null, file.name)
                loadedBundledIds += info.id
            }
        }
        store.setInstalled(installed)
        installed.forEach { item ->
            if (item.metadata.id in loadedBundledIds) return@forEach
            val f = store.pluginFile(item)
            if (!f.exists()) return@forEach
            val code = f.readText()
            val meta = NovelRepositoryEntry(
                id = item.metadata.id,
                name = item.metadata.name,
                site = item.metadata.site,
                lang = item.metadata.lang,
                version = item.metadata.version,
                url = item.pluginUrl,
                iconUrl = item.metadata.iconUrl,
            )
            runCatching { host.loadPlugin(meta, code) }
        }
    }

    suspend fun popular(sourceId: String) = host.popular(sourceId, 1)
    suspend fun latest(sourceId: String) = host.latest(sourceId, 1)
    suspend fun search(sourceId: String, query: String) = host.search(sourceId, query, 1)
    suspend fun details(sourceId: String, path: String) = host.details(sourceId, path)
    suspend fun chapter(sourceId: String, path: String) = host.chapter(sourceId, path)

    fun close() = host.close()

    private fun normalizeHttpUrl(value: String): String {
        val normalized = value.trim()
        require(normalized.startsWith("http://") || normalized.startsWith("https://")) {
            "أدخل عنوان URL يبدأ بـ http:// أو https://"
        }
        return normalized
    }
}
