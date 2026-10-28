package eu.kanade.tachiyomi.mslime

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Stable identifiers are part of the cache contract; change when translation semantics change. */
const val MSL_TRANSLATION_ENGINE_VERSION = "mlkit-17.0.3-en-ar-v1-glossary-empty-v1"

/** A chunk keeps the exact separator that followed it outside the text sent to the model. */
data class MslTranslationChunk(
    val index: Int,
    val source: String,
    val separatorAfter: String,
    val separatorBefore: String = "",
)

object MslTranslationText {
    private val tokenPattern = Regex("\\S+|\\s+")

    fun normalizeOcr(value: String): String = value
        .replace('“', '"')
        .replace('”', '"')
        .replace('’', '\'')
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        // Keep newlines: they are meaningful separators in speech bubbles.
        .replace(Regex("[\\t\\x0B\\f ]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .trim()

    /**
     * Splits long input without dropping whitespace. Separators between chunks are never sent to
     * the model, so a response can be reassembled in the original order without a scene marker
     * that a translation model could alter or omit.
     */
    fun splitPreservingSeparators(text: String, maxChars: Int = 1_800): List<MslTranslationChunk> {
        require(maxChars > 0) { "maxChars must be positive" }
        if (text.isEmpty()) return emptyList()

        val chunks = mutableListOf<MslTranslationChunk>()
        var current = StringBuilder()
        var pendingSeparator = ""
        var index = 0

        fun flush(separatorAfter: String) {
            if (current.isNotEmpty()) {
                chunks += MslTranslationChunk(index++, current.toString(), separatorAfter)
                current = StringBuilder()
            }
        }

        for (match in tokenPattern.findAll(text)) {
            val token = match.value
            if (token.all(Char::isWhitespace)) {
                pendingSeparator += token
                continue
            }

            if (current.isNotEmpty() && current.length + pendingSeparator.length + token.length > maxChars) {
                flush(pendingSeparator)
                pendingSeparator = ""
            }

            if (token.length <= maxChars) {
                current.append(pendingSeparator).append(token)
                pendingSeparator = ""
            } else {
                // A single URL/name can exceed the limit; split it deterministically by code unit.
                val leadingSeparator = if (current.isNotEmpty()) {
                    flush(pendingSeparator)
                    ""
                } else {
                    pendingSeparator
                }
                pendingSeparator = ""
                var start = 0
                while (start < token.length) {
                    val end = (start + maxChars).coerceAtMost(token.length)
                    chunks += MslTranslationChunk(
                        index++,
                        token.substring(start, end),
                        "",
                        if (start == 0) leadingSeparator else "",
                    )
                    start = end
                }
            }
        }
        flush(pendingSeparator)
        return chunks
    }

    fun joinTranslated(chunks: List<MslTranslationChunk>, translated: List<String>): String? {
        if (chunks.size != translated.size) return null
        if (chunks.isEmpty()) return ""
        return buildString {
            chunks.indices.forEach { i ->
                append(chunks[i].separatorBefore)
                append(translated[i])
                append(chunks[i].separatorAfter)
            }
        }
    }

    fun isUsableTranslation(value: String): Boolean = value.any { !it.isWhitespace() }

    /** The app deliberately supports one direction only: English -> Arabic. */
    fun isEnglishCandidate(value: String): Boolean {
        val text = value.trim()
        val englishLetters = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        val unsupportedScript = text.any {
            it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN
        }
        return englishLetters > 0 && !unsupportedScript
    }
}

data class MslProtectedText(
    val text: String,
    val replacements: Map<String, String>,
) {
    fun restore(translated: String): String? {
        if (replacements.keys.any { it !in translated }) return null
        return replacements.entries.fold(translated) { value, (token, replacement) ->
            value.replace(token, replacement)
        }
    }
}

/** Empty by default. Entries are only supplied by an explicit JSON object; no terms are invented. */
data class MslGlossary(val entries: Map<String, String> = emptyMap()) {
    fun protect(source: String): MslProtectedText {
        if (entries.isEmpty() || source.isEmpty()) return MslProtectedText(source, emptyMap())
        var protected = source
        val replacements = linkedMapOf<String, String>()
        entries.keys
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.length }
            .forEachIndexed { index, phrase ->
                val token = "MSL_GLOSSARY_TOKEN_${index}_X"
                var searchFrom = 0
                while (searchFrom < protected.length) {
                    val found = protected.indexOf(phrase, searchFrom, ignoreCase = true)
                    if (found < 0) break
                    if (isPhraseBoundary(protected, found, phrase.length)) {
                        protected = protected.replaceRange(found, found + phrase.length, token)
                        replacements[token] = entries.getValue(phrase)
                        searchFrom = found + token.length
                    } else {
                        searchFrom = found + phrase.length
                    }
                }
            }
        return MslProtectedText(protected, replacements)
    }

    companion object {
        val EMPTY = MslGlossary()

        /** Parses the intentionally empty asset (or a user-supplied object) without network I/O. */
        fun fromJson(json: String): MslGlossary {
            val root = Json.parseToJsonElement(json).jsonObject
            return MslGlossary(root.mapValues { (_, value) -> value.jsonPrimitive.content })
        }
    }

    private fun isPhraseBoundary(text: String, start: Int, length: Int): Boolean {
        fun wordChar(char: Char?): Boolean = char?.isLetterOrDigit() == true || char == '_'
        return !wordChar(text.getOrNull(start - 1)) && !wordChar(text.getOrNull(start + length))
    }
}

private fun sha256Hex(value: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

object MslTranslationCacheKey {
    fun forSource(source: String, engineVersion: String = MSL_TRANSLATION_ENGINE_VERSION): String =
        sha256Hex("$source\u0000$engineVersion\u0000en\u0000ar")
}

/** The store is intentionally key/value-only so it cannot persist source text or images. */
interface MslTranslationCacheStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

/** Bounded LRU cache. Only SHA-256 keys and translated values are stored. */
class MslBoundedTranslationCache(
    private val store: MslTranslationCacheStore,
    private val maxEntries: Int = 128,
) {
    private val order = ArrayDeque<String>()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        store.keys().forEach { order.addLast(it) }
        trim()
    }

    fun get(source: String): String? {
        val key = MslTranslationCacheKey.forSource(source)
        val value = store.get(key) ?: return null
        touch(key)
        return value
    }

    fun put(source: String, translated: String) {
        if (source.isEmpty() || !MslTranslationText.isUsableTranslation(translated)) return
        val key = MslTranslationCacheKey.forSource(source)
        store.put(key, translated)
        touch(key)
        trim()
    }

    fun contains(source: String): Boolean = get(source) != null
    fun size(): Int = order.count { store.get(it) != null }

    private fun touch(key: String) {
        order.remove(key)
        order.addLast(key)
    }

    private fun trim() {
        while (order.size > maxEntries) {
            store.remove(order.removeFirst())
        }
    }
}

/** Provider boundary kept free of ML Kit types so the processing contract is unit-testable. */
fun interface MslTranslationProvider {
    fun translate(source: String, onSuccess: (String) -> Unit, onFailure: (Throwable) -> Unit)
}

class MslTranslationProcessor(
    private val cache: MslBoundedTranslationCache,
    private val glossary: MslGlossary = MslGlossary.EMPTY,
    private val maxChars: Int = 1_800,
) {
    fun plan(source: String): List<MslTranslationChunk> =
        MslTranslationText.splitPreservingSeparators(source, maxChars)

    fun cached(source: String): String? = cache.get(source)

    fun cache(source: String, translated: String) = cache.put(source, translated)

    fun protect(source: String): MslProtectedText = glossary.protect(source)

    fun join(chunks: List<MslTranslationChunk>, translated: List<String>): String? =
        MslTranslationText.joinTranslated(chunks, translated)
}
