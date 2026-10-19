package io.github.thupapuha.macrotracker.core.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** Shared JSON settings: tolerant when reading, omits nulls when writing. */
val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

class HttpResult(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** Executes the request without blocking and cancels the HTTP call if the coroutine is cancelled. */
suspend fun OkHttpClient.await(request: Request): HttpResult = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use { HttpResult(it.code, it.body?.string().orEmpty()) }
                cont.resume(result)
            } catch (e: IOException) {
                cont.resumeWithException(e)
            }
        }
    })
}

/** Pulls a human readable message out of the error bodies returned by Gemini, PostgREST and GoTrue. */
fun extractErrorMessage(body: String): String? = runCatching {
    val obj = AppJson.parseToJsonElement(body).jsonObject
    fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    obj.str("message")
        ?: obj.str("msg")
        ?: obj.str("error_description")
        ?: (obj["error"] as? JsonObject)?.str("message")
        ?: obj.str("error")
}.getOrNull()?.takeIf { it.isNotBlank() }
