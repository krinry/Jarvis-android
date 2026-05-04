package dev.krinry.jarvis.data.chat

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

sealed class Attachment(val uri: String, val type: String) {

    data class Image(val imageUri: String) : Attachment(imageUri, "image")
    data class Audio(val audioUri: String) : Attachment(audioUri, "audio")
    data class Pdf(val pdfUri: String) : Attachment(pdfUri, "pdf")

    companion object {
        fun fromUri(uriString: String): Attachment? {
            val ext = uriString.substringAfterLast(".", "").lowercase()
            return when (ext) {
                "jpg", "jpeg", "png", "webp", "gif" -> Image(uriString)
                "mp3", "m4a", "wav", "ogg", "aac" -> Audio(uriString)
                "pdf" -> Pdf(uriString)
                else -> {
                    // Try to detect from content type pattern
                    when {
                        uriString.contains("/image") -> Image(uriString)
                        uriString.contains("/audio") -> Audio(uriString)
                        uriString.contains("/pdf") -> Pdf(uriString)
                        else -> null
                    }
                }
            }
        }
    }

    override fun toString(): String = uri

    /**
     * Persist a picked file to app cache directory so it survives
     * if the original is deleted by the user or the picker.
     */
    fun persistToCache(context: Context): Attachment {
        val sourceUri = Uri.parse(uri)
        val cacheDir = File(context.cacheDir, "attachments").apply { mkdirs() }
        val destFile = File(cacheDir, "${UUID.randomUUID()}.${fileExtension()}")

        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val cacheUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destFile
            )
            when (this) {
                is Image -> Image(cacheUri.toString())
                is Audio -> Audio(cacheUri.toString())
                is Pdf -> Pdf(cacheUri.toString())
            }
        } catch (e: Exception) {
            this // fallback to original
        }
    }

    private fun fileExtension(): String = when (this) {
        is Image -> "jpg"
        is Audio -> "m4a"
        is Pdf -> "pdf"
    }
}
