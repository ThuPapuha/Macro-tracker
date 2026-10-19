package io.github.thupapuha.macrotracker.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Subset of the Gemini `generateContent` REST schema used by the app. */
@Serializable
data class GeminiRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null,
    val generationConfig: GenerationConfig? = null,
)

@Serializable
data class Content(
    val parts: List<Part> = emptyList(),
    val role: String? = null,
)

@Serializable
data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null,
    val thought: Boolean? = null,
)

@Serializable
data class InlineData(
    val mimeType: String,
    val data: String,
)

@Serializable
data class GenerationConfig(
    val responseMimeType: String? = null,
    val responseSchema: JsonObject? = null,
)

@Serializable
data class GeminiResponse(
    val candidates: List<Candidate> = emptyList(),
    val promptFeedback: PromptFeedback? = null,
) {
    /** Concatenated answer text, skipping "thought" parts. */
    fun text(): String? = candidates.firstOrNull()?.content?.parts
        ?.filter { it.thought != true }
        ?.mapNotNull { it.text }
        ?.joinToString("")
        ?.takeIf { it.isNotBlank() }
}

@Serializable
data class Candidate(
    val content: Content? = null,
    val finishReason: String? = null,
)

@Serializable
data class PromptFeedback(
    val blockReason: String? = null,
)
