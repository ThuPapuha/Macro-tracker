package io.github.thupapuha.macrotracker.core.supabase

import io.github.thupapuha.macrotracker.core.ai.GeminiTransport
import io.github.thupapuha.macrotracker.core.net.AppJson
import io.github.thupapuha.macrotracker.core.net.HttpResult
import io.github.thupapuha.macrotracker.core.net.JSON_MEDIA_TYPE
import io.github.thupapuha.macrotracker.core.net.await
import io.github.thupapuha.macrotracker.core.net.extractErrorMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class SupabaseConfig(val url: String, val anonKey: String) {
    companion object {
        fun of(url: String?, anonKey: String?): SupabaseConfig? {
            if (url.isNullOrBlank() || anonKey.isNullOrBlank()) return null
            return SupabaseConfig(url.trim().trimEnd('/'), anonKey.trim())
        }
    }
}

@Serializable
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch seconds. */
    val expiresAt: Long,
    val userId: String,
)

interface SessionStore {
    suspend fun load(): AuthSession?
    suspend fun save(session: AuthSession?)
}

class BackendException(message: String, val status: Int = 0, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Minimal Supabase client: anonymous auth with token refresh, PostgREST RPC calls and
 * Edge Function invocation. Every app install gets its own anonymous user.
 */
class SupabaseClient(
    val config: SupabaseConfig,
    private val http: OkHttpClient,
    private val store: SessionStore,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutex = Mutex()
    private var cached: AuthSession? = null

    /**
     * Returns a valid session, creating an anonymous user on first use.
     * Pass [rejectedToken] after a 401 to force a refresh (only if nobody refreshed meanwhile).
     */
    suspend fun session(rejectedToken: String? = null): AuthSession = mutex.withLock {
        val current = cached ?: store.load()
        val session = when {
            current == null -> signUpAnonymously()
            rejectedToken != null && rejectedToken == current.accessToken -> refresh(current)
            current.expiresAt - nowSeconds() < 60 -> refresh(current)
            else -> current
        }
        if (session != current) store.save(session)
        cached = session
        session
    }

    /**
     * Puts back a session saved in a backup (after reinstalling the app). If it is no longer
     * valid, the next request falls back to a new anonymous user as usual.
     */
    suspend fun replaceSession(session: AuthSession) = mutex.withLock {
        store.save(session)
        cached = session
    }

    suspend fun rpc(function: String, args: JsonObject = JsonObject(emptyMap())): String {
        val result = authorized {
            Request.Builder()
                .url("${config.url}/rest/v1/rpc/$function")
                .post(args.toString().toRequestBody(JSON_MEDIA_TYPE))
        }
        if (!result.isSuccessful) {
            throw BackendException(extractErrorMessage(result.body) ?: "Error del servidor (${result.code})", result.code)
        }
        return result.body
    }

    suspend fun invokeFunction(name: String, body: String): HttpResult = authorized {
        Request.Builder()
            .url("${config.url}/functions/v1/$name")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
    }

    /** Gemini requests routed through the `gemini` Edge Function, which holds the API key. */
    fun geminiTransport(function: String = "gemini") = GeminiTransport { invokeFunction(function, it) }

    private suspend fun authorized(build: () -> Request.Builder): HttpResult {
        var session = session()
        var result = send(build(), session)
        if (result.code == 401) {
            session = session(rejectedToken = session.accessToken)
            result = send(build(), session)
        }
        return result
    }

    private suspend fun send(builder: Request.Builder, session: AuthSession): HttpResult = network {
        http.await(
            builder
                .header("apikey", config.anonKey)
                .header("Authorization", "Bearer ${session.accessToken}")
                .build(),
        )
    }

    private suspend fun signUpAnonymously(): AuthSession {
        val result = postAuth("/auth/v1/signup", buildJsonObject { put("data", JsonObject(emptyMap())) })
        if (!result.isSuccessful) {
            val detail = extractErrorMessage(result.body) ?: "código ${result.code}"
            val hint = if (result.body.contains("anonymous", ignoreCase = true)) {
                " Activa \"Allow anonymous sign-ins\" en Supabase (Authentication → Sign In / Providers)."
            } else {
                ""
            }
            throw BackendException("No se pudo crear la sesión: $detail.$hint", result.code)
        }
        return parseSession(result.body)
    }

    private suspend fun refresh(current: AuthSession): AuthSession {
        val result = postAuth(
            "/auth/v1/token?grant_type=refresh_token",
            buildJsonObject { put("refresh_token", current.refreshToken) },
        )
        return when {
            result.isSuccessful -> parseSession(result.body)
            // The refresh token is no longer valid: start over with a new anonymous user.
            result.code in 400..499 && result.code != 429 -> signUpAnonymously()
            else -> throw BackendException("El servidor no está disponible (${result.code})", result.code)
        }
    }

    private suspend fun postAuth(path: String, body: JsonObject): HttpResult = network {
        http.await(
            Request.Builder()
                .url(config.url + path)
                .header("apikey", config.anonKey)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
    }

    private fun parseSession(body: String): AuthSession {
        val token = try {
            AppJson.decodeFromString(TokenResponse.serializer(), body)
        } catch (e: SerializationException) {
            throw BackendException("Respuesta de sesión inválida", cause = e)
        }
        return AuthSession(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAt = token.expiresAt ?: (nowSeconds() + token.expiresIn),
            userId = token.user.id,
        )
    }

    private inline fun <T> network(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw BackendException("No se pudo conectar con el servidor. Revisa tu conexión.", cause = e)
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: Long = 3600,
        @SerialName("expires_at") val expiresAt: Long? = null,
        val user: TokenUser,
    )

    @Serializable
    private data class TokenUser(val id: String)
}
