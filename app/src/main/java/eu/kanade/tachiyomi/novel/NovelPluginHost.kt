@file:Suppress("ktlint:standard:no-wildcard-imports")

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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
                    "${jsString(
                        meta.id,
                    )},${jsString(code)},${jsString(meta.iconUrl ?: "")},${jsString(meta.lang ?: "")}" +
                    "))",
            )
            val info = JSON.decodeFromString<NovelPluginInfo>(jsonResult)
            pluginIdsByUrl[meta.url] = info.id
            info
        }
    }

    suspend fun popular(pluginId: String, page: Int): List<NovelItem> = call(
        pluginId,
        "popularNovels",
        listOf(
            JsonPrimitive(page),
            buildJsonObject {
                put("showLatestNovels", false)
            },
        ),
    ) { raw -> JSON.decodeFromJsonElement(ListSerializer(NovelItem.serializer()), raw) }

    suspend fun latest(pluginId: String, page: Int): List<NovelItem> = call(
        pluginId,
        "popularNovels",
        listOf(
            JsonPrimitive(page),
            buildJsonObject {
                put("showLatestNovels", true)
            },
        ),
    ) { raw -> JSON.decodeFromJsonElement(ListSerializer(NovelItem.serializer()), raw) }

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
                if (opts.headers?.keys?.none { it.equals("User-Agent", true) } != false &&
                    deviceUserAgent.isNotBlank()
                ) {
                    header("User-Agent", deviceUserAgent)
                }
                val method = opts.method?.uppercase() ?: "GET"
                val body = when {
                    opts.bodyBase64 != null -> Base64.decode(opts.bodyBase64, Base64.NO_WRAP).toRequestBody()
                    opts.multipart != null -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                        opts.multipart.forEach { pair ->
                            addFormDataPart(pair.getOrElse(0) { "" }, pair.getOrElse(1) { "" })
                        }
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
                    put(
                        "bodyBase64",
                        if (binary) JsonPrimitive(Base64.encodeToString(responseBytes, Base64.NO_WRAP)) else JsonNull,
                    )
                    put(
                        "headers",
                        buildJsonObject {
                            response.headers.names().forEach { h -> put(h.lowercase(), response.header(h).orEmpty()) }
                        },
                    )
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
        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
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
