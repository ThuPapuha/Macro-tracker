package io.github.thupapuha.macrotracker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import io.github.thupapuha.macrotracker.core.ai.AiException
import io.github.thupapuha.macrotracker.core.ai.Attachment
import io.github.thupapuha.macrotracker.core.ai.DocumentPreparer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

class PickedDocument(val bytes: ByteArray, val mimeType: String?, val name: String)

/** Reads photos and documents chosen by the user and shrinks images before they go to the AI. */
class MediaReader(context: Context) {
    private val context = context.applicationContext
    private val resolver get() = context.contentResolver

    suspend fun image(uri: Uri, maxDimension: Int = 1600): Attachment = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            // Formats this Android version cannot decode (e.g. HEIC before Android 9) go as-is.
            val mime = resolver.getType(uri) ?: "image/jpeg"
            val bytes = open(uri).use { it.readLimited(DocumentPreparer.MAX_BYTES) }
            return@withContext Attachment(mime, bytes)
        }

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDimension || bounds.outHeight / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        val decoded = open(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw AiException("No se pudo leer la imagen")

        val rotation = runCatching { open(uri).use { ExifInterface(it).rotationDegrees } }.getOrDefault(0)
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val output = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

        val bytes = ByteArrayOutputStream().use { out ->
            output.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        if (output !== decoded) output.recycle()
        decoded.recycle()
        Attachment("image/jpeg", bytes)
    }

    suspend fun document(uri: Uri): PickedDocument = withContext(Dispatchers.IO) {
        val mime = resolver.getType(uri)
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "documento"

        if (mime?.startsWith("image/") == true) {
            val image = image(uri, maxDimension = 2048)
            return@withContext PickedDocument(image.bytes, image.mimeType, name)
        }
        PickedDocument(open(uri).use { it.readLimited(DocumentPreparer.MAX_BYTES) }, mime, name)
    }

    /** A content:// Uri the camera app can write a full-size photo into. */
    fun newPhotoUri(): Uri {
        val dir = File(context.cacheDir, "images").apply { mkdirs() }
        val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        dir.listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.delete() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw AiException("No se pudo abrir el archivo")

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) throw AiException("El archivo supera ${limit / (1024 * 1024)} MB")
        }
        return out.toByteArray()
    }
}
