package io.github.thupapuha.macrotracker.core.ai

import io.github.thupapuha.macrotracker.core.net.AppJson
import io.github.thupapuha.macrotracker.core.net.HttpResult
import io.github.thupapuha.macrotracker.core.net.JSON_MEDIA_TYPE
import io.github.thupapuha.macrotracker.core.net.await
import io.github.thupapuha.macrotracker.core.net.extractErrorMessage
import io.github.thupapuha.macrotracker.core.supabase.BackendException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

const val DEFAULT_GEMINI_MODEL = "gemini-3.6-flash"

class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Sends a raw `generateContent` request body and returns the raw response. */
fun interface GeminiTransport {
    suspend fun generateContent(requestJson: String): HttpResult
}

/** Calls the Gemini API directly with an API key the user typed into the app. */
class DirectGeminiTransport(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val model: String = DEFAULT_GEMINI_MODEL,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : GeminiTransport {
    override suspend fun generateContent(requestJson: String): HttpResult {
        val request = Request.Builder()
            .url("$baseUrl/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return http.await(request)
    }
}

class GeminiClient(private val transport: suspend () -> GeminiTransport) {

    suspend fun generateJson(systemPrompt: String, parts: List<Part>, schema: JsonObject): JsonElement {
        val request = GeminiRequest(
            contents = listOf(Content(role = "user", parts = parts)),
            systemInstruction = Content(parts = listOf(Part(text = systemPrompt))),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                responseSchema = schema,
            ),
        )
        val body = AppJson.encodeToString(GeminiRequest.serializer(), request)

        val result = try {
            transport().generateContent(body)
        } catch (e: IOException) {
            throw AiException("No se pudo conectar con la IA. Revisa tu conexión a internet.", e)
        } catch (e: BackendException) {
            throw AiException(e.message ?: "Error del servidor", e)
        }
        if (!result.isSuccessful) throw AiException(describeError(result))

        val response = try {
            AppJson.decodeFromString(GeminiResponse.serializer(), result.body)
        } catch (e: SerializationException) {
            throw AiException("Respuesta inesperada de la IA", e)
        }
        val text = response.text() ?: throw AiException(
            response.promptFeedback?.blockReason?.let { "La IA rechazó la solicitud ($it)" }
                ?: "La IA no devolvió resultados. Intenta de nuevo con más detalle.",
        )
        return parseJsonText(text)
    }

    private fun describeError(result: HttpResult): String {
        val detail = extractErrorMessage(result.body) ?: result.body.take(200)
        return when (result.code) {
            400 -> "Solicitud inválida para la IA: $detail"
            401, 403 -> "Sin permiso para usar la IA: $detail"
            404 -> "No se encontró el servicio o modelo de IA. ¿Está desplegada la función en Supabase? ($detail)"
            413 -> "El archivo es demasiado grande para la IA"
            429 -> "Se alcanzó el límite de uso de la IA. Intenta más tarde. ($detail)"
            in 500..599 -> "La IA no está disponible en este momento (${result.code}). Intenta de nuevo."
            else -> "Error de la IA (${result.code}): $detail"
        }
    }

    companion object {
        /** Parses model output as JSON, tolerating markdown code fences or text around the object. */
        fun parseJsonText(text: String): JsonElement {
            val trimmed = text.trim()
                .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
                .removeSuffix("```")
                .trim()
            runCatching { return AppJson.parseToJsonElement(trimmed) }
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start >= 0 && end > start) {
                runCatching { return AppJson.parseToJsonElement(trimmed.substring(start, end + 1)) }
            }
            throw AiException("La IA devolvió un formato que no se pudo leer")
        }
    }
}
