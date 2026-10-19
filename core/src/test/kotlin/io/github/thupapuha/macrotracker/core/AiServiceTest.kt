package io.github.thupapuha.macrotracker.core

import io.github.thupapuha.macrotracker.core.ai.AiException
import io.github.thupapuha.macrotracker.core.ai.AiService
import io.github.thupapuha.macrotracker.core.ai.DocumentPreparer
import io.github.thupapuha.macrotracker.core.ai.GeminiClient
import io.github.thupapuha.macrotracker.core.ai.GeminiTransport
import io.github.thupapuha.macrotracker.core.net.AppJson
import io.github.thupapuha.macrotracker.core.net.HttpResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AiServiceTest {

    private fun geminiReply(text: String, thought: String? = null): String = buildJsonObject {
        putJsonArray("candidates") {
            add(
                buildJsonObject {
                    putJsonObject("content") {
                        put("role", "model")
                        putJsonArray("parts") {
                            if (thought != null) {
                                add(buildJsonObject { put("text", thought); put("thought", true) })
                            }
                            add(buildJsonObject { put("text", text); put("thoughtSignature", "abc") })
                        }
                    }
                    put("finishReason", "STOP")
                },
            )
        }
    }.toString()

    private class FakeTransport(private val reply: HttpResult) : GeminiTransport {
        var lastRequest: String? = null
        override suspend fun generateContent(requestJson: String): HttpResult {
            lastRequest = requestJson
            return reply
        }
    }

    @Test
    fun parsesMealEstimateAndSkipsThoughts() = runTest {
        val transport = FakeTransport(
            HttpResult(
                200,
                geminiReply(
                    """```json
                    {"items":[{"name":"Huevo revuelto","quantity":"2 unidades","calories":0,"protein":12.04,"carbs":1.1,"fat":10}],
                     "notes":"Asumí aceite"}
                    ```""",
                    thought = "pensando...",
                ),
            ),
        )
        val service = AiService(GeminiClient { transport })
        val estimate = service.estimateMeal("2 huevos revueltos")

        assertEquals(1, estimate.items.size)
        val egg = estimate.items.first()
        assertEquals("Huevo revuelto", egg.name)
        assertEquals(12.0, egg.protein, 0.001)
        // Calories were missing, so they are derived from the macros (12*4 + 1.1*4 + 10*9).
        assertEquals(142.0, egg.calories, 0.001)

        val request = AppJson.parseToJsonElement(transport.lastRequest!!).jsonObject
        assertEquals(
            "application/json",
            request["generationConfig"]!!.jsonObject["responseMimeType"]!!.jsonPrimitive.content,
        )
        val parts = request["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertTrue(parts.last().jsonObject["text"]!!.jsonPrimitive.content.contains("2 huevos"))
    }

    @Test
    fun targetsFromDocumentDerivesMissingCalories() = runTest {
        val transport = FakeTransport(
            HttpResult(
                200,
                geminiReply("""{"calories":0,"protein":150,"carbs":200,"fat":60,"found_in_document":true,"summary":"Plan"}"""),
            ),
        )
        val service = AiService(GeminiClient { transport })
        val parts = DocumentPreparer.prepare("Plan: 150 g proteína".toByteArray(), "text/plain", "plan.txt")
        val suggestion = service.targetsFromDocument(parts)
        assertEquals(1940.0, suggestion.calories, 0.001)
        assertTrue(suggestion.toTargets("plan.txt").note.startsWith("Tomado de plan.txt"))
    }

    @Test
    fun reportsHttpErrors() = runTest {
        val body = """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""
        val service = AiService(GeminiClient { FakeTransport(HttpResult(429, body)) })
        try {
            service.estimateMeal("pan")
            fail("expected AiException")
        } catch (e: AiException) {
            assertTrue(e.message!!.contains("límite"))
            assertTrue(e.message!!.contains("Quota exceeded"))
        }
    }

    @Test
    fun extractsJsonSurroundedByText() {
        val json = GeminiClient.parseJsonText("Aquí está: {\"a\": 1} listo")
        assertEquals(JsonPrimitive(1), json.jsonObject["a"])
    }

    @Test
    fun extractsDocxText() {
        val xml = """<?xml version="1.0"?><w:document><w:body>
            <w:p><w:r><w:t>Calorías: 2000 kcal</w:t></w:r></w:p>
            <w:p><w:r><w:t>Proteína &amp; grasas</w:t></w:r></w:p>
            </w:body></w:document>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }
        val text = DocumentPreparer.extractDocxText(out.toByteArray())
        assertTrue(text, text.contains("Calorías: 2000 kcal"))
        assertTrue(text, text.contains("Proteína & grasas"))
    }

    @Test
    fun pdfIsSentInline() {
        val parts = DocumentPreparer.prepare(byteArrayOf(1, 2, 3), null, "plan.PDF")
        assertEquals("application/pdf", parts.last().inlineData!!.mimeType)
    }

    @Test(expected = AiException::class)
    fun rejectsUnsupportedFiles() {
        DocumentPreparer.prepare(byteArrayOf(1), "application/zip", "x.zip")
    }
}
