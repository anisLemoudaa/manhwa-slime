package tachiyomi.data.release

import android.os.Build
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import okhttp3.Headers.Companion.toHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReleaseServiceImpl(
    private val networkService: NetworkHelper,
    private val json: Json,
) : ReleaseService {

    override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release? {
        officialRelease()?.let { return it }

        val release = with(json) {
            networkService.client
                .newCall(GET("https://api.github.com/repos/${arguments.repository}/releases/latest"))
                .awaitSuccess()
                .parseAs<GithubRelease>()
        }

        val downloadLink = getDownloadLink(release = release, isFoss = arguments.isFoss) ?: return null

        return Release(
            version = release.version,
            info = release.info.substringBeforeLast("<!-->").replace(gitHubUsernameMentionRegex) { mention ->
                "[${mention.value}](https://github.com/${mention.value.substring(1)})"
            },
            releaseLink = release.releaseLink,
            downloadLink = downloadLink,
        )
    }

    /**
     * Reads the public MANHWA SLIME release first. The Pages Function is preferred;
     * the public Supabase view is a fallback because direct-upload Pages deployments
     * may not execute the functions directory.
     */
    private suspend fun officialRelease(): Release? {
        val fromPages = runCatching {
            val response = networkService.client
                .newCall(GET(OFFICIAL_VERSION_URL))
                .awaitSuccess()
            json.decodeFromString<OfficialReleaseResponse>(response.body.string())
        }.getOrNull()

        if (fromPages?.isUsable() == true) return fromPages.toRelease()

        return runCatching {
            val response = networkService.client
                .newCall(
                    GET(
                        OFFICIAL_SUPABASE_URL,
                        headers = mapOf(
                            "apikey" to SUPABASE_ANON_KEY,
                            "Authorization" to "Bearer $SUPABASE_ANON_KEY",
                        ).toHeaders(),
                    ),
                )
                .awaitSuccess()
            json.decodeFromString<List<OfficialReleaseRow>>(response.body.string())
                .firstOrNull()
                ?.takeIf { it.downloadUrl?.startsWith("https://") == true }
                ?.toRelease()
        }.getOrNull()
    }

    private fun getDownloadLink(release: GithubRelease, isFoss: Boolean): String? {
        val map = release.assets.associate { asset ->
            BUILD_TYPES.find { "-$it" in asset.name } to asset.downloadLink
        }

        return if (!isFoss) {
            map[Build.SUPPORTED_ABIS[0]] ?: map[null]
        } else {
            map[FOSS]
        }
    }

    companion object {
        private const val FOSS = "foss"
        private const val OFFICIAL_VERSION_URL = "https://manhwa-slime.pages.dev/api/version?variant=standard"
        private const val OFFICIAL_SUPABASE_URL = "https://fqzdvcjekjgpdvomobre.supabase.co/rest/v1/app_releases?select=variant,version_name,version_code,title,release_notes,download_url,mandatory,published_at&variant=eq.standard&status=eq.published&limit=1"
        private const val SUPABASE_ANON_KEY = "sb_publishable_NpIFxgCHhuVs3yYqm8ynIA__DQ2A0xs"
        private val BUILD_TYPES = listOf(FOSS, "arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        /**
         * Regular expression that matches a mention to a valid GitHub username, like it's
         * done in GitHub Flavored Markdown. It follows these constraints:
         *
         * - Alphanumeric with single hyphens (no consecutive hyphens)
         * - Cannot begin or end with a hyphen
         * - Max length of 39 characters
         *
         * Reference: https://stackoverflow.com/a/30281147
         */
        private val gitHubUsernameMentionRegex = """\B@([a-z0-9](?:-(?=[a-z0-9])|[a-z0-9]){0,38}(?<=[a-z0-9]))"""
            .toRegex(RegexOption.IGNORE_CASE)
    }
}

@Serializable
private data class OfficialReleaseResponse(
    val available: Boolean = false,
    val versionName: String? = null,
    val versionCode: Int? = null,
    val title: String? = null,
    val releaseNotes: JsonElement = JsonPrimitive(""),
    val downloadUrl: String? = null,
    val mandatory: Boolean = false,
    val publishedAt: String? = null,
) {
    fun isUsable() = available && !versionName.isNullOrBlank() && versionCode != null && downloadUrl?.startsWith("https://") == true

    fun toRelease() = Release(
        version = requireNotNull(versionName),
        info = releaseNotesText(releaseNotes).ifBlank { title.orEmpty() },
        releaseLink = "https://manhwa-slime.pages.dev/",
        downloadLink = requireNotNull(downloadUrl),
        versionCode = requireNotNull(versionCode),
        mandatory = mandatory,
        publishedAt = publishedAt,
    )
}

@Serializable
private data class OfficialReleaseRow(
    @SerialName("version_name") val versionName: String,
    @SerialName("version_code") val versionCode: Int,
    val title: String,
    @SerialName("release_notes") val releaseNotes: JsonElement = JsonPrimitive(""),
    @SerialName("download_url") val downloadUrl: String? = null,
    val mandatory: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
) {
    fun toRelease() = Release(
        version = versionName,
        info = releaseNotesText(releaseNotes).ifBlank { title },
        releaseLink = "https://manhwa-slime.pages.dev/",
        downloadLink = downloadUrl!!,
        versionCode = versionCode,
        mandatory = mandatory,
        publishedAt = publishedAt,
    )
}

private fun releaseNotesText(value: JsonElement): String = when (value) {
    is JsonArray -> value.joinToString("\n") { "• ${it.toString().trim('"')}" }
    is JsonPrimitive -> value.content
    else -> ""
}
