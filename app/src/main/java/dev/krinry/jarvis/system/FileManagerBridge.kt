package dev.krinry.jarvis.system

import android.os.Environment
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FileManagerBridge — Direct file system authority for J.A.R.V.I.S.
 *
 * Allows Jarvis to create, write, read, and organize code files (HTML, CSS, JS, Python, Shell, JSON)
 * anywhere in the user workspace (/sdcard/Download/JarvisProjects/) or system storage.
 *
 * Features:
 * - Native Java/Kotlin file operations for instant performance.
 * - Multi-tier fallback to Shizuku/su/sh shell if Android Scoped Storage restricts direct file writing.
 * - Automatic parent directory creation.
 */
object FileManagerBridge {

    private const val TAG = "FileManagerBridge"

    fun getWorkspaceDir(): File {
        return try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(downloads, "JarvisProjects").apply { if (!exists()) mkdirs() }
        } catch (_: Exception) {
            File("/sdcard/Download/JarvisProjects").apply { if (!exists()) mkdirs() }
        }
    }

    fun resolveFile(path: String): File {
        val cleanPath = path.trim()
        return if (cleanPath.startsWith("/")) {
            File(cleanPath)
        } else {
            File(getWorkspaceDir(), cleanPath)
        }
    }

    /**
     * Writes [content] to [path]. Creates parent directories automatically.
     */
    suspend fun writeFile(
        path: String,
        content: String,
        overwrite: Boolean = true
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val target = resolveFile(path)

        if (target.exists() && !overwrite) {
            return@withContext Pair(false, "❌ File already exists at ${target.absolutePath} and overwrite=false.")
        }

        try {
            target.parentFile?.mkdirs()
            FileOutputStream(target, false).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
            }
            Log.d(TAG, "File written successfully: ${target.absolutePath} (${content.length} chars)")
            return@withContext Pair(
                true,
                "✅ File written successfully at ${target.absolutePath} (${content.length} chars)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Standard FileOutputStream failed (${e.message}), attempting shell fallback via Shizuku/sh")
        }

        // Multi-tier fallback via Shizuku / su / sh
        try {
            val parentDir = target.parentFile?.absolutePath ?: "/sdcard/Download/JarvisProjects"
            ShizukuBridge.executeShell("mkdir -p '$parentDir'")

            val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val shellCmd = "echo '$b64' | base64 -d > '${target.absolutePath}'"
            val res = ShizukuBridge.executeShell(shellCmd)

            if (res.isSuccess) {
                return@withContext Pair(
                    true,
                    "✅ File written via system shell at ${target.absolutePath} (${content.length} chars)"
                )
            } else {
                return@withContext Pair(
                    false,
                    "❌ Shell write failed: ${res.stderr.ifBlank { res.stdout }}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "All write methods failed for $path", e)
            return@withContext Pair(false, "❌ Failed to write file: ${e.message}")
        }
    }

    /**
     * Reads text content from [path] up to [maxLines].
     */
    suspend fun readFile(
        path: String,
        maxLines: Int = 100
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val target = resolveFile(path)

        if (!target.exists()) {
            return@withContext Pair(false, "❌ File does not exist: ${target.absolutePath}")
        }

        try {
            val lines = mutableListOf<String>()
            FileInputStream(target).bufferedReader(Charsets.UTF_8).use { reader ->
                var count = 0
                var line = reader.readLine()
                while (line != null && count < maxLines) {
                    lines.add(line)
                    count++
                    line = reader.readLine()
                }
            }
            val text = lines.joinToString("\n")
            val truncatedNotice = if (target.length() > text.length * 2) "\n[... truncated, showing first $maxLines lines]" else ""
            return@withContext Pair(true, text + truncatedNotice)
        } catch (e: Exception) {
            Log.w(TAG, "Direct read failed (${e.message}), trying shell fallback")
        }

        // Shell fallback
        try {
            val res = ShizukuBridge.executeShell("head -n $maxLines '${target.absolutePath}'")
            if (res.isSuccess) {
                return@withContext Pair(true, res.stdout)
            } else {
                return@withContext Pair(false, "❌ Could not read file: ${res.stderr}")
            }
        } catch (e: Exception) {
            return@withContext Pair(false, "❌ Read error: ${e.message}")
        }
    }

    /**
     * Lists files and folders in [path].
     */
    suspend fun listDirectory(path: String? = null): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val target = if (path.isNullOrBlank()) getWorkspaceDir() else resolveFile(path)

        if (!target.exists()) {
            return@withContext Pair(false, "❌ Directory does not exist: ${target.absolutePath}")
        }

        try {
            val files = target.listFiles()
            if (files == null || files.isEmpty()) {
                return@withContext Pair(true, "Directory ${target.absolutePath} is empty.")
            }

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val sb = StringBuilder("Directory: ${target.absolutePath} (${files.size} items):\n")
            files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach { f ->
                val type = if (f.isDirectory) "[DIR]" else "[FILE]"
                val size = if (f.isFile) "(${f.length()} bytes)" else ""
                val date = sdf.format(Date(f.lastModified()))
                sb.append("• $type ${f.name} $size ($date)\n")
            }
            return@withContext Pair(true, sb.toString().trim())
        } catch (e: Exception) {
            return@withContext Pair(false, "❌ Failed to list directory: ${e.message}")
        }
    }

    /**
     * Deletes [path].
     */
    suspend fun deleteFile(path: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val target = resolveFile(path)
        if (!target.exists()) {
            return@withContext Pair(true, "Target does not exist: ${target.absolutePath}")
        }
        try {
            val deleted = target.deleteRecursively()
            if (deleted) {
                return@withContext Pair(true, "✅ Deleted: ${target.absolutePath}")
            }
        } catch (_: Exception) {}

        val res = ShizukuBridge.executeShell("rm -rf '${target.absolutePath}'")
        return@withContext if (res.isSuccess) {
            Pair(true, "✅ Deleted via shell: ${target.absolutePath}")
        } else {
            Pair(false, "❌ Failed to delete: ${res.stderr}")
        }
    }
}
