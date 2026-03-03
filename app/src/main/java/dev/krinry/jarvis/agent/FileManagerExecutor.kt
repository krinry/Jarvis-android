package dev.krinry.jarvis.agent

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * FileManagerExecutor — Create, read, write, list, delete, and share files.
 *
 * Works with both app internal storage and external shared storage.
 * Actions: list_files, read_file, write_file, delete_file, share_file
 */
object FileManagerExecutor {

    private const val TAG = "FileManagerExecutor"

    fun execute(action: ActionExecutor.AgentAction, context: Context): String {
        return when (action.action) {
            "list_files" -> listFiles(action)
            "read_file" -> readFile(action)
            "write_file" -> writeFile(action)
            "delete_file" -> deleteFile(action)
            "share_file" -> shareFile(action, context)
            else -> "❓ Unknown file action: ${action.action}"
        }
    }

    /**
     * List files in a directory.
     * AI: {"action":"list_files","path":"/sdcard/Download"}
     * Default: /sdcard/ root
     */
    private fun listFiles(action: ActionExecutor.AgentAction): String {
        val path = action.path ?: Environment.getExternalStorageDirectory().absolutePath

        return try {
            val dir = File(path)
            if (!dir.exists()) return "❌ Directory nahi mila: $path"
            if (!dir.isDirectory) return "❌ Yeh file hai, directory nahi: ${dir.name}"

            val files = dir.listFiles() ?: return "❌ Directory padh nahi paya"
            if (files.isEmpty()) return "✅ Directory khaali hai: ${dir.name}"

            val sb = StringBuilder("✅ ${dir.name}/ (${files.size} items):\n")
            files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .take(30)
                .forEach { f ->
                    val icon = if (f.isDirectory) "📁" else "📄"
                    val size = if (f.isFile) " (${formatSize(f.length())})" else ""
                    sb.append("$icon ${f.name}$size\n")
                }
            if (files.size > 30) sb.append("... aur ${files.size - 30} files")

            sb.toString().trim()
        } catch (e: Exception) {
            "❌ List error: ${e.message?.take(50)}"
        }
    }

    /**
     * Read text file content.
     * AI: {"action":"read_file","path":"/sdcard/Download/notes.txt"}
     */
    private fun readFile(action: ActionExecutor.AgentAction): String {
        val path = action.path ?: return "❌ File path nahi diya"

        return try {
            val file = File(path)
            if (!file.exists()) return "❌ File nahi mila: ${file.name}"
            if (!file.isFile) return "❌ Yeh directory hai, file nahi"
            if (file.length() > 500_000) return "❌ File bahut badi hai: ${formatSize(file.length())}"

            val content = file.readText().take(3000) // Truncate for tokens
            Log.d(TAG, "Read file: $path (${content.length} chars)")
            "✅ ${file.name} (${formatSize(file.length())}):\n$content"
        } catch (e: Exception) {
            "❌ Read error: ${e.message?.take(50)}"
        }
    }

    /**
     * Write/create a text file.
     * AI: {"action":"write_file","path":"/sdcard/Download/note.txt","body":"Hello World"}
     */
    private fun writeFile(action: ActionExecutor.AgentAction): String {
        val path = action.path ?: return "❌ File path nahi diya"
        val body = action.body ?: return "❌ File content nahi diya"

        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(body)
            Log.d(TAG, "Wrote file: $path (${body.length} chars)")
            "✅ File banaya: ${file.name} (${body.length} chars)"
        } catch (e: Exception) {
            "❌ Write error: ${e.message?.take(50)}"
        }
    }

    /**
     * Delete a file.
     * AI: {"action":"delete_file","path":"/sdcard/Download/temp.txt"}
     */
    private fun deleteFile(action: ActionExecutor.AgentAction): String {
        val path = action.path ?: return "❌ File path nahi diya"

        return try {
            val file = File(path)
            if (!file.exists()) return "❌ File nahi mila: ${file.name}"

            if (file.isDirectory) {
                file.deleteRecursively()
                "✅ Folder delete kiya: ${file.name}"
            } else {
                file.delete()
                "✅ File delete kiya: ${file.name}"
            }
        } catch (e: Exception) {
            "❌ Delete error: ${e.message?.take(50)}"
        }
    }

    /**
     * Share a file to another app.
     * AI: {"action":"share_file","path":"/sdcard/Download/photo.jpg"}
     */
    private fun shareFile(action: ActionExecutor.AgentAction, context: Context): String {
        val path = action.path ?: return "❌ File path nahi diya"

        return try {
            val file = File(path)
            if (!file.exists()) return "❌ File nahi mila: ${file.name}"

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = getMimeType(file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "✅ Share dialog khol diya: ${file.name}"
        } catch (e: Exception) {
            "❌ Share error: ${e.message?.take(50)}"
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
            else -> "${bytes / (1024 * 1024 * 1024)}GB"
        }
    }

    private fun getMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "html" -> "text/html"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }
}
