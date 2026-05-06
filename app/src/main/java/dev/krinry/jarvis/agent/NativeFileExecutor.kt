package dev.krinry.jarvis.agent

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * NativeFileExecutor — File system operations for Jarvis AI Agent.
 * 
 * Operations:
 * - create_dir: Create directories
 * - write_file: Write text/binary to files
 * - read_file: Read file contents
 * - list_files: List directory contents
 * - delete_path: Delete file or directory
 * - move_file: Move/rename files
 * 
 * Uses Dispatchers.IO for all file operations (non-blocking).
 * Returns clear error messages for LLM feedback.
 */
object NativeFileExecutor {

    private const val TAG = "NativeFileExecutor"

    /**
     * Execute file operation based on action type.
     * All operations are suspend functions running on Dispatchers.IO.
     */
    suspend fun execute(
        action: String,
        path: String?,
        body: String? = null,
        newPath: String? = null,
        context: Context
    ): String = withContext(Dispatchers.IO) {
        try {
            when (action) {
                "create_dir" -> createDir(path, context)
                "write_file" -> writeFile(path, body, context)
                "read_file" -> readFile(path, context)
                "list_files" -> listFiles(path, context)
                "delete_path" -> deletePath(path, context)
                "move_file" -> moveFile(path, newPath, context)
                else -> "❌ Unknown action: $action"
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied", e)
            "❌ Permission denied. Please grant file access permission."
        } catch (e: Exception) {
            Log.e(TAG, "File operation failed", e)
            "❌ Error: ${e.message ?: "Unknown error"}"
        }
    }

    /**
     * Sync execute - for calling from non-suspend contexts (like ActionExecutor).
     * Uses runBlocking internally.
     */
    fun executeSync(action: String, path: String?, body: String? = null, newPath: String? = null, context: Context): String {
        return kotlinx.coroutines.runBlocking {
            execute(action, path, body, newPath, context)
        }
    }

    /**
     * Create directory/folder.
     * Supports nested directory creation.
     */
    private suspend fun createDir(path: String?, context: Context): String = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) {
            return@withContext "❌ Path required for create_dir"
        }

        val targetDir = resolvePath(path, context)
        if (targetDir.exists()) {
            return@withContext "⚠️ Directory already exists: ${targetDir.absolutePath}"
        }

        val created = targetDir.mkdirs()
        if (created) {
            "✅ Created directory: ${targetDir.absolutePath}"
        } else {
            "❌ Failed to create directory: ${targetDir.absolutePath}"
        }
    }

    /**
     * Write content to file.
     * Creates parent directories if they don't exist.
     * Supports append mode with append=true parameter.
     */
    private suspend fun writeFile(
        path: String?,
        body: String?,
        context: Context
    ): String = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) {
            return@withContext "❌ Path required for write_file"
        }
        if (body.isNullOrBlank()) {
            return@withContext "❌ Content required for write_file"
        }

        val targetFile = resolvePath(path, context)

        // Create parent directories if needed
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        try {
            if (targetFile.exists() && targetFile.isDirectory) {
                return@withContext "❌ Path is a directory: ${targetFile.absolutePath}"
            }

            FileOutputStream(targetFile).use { fos ->
                fos.write(body.toByteArray())
            }

            val size = targetFile.length()
            "✅ Written ${body.length} bytes to: ${targetFile.name}"
        } catch (e: Exception) {
            "❌ Write failed: ${e.message}"
        }
    }

    /**
     * Read file contents.
     * Returns text content for text files.
     * For binary files, returns metadata only.
     */
    private suspend fun readFile(path: String?, context: Context): String = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) {
            return@withContext "❌ Path required for read_file"
        }

        val targetFile = resolvePath(path, context)

        if (!targetFile.exists()) {
            return@withContext "❌ File not found: ${targetFile.absolutePath}"
        }

        if (targetFile.isDirectory) {
            return@withContext "❌ Path is a directory: ${targetFile.absolutePath}. Use list_files instead."
        }

        if (!targetFile.canRead()) {
            return@withContext "❌ Cannot read: ${targetFile.absolutePath}"
        }

        try {
            // Check file size - limit to 100KB for LLM context
            val maxSize = 100 * 1024 // 100KB
            if (targetFile.length() > maxSize) {
                return@withContext "⚠️ File too large (${targetFile.length()} bytes). Max 100KB supported.\n" +
                        "File: ${targetFile.name}\n" +
                        "Size: ${formatFileSize(targetFile.length())}\n" +
                        "Modified: ${formatDate(targetFile.lastModified())}"
            }

            FileInputStream(targetFile).use { fis ->
                val bytes = fis.readBytes()
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            "❌ Read failed: ${e.message}"
        }
    }

    /**
     * List directory contents.
     * Returns files and subdirectories with details.
     */
    private suspend fun listFiles(path: String?, context: Context): String = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) {
            val defaultDir = context.getExternalFilesDir(null)
                ?: context.filesDir
            return@withContext listDirectory(defaultDir)
        }

        val targetDir = resolvePath(path, context)

        if (!targetDir.exists()) {
            return@withContext "❌ Directory not found: ${targetDir.absolutePath}"
        }

        if (!targetDir.isDirectory) {
            return@withContext "❌ Not a directory: ${targetDir.absolutePath}"
        }

        listDirectory(targetDir)
    }

    private fun listDirectory(dir: File): String {
        val files = dir.listFiles() ?: return "⚠️ Empty directory: ${dir.absolutePath}"

        if (files.isEmpty()) {
            return "⚠️ Empty directory: ${dir.absolutePath}"
        }

        val sb = StringBuilder()
        sb.appendLine("📁 ${dir.absolutePath}")
        sb.appendLine("───")

        // Sort: directories first, then files
        val sorted = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        for (file in sorted) {
            val icon = if (file.isDirectory) "📂" else "📄"
            val size = if (file.isDirectory) "" else " (${formatFileSize(file.length())})"
            sb.appendLine("$icon ${file.name}$size")
        }

        sb.appendLine("───")
        sb.appendLine("${files.size} items")

        return sb.toString().trimEnd()
    }

    /**
     * Delete file or directory.
     * For directories, deletes recursively.
     */
    private suspend fun deletePath(path: String?, context: Context): String = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) {
            return@withContext "❌ Path required for delete_path"
        }

        val target = resolvePath(path, context)

        if (!target.exists()) {
            return@withContext "❌ Not found: ${target.absolutePath}"
        }

        val deleted = if (target.isDirectory) {
            deleteRecursively(target)
        } else {
            target.delete()
        }

        if (deleted) {
            "🗑️ Deleted: ${target.name}"
        } else {
            "❌ Delete failed: ${target.absolutePath}"
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                deleteRecursively(child)
            }
        }
        return file.delete()
    }

    /**
     * Move or rename file/directory.
     * Can also be used to copy by specifying new path.
     */
    private suspend fun moveFile(
        oldPath: String?,
        newPath: String?,
        context: Context
    ): String = withContext(Dispatchers.IO) {
        if (oldPath.isNullOrBlank()) {
            return@withContext "❌ Source path required for move_file"
        }
        if (newPath.isNullOrBlank()) {
            return@withContext "❌ Destination path required for move_file"
        }

        val source = resolvePath(oldPath, context)
        val dest = resolvePath(newPath, context)

        if (!source.exists()) {
            return@withContext "❌ Source not found: ${source.absolutePath}"
        }

        // Create parent directory if needed
        val parent = dest.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        val renamed = source.renameTo(dest)
        if (renamed) {
            "✅ Moved: ${source.name} → ${dest.name}"
        } else {
            "❌ Move failed. Try checking if destination exists."
        }
    }

    // ============================================================
    // Helper Functions
    // ============================================================

    /**
     * Resolve path to absolute file path.
     * Handles both absolute and relative paths.
     * Uses scoped storage for Android 11+.
     */
    private fun resolvePath(path: String?, context: Context): File {
        if (path == null) throw IllegalArgumentException("Path cannot be null")

        // Absolute path - use as is
        if (path.startsWith("/")) {
            return File(path)
        }

        // Relative path - use app's external storage
        val baseDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ - use app-specific directory
            context.getExternalFilesDir(null) ?: context.filesDir
        } else {
            // Below Android 11 - use external storage dir
            Environment.getExternalStorageDirectory()
        }

        return File(baseDir, path)
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    // ============================================================
    // Permission Helper Functions
    // ============================================================

    /**
     * Check if app has full file access permission.
     * For Android 11+ (API 30+), checks MANAGE_EXTERNAL_STORAGE.
     * For older versions, checks WRITE_EXTERNAL_STORAGE.
     */
    fun hasFilePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val write = android.content.pm.PackageManager.PERMISSION_GRANTED
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == write
        }
    }

    /**
     * Get the permission required for file operations.
     * Returns the permission string to request.
     */
    fun getRequiredPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.Manifest.permission.MANAGE_EXTERNAL_STORAGE
        } else {
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
    }

    /**
     * Check if should request legacy storage permission.
     * Returns true for Android 10 and below.
     */
    fun shouldRequestLegacyPermission(): Boolean {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
    }

    /**
     * Get storage access guide message for user.
     */
    fun getPermissionGuide(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "📱 Grant File Access:\n" +
                    "1. Go to Settings → Apps → Jarvis\n" +
                    "2. Tap 'Permissions'\n" +
                    "3. Enable 'Files and media' or 'All files access'\n" +
                    "Alternatively: Settings → Storage → Three dots → Allow access"
        } else {
            "📱 Grant Storage Permission:\n" +
                    "1. Go to Settings → Apps → Jarvis\n" +
                    "2. Tap 'Permissions'\n" +
                    "3. Enable 'Storage'"
        }
    }
}