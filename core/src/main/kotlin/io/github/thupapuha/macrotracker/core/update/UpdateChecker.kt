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
) {
    /** Returns the latest release if it is newer than [currentVersionCode], otherwise null. */
    suspend fun check(currentVersionCode: Int): AvailableUpdate? =
        latest()?.takeIf { it.versionCode > currentVersionCode }

    suspend fun latest(): AvailableUpdate? {
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
            !result.isSuccessful -> throw UpdateException("No se pudo buscar actualizaciones (${result.code})")
            else -> parseRelease(result.body)
        }
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
