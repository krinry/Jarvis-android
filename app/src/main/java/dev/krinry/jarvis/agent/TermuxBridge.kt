package dev.krinry.jarvis.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * TermuxBridge — Execute Linux commands via Termux's RUN_COMMAND intent.
 *
 * AI can: run shell commands, write code files, read files — all via Termux.
 * Requirement: User must install Termux + grant RUN_COMMAND permission.
 *
 * Use cases:
 * - python script.py, node app.js, git clone, npm start
 * - Write files: AI writes code to Termux home dir
 * - Read output: AI reads files for results
 */
object TermuxBridge {

    private const val TAG = "TermuxBridge"
    private const val TERMUX_PKG = "com.termux"
    private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"

    /**
     * Execute a Termux action based on AI's command.
     */
    fun execute(action: ActionExecutor.AgentAction, context: Context): String {
        if (!isTermuxInstalled(context)) {
            return "❌ Termux install nahi hai. Pehle Termux install karo."
        }

        return when (action.action) {
            "termux_run" -> runCommand(action, context)
            "termux_write_file" -> writeFile(action, context)
            "termux_read_file" -> readFile(action)
            else -> "❓ Unknown termux action: ${action.action}"
        }
    }

    /**
     * Run a shell command in Termux.
     * AI sends: {"action":"termux_run","command":"python --version"}
     */
    private fun runCommand(action: ActionExecutor.AgentAction, context: Context): String {
        val command = action.command
        if (command.isNullOrBlank()) {
            return "❌ Command nahi diya"
        }

        return try {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                component = ComponentName(TERMUX_PKG, TERMUX_SERVICE)
                putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_BIN/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            context.startService(intent)
            Log.d(TAG, "Termux command sent: $command")
            "✅ Termux command bheja: ${command.take(60)}"
        } catch (e: SecurityException) {
            Log.e(TAG, "Termux permission denied", e)
            "❌ Termux permission denied. Settings → Apps → Jarvis → Permissions mein RUN_COMMAND allow karo."
        } catch (e: Exception) {
            Log.e(TAG, "Termux command failed", e)
            "❌ Termux error: ${e.message?.take(50)}"
        }
    }

    /**
     * Write a file to Termux home directory via RUN_COMMAND.
     * AI sends: {"action":"termux_write_file","path":"app.py","body":"print('hello')"}
     */
    private fun writeFile(action: ActionExecutor.AgentAction, context: Context): String {
        val path = action.path
        val body = action.body
        if (path.isNullOrBlank()) return "❌ File path nahi diya"
        if (body == null) return "❌ File content nahi diya"

        return try {
            val fullPath = if (path.startsWith("/")) path else "$TERMUX_HOME/$path"
            
            // Encode body to base64 to avoid quote escaping issues in bash commands
            val base64Body = android.util.Base64.encodeToString(body.toByteArray(), android.util.Base64.NO_WRAP)
            
            // Termux has base64 built-in. We echo the base64 string and decode it into the file.
            val command = "echo '$base64Body' | base64 -d > '$fullPath'"
            
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                component = ComponentName(TERMUX_PKG, TERMUX_SERVICE)
                putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_BIN/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            context.startService(intent)
            
            Log.d(TAG, "File written via RUN_COMMAND: $fullPath")
            "✅ File likhne ka command bheja: $path. Wait 2-3s for completion."
        } catch (e: SecurityException) {
            "❌ Termux permission denied. Allow RUN_COMMAND permission."
        } catch (e: Exception) {
            Log.e(TAG, "Write file failed", e)
            "❌ File write error: ${e.message?.take(50)}"
        }
    }

    /**
     * Read a file from Termux storage.
     * AI sends: {"action":"termux_read_file","path":"output.txt"}
     */
    private fun readFile(action: ActionExecutor.AgentAction): String {
        val path = action.path
        if (path.isNullOrBlank()) return "❌ File path nahi diya"

        return try {
            val fullPath = if (path.startsWith("/")) path else "$TERMUX_HOME/$path"
            val file = File(fullPath)
            if (!file.exists()) return "❌ File nahi mila: ${file.name}"
            val content = file.readText().take(2000) // Truncate to save tokens
            Log.d(TAG, "File read: $fullPath (${content.length} chars)")
            "✅ File content:\n$content"
        } catch (e: Exception) {
            Log.e(TAG, "Read file failed", e)
            "❌ File read error: ${e.message?.take(50)}"
        }
    }

    /**
     * Check if Termux is installed.
     */
    private fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PKG, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
