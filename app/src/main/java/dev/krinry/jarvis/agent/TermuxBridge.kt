package dev.krinry.jarvis.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * TermuxBridge — Execute Linux commands via Termux's RUN_COMMAND intent.
 *
 * Fixes vs old version:
 * - termux_write_file: Now writes to Android internal storage FIRST (no size limit),
 *   then sends a single `cp` command to Termux. This avoids bash command-length limits
 *   that caused large file writes to silently fail.
 * - termux_modify_file: NEW action. Uses sed or python to modify specific lines/sections
 *   in an existing file without re-writing the whole thing.
 * - readFile: Can now read from both Termux home and Android shared storage paths.
 */
object TermuxBridge {

    private const val TAG = "TermuxBridge"
    private const val TERMUX_PKG = "com.termux"
    private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"

    fun execute(action: ActionExecutor.AgentAction, context: Context): String {
        if (!isTermuxInstalled(context)) {
            return "❌ Termux install nahi hai. Pehle Termux install karo."
        }

        return when (action.action) {
            "termux_run"         -> runCommand(action, context)
            "termux_write_file"  -> writeFileSafe(action, context)
            "termux_modify_file" -> modifyFile(action, context)
            "termux_read_file"   -> readFile(action)
            else -> "❓ Unknown termux action: ${action.action}"
        }
    }

    // =========================================================================
    // Run shell command
    // =========================================================================
    private fun runCommand(action: ActionExecutor.AgentAction, context: Context): String {
        val command = action.command
        if (command.isNullOrBlank()) return "❌ Command nahi diya"

        return try {
            sendToTermux(context, command)
            Log.d(TAG, "Termux command sent: $command")
            "✅ Termux command bheja: ${command.take(60)}"
        } catch (e: SecurityException) {
            "❌ Termux permission denied. Settings → Apps → Jarvis → Permissions mein RUN_COMMAND allow karo."
        } catch (e: Exception) {
            Log.e(TAG, "Termux command failed", e)
            "❌ Termux error: ${e.message?.take(50)}"
        }
    }

    // =========================================================================
    // SAFE file write: write via Android IO first, then cp to Termux home
    // This bypasses the bash command-length limit that breaks large files.
    // =========================================================================
    private fun writeFileSafe(action: ActionExecutor.AgentAction, context: Context): String {
        val path = action.path
        val body = action.body
        if (path.isNullOrBlank()) return "❌ File path nahi diya"
        if (body == null) return "❌ File content nahi diya"

        return try {
            // Step 1: Determine target paths
            val fileName = path.substringAfterLast("/")  // e.g. "app.py" or "index.html"
            val termuxTargetPath = if (path.startsWith("/")) path else "$TERMUX_HOME/$path"

            // Step 2: Write to Android app's cache (no size limit, no bash needed)
            val tempFile = File(context.cacheDir, "termux_transfer_$fileName")
            tempFile.writeText(body)
            Log.d(TAG, "Temp file written: ${tempFile.absolutePath} (${body.length} bytes)")

            // Step 3: Send ONE simple cp command to Termux (tiny command, no content in bash)
            val cpCommand = "cp '${tempFile.absolutePath}' '$termuxTargetPath' && echo 'DONE'"
            sendToTermux(context, cpCommand)

            "✅ File bhej diya Termux me: $path (${body.length} chars)\n⏳ 2-3 sec wait karo cp ke liye."
        } catch (e: SecurityException) {
            "❌ Termux permission denied."
        } catch (e: Exception) {
            Log.e(TAG, "Write file failed", e)
            "❌ File write error: ${e.message?.take(60)}"
        }
    }

    // =========================================================================
    // Modify existing file: insert, replace, or append specific text.
    // AI: {"action":"termux_modify_file","path":"app.py","text":"old_line","body":"new_line"}
    // Also supports: {"action":"termux_modify_file","path":"index.html","command":"append","body":"<script>...</script>"}
    // =========================================================================
    private fun modifyFile(action: ActionExecutor.AgentAction, context: Context): String {
        val path = action.path ?: return "❌ File path nahi diya"
        val body = action.body ?: return "❌ Replacement/append content nahi diya"
        val mode = action.command ?: "replace" // "replace", "append", "prepend"
        val searchText = action.text  // For replace mode: what to find

        val termuxTargetPath = if (path.startsWith("/")) path else "$TERMUX_HOME/$path"

        val command = when (mode) {
            "append" -> {
                // Write append content to temp file, then cat-append to target
                val tempFile = File(context.cacheDir, "termux_append_${System.currentTimeMillis()}.tmp")
                tempFile.writeText(body)
                "cat '${tempFile.absolutePath}' >> '$termuxTargetPath' && echo 'APPENDED'"
            }
            "prepend" -> {
                val tempFile = File(context.cacheDir, "termux_prepend_${System.currentTimeMillis()}.tmp")
                tempFile.writeText(body)
                "cat '${tempFile.absolutePath}' '$termuxTargetPath' > '${termuxTargetPath}.tmp' && mv '${termuxTargetPath}.tmp' '$termuxTargetPath' && echo 'PREPENDED'"
            }
            else -> { // "replace" — use python for safe multiline replacement
                if (searchText.isNullOrBlank()) return "❌ Replace mode me 'text' field chahiye (what to find)"
                val searchFile = File(context.cacheDir, "search_${System.currentTimeMillis()}.tmp")
                val replaceFile = File(context.cacheDir, "replace_${System.currentTimeMillis()}.tmp")
                searchFile.writeText(searchText)
                replaceFile.writeText(body)
                // Python-based safe string replacement (handles multiline, special chars)
                """python3 -c "
import sys
with open('$termuxTargetPath','r') as f: content=f.read()
with open('${searchFile.absolutePath}','r') as f: old=f.read()
with open('${replaceFile.absolutePath}','r') as f: new=f.read()
content=content.replace(old,new,1)
with open('$termuxTargetPath','w') as f: f.write(content)
print('REPLACED')
""""
            }
        }

        return try {
            sendToTermux(context, command)
            "✅ File modify command bheja: $path ($mode)"
        } catch (e: Exception) {
            Log.e(TAG, "Modify file failed", e)
            "❌ Modify error: ${e.message?.take(60)}"
        }
    }

    // =========================================================================
    // Read file from Termux home or shared storage
    // =========================================================================
    private fun readFile(action: ActionExecutor.AgentAction): String {
        val path = action.path ?: return "❌ File path nahi diya"

        return try {
            // Try both Termux home and direct path
            val fullPath = if (path.startsWith("/")) path else "$TERMUX_HOME/$path"
            val file = File(fullPath)

            if (!file.exists()) return "❌ File nahi mila: $fullPath"
            if (file.length() > 100_000) return "❌ File bohot badi hai (>${file.length()/1024}KB). Pehle trim karo."

            val content = file.readText().take(3000)
            Log.d(TAG, "File read: $fullPath (${content.length} chars)")
            "✅ File content ($path):\n$content"
        } catch (e: Exception) {
            Log.e(TAG, "Read file failed", e)
            "❌ File read error: ${e.message?.take(50)}"
        }
    }

    // =========================================================================
    // Send command to Termux via RUN_COMMAND intent
    // =========================================================================
    private fun sendToTermux(context: Context, command: String) {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            component = ComponentName(TERMUX_PKG, TERMUX_SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_BIN/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        context.startService(intent)
    }

    private fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PKG, 0)
            true
        } catch (_: Exception) { false }
    }
}
