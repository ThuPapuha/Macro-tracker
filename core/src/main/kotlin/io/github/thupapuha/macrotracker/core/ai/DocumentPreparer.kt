package io.github.thupapuha.macrotracker.core.ai

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Turns an uploaded file into Gemini request parts (inline PDF/image, or extracted text). */
object DocumentPreparer {
    /** Gemini accepts up to ~20 MB per request; leave room for base64 overhead. */
    const val MAX_BYTES = 12 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 200_000

    private val inlineImageTypes = setOf("image/png", "image/jpeg", "image/webp", "image/heic", "image/heif")
    private const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private val textExtensions = setOf("txt", "csv", "md", "json", "xml", "html", "htm", "tsv")

    fun prepare(bytes: ByteArray, mimeType: String?, fileName: String?): List<Part> {
        if (bytes.isEmpty()) throw AiException("El archivo está vacío")
        if (bytes.size > MAX_BYTES) throw AiException("El archivo supera ${MAX_BYTES / (1024 * 1024)} MB")

        val name = fileName.orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        val header = Part(text = "Documento adjunto: ${name.ifBlank { "sin nombre" }}")

        return when {
            mime == "application/pdf" || extension == "pdf" ->
                listOf(header, Attachment("application/pdf", bytes).toPart())

            mime in inlineImageTypes -> listOf(header, Attachment(mime, bytes).toPart())

            mime == DOCX || extension == "docx" ->
                listOf(header, textPart(extractDocxText(bytes)))

            mime.startsWith("text/") || extension in textExtensions ->
                listOf(header, textPart(bytes.decodeToString()))

            else -> throw AiException("Formato no soportado. Usa PDF, imagen, Word (.docx) o texto.")
        }
    }

    private fun textPart(text: String): Part {
        val clean = text.trim()
        if (clean.isEmpty()) throw AiException("No se encontró texto en el documento")
        return Part(text = clean.take(MAX_TEXT_CHARS))
    }

    /** Reads the paragraphs of word/document.xml without any external library. */
    fun extractDocxText(bytes: ByteArray): String {
        val xml = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entry.name != "word/document.xml") entry = zip.nextEntry
            if (entry == null) throw AiException("El archivo Word no es válido")
            zip.readBytes().decodeToString()
        }
        return xml
            .replace("</w:p>", "\n")
            .replace(Regex("<w:tab\\s*/>"), "\t")
            .replace(Regex("<w:br\\s*/>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
