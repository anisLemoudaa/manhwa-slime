#!/usr/bin/env bash
set -euo pipefail

ROOT="$(pwd)"
APP="$ROOT/app/src/main/java/eu/kanade/tachiyomi"
NOVEL="$APP/novel"
ASSETS="$ROOT/app/src/main/assets/lnhost/vendor"
HEADLESS="$ROOT/app/src/main/assets/lnhost/headless.js"

say(){ printf '\n%s\n' "$1"; }
fail(){ echo "❌ $1" >&2; exit 1; }

[[ -f "$ROOT/settings.gradle.kts" ]] || fail "شغّل السكربت من جذر المشروع /workspaces/manhwa-slime"
[[ -f "$ROOT/app/build.gradle.kts" ]] || fail "app/build.gradle.kts غير موجود"
[[ -f "$ROOT/gradle/libs.versions.toml" ]] || fail "gradle/libs.versions.toml غير موجود"

say "🧩 Manhwa Slime • Novel Section installer"

mkdir -p "$NOVEL" "$ASSETS"

# -----------------------------------------------------------------------------
# 1) QuickJS dependency
# -----------------------------------------------------------------------------
python3 - "$ROOT/gradle/libs.versions.toml" "$ROOT/app/build.gradle.kts" <<'PY'
from pathlib import Path
import sys
libp = Path(sys.argv[1])
app = Path(sys.argv[2])

s = libp.read_text()
if 'novelQuickjs = { module = "io.github.dokar3:quickjs-kt", version = "1.0.5" }' not in s:
    anchor = 'reorderable = { module = "sh.calvin.reorderable:reorderable", version.ref = "reorderable" }'
    if anchor in s:
        s = s.replace(anchor, anchor + '\nnovelQuickjs = { module = "io.github.dokar3:quickjs-kt", version = "1.0.5" }')
    else:
        raise SystemExit('لم أجد سطر reorderable داخل libs.versions.toml - أضف novelQuickjs يدويًا تحت [libraries]')
    libp.write_text(s)

s = app.read_text()
if 'implementation(libs.novelQuickjs)' not in s:
    marker = 'implementation(projects.sourceLocal)'
    if marker in s:
        s = s.replace(marker, marker + '\n    // Manhwa Slime Novel runtime: LNReader-compatible JavaScript sources.\n    implementation(libs.novelQuickjs)')
    else:
        raise SystemExit('لم أجد projects.sourceLocal داخل app/build.gradle.kts')
    app.write_text(s)
PY

# -----------------------------------------------------------------------------
# 2) Fetch the LNReader-compatible headless runtime + vendor JS from Reikai.
#    Reikai is Apache-2.0; these files are kept as third-party runtime assets.
# -----------------------------------------------------------------------------
REIKAI_COMMIT="c7c16c91776ab53635b33452a2ebfd5dd9a42e0b"
RAW="https://raw.githubusercontent.com/unseensnick/Reikai/${REIKAI_COMMIT}"

curl -fsSL "$RAW/app/src/main/assets/lnhost/headless.js" -o "$HEADLESS"
for f in dayjs.min.js htmlparser2.min.js cheerio.min.js protobuf.min.js noble-ciphers.min.js; do
  curl -fsSL "$RAW/app/src/main/assets/lnhost/vendor/$f" -o "$ASSETS/$f"
done

cat > "$ROOT/NOTICE_LN_RUNTIME.md" <<'EOF'
# LN runtime attribution

The `app/src/main/assets/lnhost/` runtime assets include material adapted from Reikai's LNReader-compatible plugin host.

Reikai is licensed under the Apache License, Version 2.0.
Reikai repository: https://github.com/unseensnick/Reikai
Pinned revision: c7c16c91776ab53635b33452a2ebfd5dd9a42e0b

The upstream LNReader plugin format is provided by the LNReader project and its community plugin repository.
LNReader core and lnreader-plugins are MIT licensed; individual third-party source sites retain their own rights and terms.
EOF

# -----------------------------------------------------------------------------
# 3) Novel runtime models
# -----------------------------------------------------------------------------
cat > "$NOVEL/NovelModels.kt" <<'EOF'
package eu.kanade.tachiyomi.novel

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class NovelRepositoryEntry(
    val id: String,
    val name: String,
    val site: String? = null,
    val lang: String? = null,
    val version: String? = null,
    val url: String,
    val iconUrl: String? = null,
)

@Serializable
data class NovelPluginInfo(
    val id: String,
    val name: String,
    val version: String? = null,
    val site: String? = null,
    val lang: String? = null,
    val iconUrl: String? = null,
    val filters: JsonObject? = null,
    val pluginSettings: JsonObject? = null,
)

@Serializable
data class NovelItem(
    val name: String,
    val path: String,
    val cover: String? = null,
)

@Serializable
data class NovelChapter(
    val name: String,
    val path: String,
    val releaseTime: String? = null,
    val chapterNumber: Double? = null,
    val page: String? = null,
)

@Serializable
data class NovelDetails(
    val path: String,
    val name: String? = null,
    val cover: String? = null,
    val genres: String? = null,
    val summary: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val chapters: List<NovelChapter>? = null,
    val totalPages: Int = 1,
)

@Serializable
data class NovelInstalledPlugin(
    val pluginUrl: String,
    val metadata: NovelPluginInfo,
    val repositoryUrl: String? = null,
    val jsFile: String,
)

data class NovelSourceRuntime(
    val installed: NovelInstalledPlugin,
) {
    val id: String get() = installed.metadata.id
    val name: String get() = installed.metadata.name
    val lang: String get() = installed.metadata.lang.orEmpty().ifBlank { "متعدد" }
    val site: String get() = installed.metadata.site.orEmpty()
    val iconUrl: String? get() = installed.metadata.iconUrl
}
EOF

# -----------------------------------------------------------------------------
# 4) Persistent store (plain JSON in app-private storage)
# -----------------------------------------------------------------------------
cat > "$NOVEL/NovelPluginStore.kt" <<'EOF'
package eu.kanade.tachiyomi.novel

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

class NovelPluginStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "msl_novel_plugins").apply { mkdirs() }
    private val reposFile = File(dir, "repositories.json")
    private val installedFile = File(dir, "installed.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun repositories(): List<String> = read(reposFile, emptyList())
    fun setRepositories(values: List<String>) = write(reposFile, values.distinct())

    fun installed(): List<NovelInstalledPlugin> = read(installedFile, emptyList())
    fun setInstalled(values: List<NovelInstalledPlugin>) = write(installedFile, values)

    fun pluginFile(url: String): File = File(dir, "${sha256(url)}.js")

    fun pluginFile(item: NovelInstalledPlugin): File = File(dir, item.jsFile)

    fun savePlugin(url: String, js: String): File {
        val f = pluginFile(url)
        f.writeText(js)
        return f
    }

    fun deletePlugin(url: String) { pluginFile(url).delete() }

    private inline fun <reified T> read(file: File, fallback: T): T {
        if (!file.exists()) return fallback
        return runCatching { json.decodeFromString<T>(file.readText()) }.getOrDefault(fallback)
    }

    private inline fun <reified T> write(file: File, value: T) {
        file.writeText(json.encodeToString(value))
    }

    companion object {
        fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
EOF

# -----------------------------------------------------------------------------
# 5) QuickJS host + OkHttp bridge for LNReader plugins
# -----------------------------------------------------------------------------
cat > "$NOVEL/NovelPluginHost.kt" <<'EOF'
package eu.kanade.tachiyomi.novel

import android.content.Context
import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelPluginHost(
    context: Context,
    private val store: NovelPluginStore = NovelPluginStore(context.applicationContext),
    networkHelper: NetworkHelper = Injekt.get(),
) {
    private val appContext = context.applicationContext
    private val client = networkHelper.client
    private val deviceUserAgent = runCatching {
        android.webkit.WebSettings.getDefaultUserAgent(appContext)
    }.getOrDefault("")

    private val engineExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "MSL-NovelJS") }
    private val engineDispatcher: CoroutineDispatcher = engineExecutor.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var quickJs: QuickJs? = null
    private val pluginIdsByUrl = ConcurrentHashMap<String, String>()
    private val storage = ConcurrentHashMap<String, String>()

    private fun loadStorage(pluginId: String, key: String): String? = synchronized(storage) {
        storage["$pluginId::$key"]
    }

    private fun setStorage(pluginId: String, key: String, value: String?) = synchronized(storage) {
        val full = "$pluginId::$key"
        if (value == null) {
            storage.remove(full)
        } else {
            storage[full] = value
        }
    }

    private fun asset(path: String): String =
        appContext.assets.open(path).bufferedReader().use { it.readText() }

    private suspend fun engine(): QuickJs {
        quickJs?.let { return it }
        val q = QuickJs.create(engineDispatcher)
        q.function("__lnLog") { args -> null }
        q.function("__lnGetStorage") { args ->
            loadStorage(args.getOrNull(0) as? String ?: "", args.getOrNull(1) as? String ?: "")
        }
        q.function("__lnSetStorage") { args ->
            setStorage(
                args.getOrNull(0) as? String ?: "",
                args.getOrNull(1) as? String ?: "",
                args.getOrNull(2) as? String,
            )
            null
        }
        q.asyncFunction("__lnDelay") { args ->
            delay(((args.getOrNull(0) as? Number)?.toLong() ?: 0L).coerceIn(0L, 30_000L))
            null
        }
        q.asyncFunction("__lnFetch") { args ->
            withContext(Dispatchers.IO) {
                runFetch(
                    args.getOrNull(0) as? String ?: "",
                    args.getOrNull(1) as? String ?: "{}",
                )
            }
        }
        q.evaluate<Any?>("(function(){globalThis.self=globalThis;globalThis.window=globalThis;})()")
        q.evaluate<Any?>(asset("lnhost/vendor/dayjs.min.js"))
        q.evaluate<Any?>(asset("lnhost/vendor/htmlparser2.min.js"))
        q.evaluate<Any?>(asset("lnhost/vendor/cheerio.min.js"))
        q.evaluate<Any?>(asset("lnhost/vendor/protobuf.min.js"))
        q.evaluate<Any?>(asset("lnhost/vendor/noble-ciphers.min.js"))
        q.evaluate<Any?>(asset("lnhost/headless.js"))
        quickJs = q
        return q
    }

    suspend fun loadPlugin(meta: NovelRepositoryEntry, code: String): NovelPluginInfo = withTimeout(30_000L) {
        mutex.withLock {
            val q = engine()
            val jsonResult = q.evaluate<String>(
                "JSON.stringify(globalThis.__lnLoadPlugin(" +
                    "${jsString(meta.id)},${jsString(code)},${jsString(meta.iconUrl ?: "")},${jsString(meta.lang ?: "")}" +
                    "))",
            )
            val info = JSON.decodeFromString<NovelPluginInfo>(jsonResult)
            pluginIdsByUrl[meta.url] = info.id
            info
        }
    }

    suspend fun popular(pluginId: String, page: Int): List<NovelItem> = call(pluginId, "popularNovels", listOf(JsonPrimitive(page), buildJsonObject {
        put("showLatestNovels", false)
    })) { raw -> JSON.decodeFromJsonElement(ListSerializer(NovelItem.serializer()), raw) }

    suspend fun latest(pluginId: String, page: Int): List<NovelItem> = call(pluginId, "popularNovels", listOf(JsonPrimitive(page), buildJsonObject {
        put("showLatestNovels", true)
    })) { raw -> JSON.decodeFromJsonElement(ListSerializer(NovelItem.serializer()), raw) }

    suspend fun search(pluginId: String, query: String, page: Int): List<NovelItem> =
        call(pluginId, "searchNovels", listOf(JsonPrimitive(query), JsonPrimitive(page))) { raw ->
            JSON.decodeFromJsonElement(ListSerializer(NovelItem.serializer()), raw)
        }

    suspend fun details(pluginId: String, path: String): NovelDetails =
        call(pluginId, "parseNovel", listOf(JsonPrimitive(path))) { raw ->
            JSON.decodeFromJsonElement(NovelDetails.serializer(), raw)
        }

    suspend fun chapter(pluginId: String, path: String): String =
        call(pluginId, "parseChapter", listOf(JsonPrimitive(path))) { raw -> raw.jsonPrimitive.contentOrNull.orEmpty() }

    private suspend fun <T> call(
        pluginId: String,
        method: String,
        args: List<JsonElement>,
        decode: (JsonElement) -> T,
    ): T = withTimeout(180_000L) {
        mutex.withLock {
            val q = engine()
            val argsJson = JSON.encodeToString(ListSerializer(JsonElement.serializer()), args)
            q.evaluate<Any?>(
                "globalThis.__lnPending='__pending__';" +
                    "globalThis.__lnCallMethod(${jsString(pluginId)},${jsString(method)},${jsString(argsJson)})" +
                    ".then(function(r){globalThis.__lnPending=r;},function(e){globalThis.__lnPending=JSON.stringify({ok:false,error:String((e&&e.message)||e)});});",
            )
            waitForPending(q)
            val resultJson = q.evaluate<String>("String(globalThis.__lnPending)")
            val result = JSON.decodeFromString<NovelCallResult>(resultJson)
            if (!result.ok) error(result.error ?: "$method failed")
            decode(result.value ?: JsonNull)
        }
    }

    private suspend fun waitForPending(q: QuickJs) {
        repeat(1800) {
            val state = q.evaluate<String>("String(globalThis.__lnPending)")
            if (state != "__pending__") return
            delay(100)
        }
        error("Plugin request timed out")
    }

    private fun jsString(value: String): String = JSON.encodeToString(String.serializer(), value)

    private fun runFetch(url: String, optsJson: String): String {
        return try {
            val opts = JSON.decodeFromString<NovelFetchOptions>(optsJson)
            val request = Request.Builder().url(url).apply {
                opts.headers?.forEach { (k, v) -> header(k, v) }
                if (opts.headers?.keys?.none { it.equals("User-Agent", true) } != false && deviceUserAgent.isNotBlank()) {
                    header("User-Agent", deviceUserAgent)
                }
                val method = opts.method?.uppercase() ?: "GET"
                val body = when {
                    opts.bodyBase64 != null -> Base64.decode(opts.bodyBase64, Base64.NO_WRAP).toRequestBody()
                    opts.multipart != null -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                        opts.multipart.forEach { pair -> addFormDataPart(pair.getOrElse(0) { "" }, pair.getOrElse(1) { "" }) }
                    }.build()
                    opts.body != null && method !in setOf("GET", "HEAD") -> opts.body.toRequestBody()
                    method in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody()
                    else -> null
                }
                method(method, body)
            }.build()
            client.newCall(request).execute().use { response ->
                val responseBytes = response.body.bytes()
                val binary = opts.binary == true
                buildJsonObject {
                    put("status", response.code)
                    put("statusText", response.message)
                    put("url", response.request.url.toString())
                    put("body", if (binary) "" else responseBytes.toString(Charsets.UTF_8))
                    put("bodyBase64", if (binary) JsonPrimitive(Base64.encodeToString(responseBytes, Base64.NO_WRAP)) else JsonNull)
                    put("headers", buildJsonObject {
                        response.headers.names().forEach { h -> put(h.lowercase(), response.header(h).orEmpty()) }
                    })
                }.toString()
            }
        } catch (t: Throwable) {
            buildJsonObject {
                put("status", 0)
                put("statusText", "")
                put("url", url)
                put("body", "")
                put("bodyBase64", JsonNull)
                put("headers", buildJsonObject { })
                put("error", t.message ?: t.javaClass.simpleName)
            }.toString()
        }
    }

    fun close() {
        quickJs?.close()
        quickJs = null
        engineExecutor.shutdownNow()
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    }
}

@Serializable
data class NovelCallResult(
    val ok: Boolean,
    val value: JsonElement? = null,
    val error: String? = null,
)

@Serializable
data class NovelFetchOptions(
    val method: String? = null,
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val bodyBase64: String? = null,
    val multipart: List<List<String>>? = null,
    val binary: Boolean? = null,
)
EOF

# -----------------------------------------------------------------------------
# 6) Repository + source manager
# -----------------------------------------------------------------------------
cat > "$NOVEL/NovelSourceManager.kt" <<'EOF'
package eu.kanade.tachiyomi.novel

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelSourceManager(
    context: Context,
    private val store: NovelPluginStore = NovelPluginStore(context.applicationContext),
    private val network: NetworkHelper = Injekt.get(),
    private val host: NovelPluginHost = NovelPluginHost(context.applicationContext, store, network),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
                code.contains("Object.defineProperty(exports")
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
        store.installed().forEach { item ->
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
EOF

# -----------------------------------------------------------------------------
# 7) Injekt-free app singleton holder. The UI can safely use one manager per process.
# -----------------------------------------------------------------------------
cat > "$NOVEL/NovelManagerHolder.kt" <<'EOF'
package eu.kanade.tachiyomi.novel

import android.content.Context

object NovelManagerHolder {
    @Volatile private var manager: NovelSourceManager? = null

    fun get(context: Context): NovelSourceManager {
        return manager ?: synchronized(this) {
            manager ?: NovelSourceManager(context.applicationContext).also { manager = it }
        }
    }
}
EOF

# -----------------------------------------------------------------------------
# 8) Novel UI: source/repo management, browse/search, detail, chapter reader
# -----------------------------------------------------------------------------
cat > "$NOVEL/NovelSectionScreen.kt" <<'EOF'
@file:OptIn(ExperimentalMaterial3Api::class)

package eu.kanade.tachiyomi.novel

import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch

private data class NovelUiState(
    val sources: List<NovelSourceRuntime> = emptyList(),
    val selectedSource: String? = null,
    val novels: List<NovelItem> = emptyList(),
    val loading: Boolean = true,
    val query: String = "",
    val repoDialog: Boolean = false,
    val repoUrl: String = "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json",
    val repoEntries: List<NovelRepositoryEntry> = emptyList(),
    val inputMode: NovelInputMode = NovelInputMode.REPOSITORY,
    val error: String? = null,
    val info: String? = null,
)

private enum class NovelInputMode { REPOSITORY, DIRECT_URL }

class NovelSectionScreen : Screen() {
    @Composable
    override fun Content() {
        NovelSectionContent()
    }
}

@Composable
fun NovelSectionContent() {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val manager = remember { NovelManagerHolder.get(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(NovelUiState()) }

    fun reload(sourceId: String? = state.selectedSource) {
        scope.launch {
            val sources = runCatching { manager.ensureLoaded(); manager.installed() }.getOrDefault(emptyList())
            val sid = sourceId ?: sources.firstOrNull()?.id
            state = state.copy(sources = sources, selectedSource = sid, loading = true, error = null, info = null)
            if (sid != null) {
                val data = runCatching { manager.latest(sid) }
                state = state.copy(novels = data.getOrDefault(emptyList()), loading = false, error = data.exceptionOrNull()?.message)
            } else {
                state = state.copy(novels = emptyList(), loading = false)
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    NovelShell(
        state = state,
        onSearch = { q ->
            state = state.copy(query = q, info = null)
            val sid = state.selectedSource
            if (sid != null && q.trim().isNotEmpty()) {
                scope.launch {
                    val r = runCatching { manager.search(sid, q.trim()) }
                    state = state.copy(novels = r.getOrDefault(emptyList()), error = r.exceptionOrNull()?.message)
                }
            } else if (q.trim().isEmpty()) {
                reload()
            }
        },
        onSelectSource = { sid -> reload(sid) },
        onAddRepository = { state = state.copy(repoDialog = true, error = null, info = null) },
        onInputModeChange = { state = state.copy(inputMode = it, repoEntries = emptyList(), error = null, info = null) },
        onRepoUrlChange = { state = state.copy(repoUrl = it, error = null, info = null) },
        onInstall = { entry ->
            scope.launch {
                val r = runCatching { manager.install(entry, state.repoUrl.trim()) }
                if (r.isFailure) state = state.copy(error = r.exceptionOrNull()?.message)
                else {
                    state = state.copy(repoDialog = false, info = "تم تثبيت ${r.getOrThrow().name}")
                    reload(r.getOrThrow().id)
                }
            }
        },
        onInstallDirect = {
            scope.launch {
                val url = state.repoUrl.trim()
                val r = runCatching { manager.installFromUrl(url, repositoryUrl = null) }
                if (r.isFailure) state = state.copy(error = r.exceptionOrNull()?.message)
                else {
                    state = state.copy(repoDialog = false, info = "تم تثبيت ${r.getOrThrow().name}")
                    reload(r.getOrThrow().id)
                }
            }
        },
        onOpen = { item ->
            val sid = state.selectedSource
            if (sid != null) navigator.push(NovelDetailsScreen(sid, item.path, item.name))
        },
        onCloseRepo = { state = state.copy(repoDialog = false, error = null) },
        onLoadRepo = {
            scope.launch {
                val result = runCatching { manager.loadRepository(state.repoUrl) }
                state = state.copy(
                    repoEntries = result.getOrDefault(emptyList()),
                    error = result.exceptionOrNull()?.message,
                    info = result.getOrNull()?.let { "تم العثور على ${it.size} مصدرًا" },
                )
            }
        },
    )
}

@Composable
private fun NovelShell(
    state: NovelUiState,
    onSearch: (String) -> Unit,
    onSelectSource: (String) -> Unit,
    onAddRepository: () -> Unit,
    onInputModeChange: (NovelInputMode) -> Unit,
    onRepoUrlChange: (String) -> Unit,
    onInstall: (NovelRepositoryEntry) -> Unit,
    onInstallDirect: () -> Unit,
    onOpen: (NovelItem) -> Unit,
    onCloseRepo: () -> Unit,
    onLoadRepo: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("الروايات", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("مصادر LNReader + روابط JS مباشرة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = onAddRepository) { Icon(Icons.Default.Settings, "إدارة المصادر") }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    AssistChip(onClick = onAddRepository, label = { Text("إضافة مصدر") }, leadingIcon = { Icon(Icons.Default.Add, null) })
                }
                items(state.sources) { source ->
                    FilterChip(
                        selected = state.selectedSource == source.id,
                        onClick = { onSelectSource(source.id) },
                        label = { Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = onSearch,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("ابحث عن رواية...") },
            )
            Spacer(Modifier.height(12.dp))

            state.info?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp)) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.sources.isEmpty() -> EmptyNovelSources(onAddRepository)
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(145.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.novels) { item -> NovelCard(item, onOpen) }
                }
            }
        }

        if (state.repoDialog) {
            AlertDialog(
                onDismissRequest = onCloseRepo,
                title = { Text("إضافة مصادر الروايات") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("اختر مستودع LNReader أو ألصق رابط plugin.js مباشر.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FilterChip(
                                selected = state.inputMode == NovelInputMode.REPOSITORY,
                                onClick = { onInputModeChange(NovelInputMode.REPOSITORY) },
                                label = { Text("مستودع") },
                            )
                            FilterChip(
                                selected = state.inputMode == NovelInputMode.DIRECT_URL,
                                onClick = { onInputModeChange(NovelInputMode.DIRECT_URL) },
                                label = { Text("رابط مباشر") },
                            )
                        }
                        OutlinedTextField(
                            value = state.repoUrl,
                            onValueChange = onRepoUrlChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("URL") },
                        )
                        if (state.inputMode == NovelInputMode.REPOSITORY) {
                            Button(onClick = onLoadRepo, modifier = Modifier.fillMaxWidth()) { Text("تحميل المستودع") }
                            if (state.repoEntries.isNotEmpty()) {
                                HorizontalDivider()
                                Text("المصادر المتاحة: ${state.repoEntries.size}", fontWeight = FontWeight.SemiBold)
                                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                    items(state.repoEntries) { entry ->
                                        ListItem(
                                            headlineContent = { Text(entry.name) },
                                            supportingContent = { Text(listOfNotNull(entry.lang, entry.version).joinToString(" • ")) },
                                            trailingContent = { Button(onClick = { onInstall(entry) }) { Text("تثبيت") } },
                                        )
                                    }
                                }
                            }
                        } else {
                            Button(onClick = onInstallDirect, modifier = Modifier.fillMaxWidth()) { Text("تنزيل وتثبيت المصدر") }
                        }
                        Text(
                            "تنبيه: المصدر يشغّل JavaScript تابعًا لجهة خارجية داخل محرك المصادر. ثبّت الروابط التي تثق بها فقط.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = onCloseRepo) { Text("إغلاق") } },
            )
        }
    }
}

@Composable
private fun EmptyNovelSources(onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("لا توجد مصادر روايات مثبتة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("أضف مستودعًا ثم ثبّت المصادر التي تريدها.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAdd) { Text("إضافة مستودع") }
    }
}

@Composable
private fun NovelCard(item: NovelItem, onOpen: (NovelItem) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(item) },
        shape = RoundedCornerShape(18.dp),
    ) {
        Column {
            AsyncImage(model = item.cover, contentDescription = item.name, modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)))
            Text(item.name, modifier = Modifier.padding(10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        }
    }
}

class NovelDetailsScreen(
    private val sourceId: String,
    private val path: String,
    private val fallbackName: String,
) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { NovelManagerHolder.get(context) }
        var details by remember { mutableStateOf<NovelDetails?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            val r = runCatching { manager.details(sourceId, path) }
            details = r.getOrNull()
            error = r.exceptionOrNull()?.message
        }
        Scaffold(
            topBar = { TopAppBar(title = { Text(details?.name ?: fallbackName) }, navigationIcon = { IconButton(onClick = { navigator.pop() }) { Text("‹", fontSize = 32.sp) } }) },
        ) { padding ->
            val d = details
            if (d == null) {
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    if (error == null) CircularProgressIndicator() else Text(error!!, color = MaterialTheme.colorScheme.error)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 30.dp)) {
                    item {
                        AsyncImage(model = d.cover, contentDescription = d.name, modifier = Modifier.fillMaxWidth().height(240.dp))
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(d.name ?: fallbackName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            val meta = listOfNotNull(
                                d.author?.takeIf { it.isNotBlank() },
                                d.status?.takeIf { it.isNotBlank() },
                                d.genres?.takeIf { it.isNotBlank() },
                            ).joinToString(" • ")
                            if (meta.isNotBlank()) Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!d.summary.isNullOrBlank()) Text(d.summary!!, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("الفصول", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    items(d.chapters.orEmpty()) { chapter ->
                        ListItem(
                            modifier = Modifier.clickable { navigator.push(NovelReaderScreen(sourceId, chapter.path, chapter.name)) },
                            headlineContent = { Text(chapter.name) },
                            supportingContent = { if (!chapter.releaseTime.isNullOrBlank()) Text(chapter.releaseTime!!) },
                        )
                    }
                }
            }
        }
    }
}

class NovelReaderScreen(
    private val sourceId: String,
    private val chapterPath: String,
    private val chapterName: String,
) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { NovelManagerHolder.get(context) }
        val isDark = isSystemInDarkTheme()
        var html by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            val r = runCatching { manager.chapter(sourceId, chapterPath) }
            html = r.getOrNull()
            error = r.exceptionOrNull()?.message
        }
        Scaffold(
            topBar = { TopAppBar(title = { Text(chapterName, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(onClick = { navigator.pop() }) { Text("‹", fontSize = 32.sp) } }) },
        ) { padding ->
            when {
                html == null && error == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
                error != null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { Text(error!!, color = MaterialTheme.colorScheme.error) }
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    factory = {
                        WebView(it).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = false
                            settings.domStorageEnabled = false
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                        }
                    },
                    update = { webView ->
                        val bg = if (isDark) "#111111" else "#ffffff"
                        val fg = if (isDark) "#eeeeee" else "#171717"
                        val style = "<style>body{font-family:sans-serif;font-size:18px;line-height:1.9;padding:18px;margin:0;background:$bg;color:$fg}img{max-width:100%;height:auto}a{color:inherit}</style>"
                        webView.loadDataWithBaseURL(null, style + html.orEmpty(), "text/html", "UTF-8", null)
                    },
                )
            }
        }
    }
}
EOF

# -----------------------------------------------------------------------------
# 9) Patch HomeScreen with the new top-level Manga/Novels switch.
# -----------------------------------------------------------------------------
python3 - "$ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1])
s=p.read_text()

if 'HomeModeContent' in s:
    print('HomeScreen.kt already patched - skipping')
    raise SystemExit(0)

anchor_imp='import eu.kanade.tachiyomi.ui.library.LibraryTab\n'
if anchor_imp not in s:
    raise SystemExit('LibraryTab import anchor not found in HomeScreen.kt')
needed=[
 'eu.kanade.tachiyomi.novel.NovelSectionContent',
 'androidx.compose.animation.AnimatedContent','androidx.compose.animation.animateColorAsState',
 'androidx.compose.animation.fadeIn','androidx.compose.animation.fadeOut','androidx.compose.animation.togetherWith',
 'androidx.compose.foundation.background','androidx.compose.foundation.clickable',
 'androidx.compose.foundation.layout.Arrangement','androidx.compose.foundation.layout.Box',
 'androidx.compose.foundation.layout.Column','androidx.compose.foundation.layout.Row',
 'androidx.compose.foundation.layout.fillMaxHeight','androidx.compose.foundation.layout.fillMaxSize',
 'androidx.compose.foundation.layout.fillMaxWidth','androidx.compose.foundation.layout.height',
 'androidx.compose.foundation.layout.padding','androidx.compose.foundation.shape.RoundedCornerShape',
 'androidx.compose.material3.MaterialTheme','androidx.compose.material3.Surface','androidx.compose.material3.Text',
 'androidx.compose.runtime.getValue','androidx.compose.runtime.mutableStateOf','androidx.compose.runtime.remember','androidx.compose.runtime.setValue',
 'androidx.compose.ui.Alignment','androidx.compose.ui.Modifier','androidx.compose.ui.draw.clip',
 'androidx.compose.ui.graphics.Color','androidx.compose.ui.text.font.FontWeight',
 'androidx.compose.ui.unit.dp','androidx.compose.ui.unit.sp',
]
add=''
for n in needed:
    line='import '+n+'\n'
    pkg=n.rsplit('.',1)[0]
    if line not in s and ('import '+pkg+'.*\n') not in s:
        add+=line
s=s.replace(anchor_imp, anchor_imp+add, 1)

anchor_sb='''                var showBottomBar by remember { mutableStateOf(true) }\n'''
if anchor_sb not in s:
    raise SystemExit('showBottomBar anchor not found in HomeScreen.kt')
s=s.replace('''                var showBottomBar by remember { mutableStateOf(true) }\n''','''                var showBottomBar by remember { mutableStateOf(true) }\n                var mediaMode by remember { mutableStateOf(MediaMode.MANGA) }\n''')

old='''                        HomeTabContent()\n                    }\n                } else {\n'''
new='''                        HomeModeContent(\n                            mode = mediaMode,\n                            onModeChange = { mediaMode = it },\n                        )\n                    }\n                } else {\n'''
if old not in s:
    raise SystemExit('HomeScreen tablet block لم يطابق الملف الحالي')
s=s.replace(old,new,1)
old2='''                        Box(\n                            modifier = Modifier\n                                .fillMaxSize()\n                                .padding(contentPadding),\n                        ) {\n                            HomeTabContent()\n                        }\n'''
new2='''                        Box(\n                            modifier = Modifier\n                                .fillMaxSize()\n                                .padding(contentPadding),\n                        ) {\n                            HomeModeContent(\n                                mode = mediaMode,\n                                onModeChange = { mediaMode = it },\n                            )\n                        }\n'''
if old2 not in s:
    raise SystemExit('HomeScreen phone block لم يطابق الملف الحالي')
s=s.replace(old2,new2,1)

needle='''    @Composable\n    private fun HomeTabContent() {\n'''
insert='''    private enum class MediaMode { MANGA, NOVELS }\n\n    @Composable\n    private fun HomeModeContent(\n        mode: MediaMode,\n        onModeChange: (MediaMode) -> Unit,\n    ) {\n        Column(Modifier.fillMaxSize()) {\n            MediaModeSwitcher(mode = mode, onModeChange = onModeChange)\n            AnimatedContent(\n                targetState = mode,\n                transitionSpec = { fadeIn() togetherWith fadeOut() },\n                label = "mediaModeContent",\n                modifier = Modifier.weight(1f),\n            ) { target ->\n                when (target) {\n                    MediaMode.MANGA -> HomeTabContent()\n                    MediaMode.NOVELS -> NovelSectionContent()\n                }\n            }\n        }\n    }\n\n    @Composable\n    private fun MediaModeSwitcher(\n        mode: MediaMode,\n        onModeChange: (MediaMode) -> Unit,\n    ) {\n        Surface(\n            modifier = Modifier\n                .fillMaxWidth()\n                .padding(horizontal = 16.dp, vertical = 10.dp),\n            shape = RoundedCornerShape(24.dp),\n            tonalElevation = 3.dp,\n        ) {\n            Row(\n                modifier = Modifier\n                    .fillMaxWidth()\n                    .height(48.dp)\n                    .padding(4.dp),\n                horizontalArrangement = Arrangement.spacedBy(4.dp),\n            ) {\n                MediaModeTab(\n                    title = "المانهوا",\n                    selected = mode == MediaMode.MANGA,\n                    modifier = Modifier.weight(1f),\n                    onClick = { onModeChange(MediaMode.MANGA) },\n                )\n                MediaModeTab(\n                    title = "الروايات",\n                    selected = mode == MediaMode.NOVELS,\n                    modifier = Modifier.weight(1f),\n                    onClick = { onModeChange(MediaMode.NOVELS) },\n                )\n            }\n        }\n    }\n\n    @Composable\n    private fun MediaModeTab(\n        title: String,\n        selected: Boolean,\n        modifier: Modifier,\n        onClick: () -> Unit,\n    ) {\n        val background by animateColorAsState(\n            if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,\n            label = "mediaModeTabBackground",\n        )\n        val contentColor by animateColorAsState(\n            if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,\n            label = "mediaModeTabColor",\n        )\n        Box(\n            modifier = modifier\n                .fillMaxHeight()\n                .clip(RoundedCornerShape(20.dp))\n                .background(background)\n                .clickable(onClick = onClick),\n            contentAlignment = Alignment.Center,\n        ) {\n            Text(\n                title,\n                color = contentColor,\n                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,\n                fontSize = 14.sp,\n            )\n        }\n    }\n\n'''+needle
if needle not in s:
    raise SystemExit('HomeTabContent anchor not found')
s=s.replace(needle,insert,1)
p.write_text(s)
PY

# -----------------------------------------------------------------------------
# 10) Make sure Java/Kotlin formatting whitespace is sane.
# -----------------------------------------------------------------------------
git diff --check

say "✅ تم تجهيز قسم الروايات + مشغّل LNReader + مستودعات URL + تبديل علوي متحرك."
echo ""
echo "📌 الملفات الجديدة:"
find "$NOVEL" -maxdepth 1 -type f -printf '%P\n' | sort
echo ""
echo "📦 ملفات runtime:"
find "$ROOT/app/src/main/assets/lnhost" -maxdepth 2 -type f -printf '%P\n' | sort
echo ""
echo "⚠️ لم يتم عمل commit أو push."
echo "⚠️ يجب تنفيذ build الآن قبل أي commit."
