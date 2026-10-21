package io.github.thupapuha.macrotracker.core.update

import io.github.thupapuha.macrotracker.core.net.AppJson
import io.github.thupapuha.macrotracker.core.net.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A newer APK published as a GitHub release (tag `build-<versionCode>`). */
data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Looks up the latest GitHub release of [repo] ("owner/name"). */
class UpdateChecker(
    private val http: OkHttpClient,
    private val repo: String,
    private val apiUrl: String = "https://api.github.com",
    private val webUrl: String = "https://github.com",
) {
    /** Returns the latest release if it is newer than [currentVersionCode], otherwise null. */
    suspend fun check(currentVersionCode: Int): AvailableUpdate? =
        latest()?.takeIf { it.versionCode > currentVersionCode }

    suspend fun latest(): AvailableUpdate? = fromManifest() ?: fromApi()

    /**
     * Each release carries a `version.json`. The `releases/latest/download/` link is served by
     * GitHub's download servers, which do not have the REST API's 60 requests/hour limit per IP
     * (easy to hit on mobile data, where many phones share an IP).
     */
    private suspend fun fromManifest(): AvailableUpdate? {
        val request = Request.Builder().url("$webUrl/$repo/releases/latest/download/version.json").build()
        val result = try {
            http.await(request)
        } catch (e: IOException) {
            return null
        }
        if (!result.isSuccessful) return null
        return parseManifest(result.body)
    }

    private suspend fun fromApi(): AvailableUpdate? {
        val request = Request.Builder()
            .url("$apiUrl/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val result = try {
            http.await(request)
        } catch (e: IOException) {
            throw UpdateException("No se pudo buscar actualizaciones. Revisa tu conexión.", e)
        }
        return when {
            result.code == 404 -> null
            result.code == 403 || result.code == 429 ->
                throw UpdateException("GitHub limitó las consultas desde tu red. Intenta en un rato.")
            !result.isSuccessful -> throw UpdateException("No se pudo buscar actualizaciones (${result.code})")
            else -> parseRelease(result.body)
        }
    }

    fun parseManifest(body: String): AvailableUpdate? {
        val manifest = try {
            AppJson.decodeFromString(Manifest.serializer(), body)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (manifest.versionCode <= 0 || manifest.apk.isBlank()) return null
        val tag = manifest.tag.ifBlank { "build-${manifest.versionCode}" }
        return AvailableUpdate(
            versionCode = manifest.versionCode,
            versionName = manifest.versionName.ifBlank { "1.0.${manifest.versionCode}" },
            apkUrl = "$webUrl/$repo/releases/download/$tag/${manifest.apk}",
            sizeBytes = manifest.size,
        )
    }

    companion object {
        private val versionInTag = Regex("(\\d+)$")

        fun parseRelease(body: String): AvailableUpdate? {
            val release = try {
                AppJson.decodeFromString(Release.serializer(), body)
            } catch (e: SerializationException) {
                throw UpdateException("Respuesta inesperada de GitHub", e)
            }
            if (release.draft || release.prerelease) return null
            val versionCode = versionInTag.find(release.tagName)?.value?.toIntOrNull() ?: return null
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
            return AvailableUpdate(
                versionCode = versionCode,
                versionName = "1.0.$versionCode",
                apkUrl = apk.url,
                sizeBytes = apk.size,
            )
        }
    }

    /** `version.json` written by the release workflow. */
    @Serializable
    private data class Manifest(
        val versionCode: Int = 0,
        val versionName: String = "",
        val tag: String = "",
        val apk: String = "",
        val size: Long = 0,
    )

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
    )
}
