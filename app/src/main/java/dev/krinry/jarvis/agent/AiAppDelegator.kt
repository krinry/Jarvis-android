package dev.krinry.jarvis.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AiAppDelegator — Delegates heavy code/text generation to native AI apps.
 *
 * Instead of burning LLM API tokens to generate large files, Jarvis opens a
 * native app (ChatGPT, Gemini, DeepSeek, Kimi, Copilot, Claude) with the prompt
 * pre-filled. The user can glance at it live and Jarvis reads the response back.
 *
 * Supported apps (in priority order):
 *   ChatGPT, Gemini, DeepSeek, Kimi, Microsoft Copilot, Claude (Anthropic)
 *
 * Usage in Agent:
 *   {"action":"delegate_ai","app":"ChatGPT","text":"Write HTML portfolio for Rahul Sharma"}
 */
object AiAppDelegator {

    private const val TAG = "AiAppDelegator"

    // Known AI app packages and their deep-link intents
    private val AI_APPS = listOf(
        Triple("ChatGPT",    "com.openai.chatgpt",           null),             // Share intent works
        Triple("Gemini",     "com.google.android.apps.bard", null),
        Triple("DeepSeek",   "com.deepseek.chat",            null),
        Triple("Kimi",       "com.moonshot.kimichat",         null),
        Triple("Copilot",    "com.microsoft.copilot",        null),
        Triple("Claude",     "com.anthropic.claude",         null),
        Triple("Bing",       "com.microsoft.bing",           null),
        Triple("Perplexity", "ai.perplexity.app.android",   null)
    )

    /**
     * Delegate a task to an AI app. Opens the specified app (or first found)
     * with the prompt copied to clipboard and attempts to paste it in the chat.
     *
     * Returns a status string for the agent.
     */
    fun execute(action: ActionExecutor.AgentAction, context: Context): String {
        val prompt = action.text ?: action.body
            ?: return "❌ Prompt nahi diya delegate ke liye"

        val requestedApp = action.appName ?: action.speech  // "ChatGPT", "Gemini", etc.

        // Find the target app
        val targetApp = findTargetApp(context, requestedApp)
            ?: return "❌ Koi AI app install nahi hua. ChatGPT, Gemini, ya DeepSeek install karo."

        val (appName, packageName, _) = targetApp
        Log.d(TAG, "Delegating to $appName ($packageName): ${prompt.take(80)}...")

        // Step 1: Copy prompt to clipboard so agent can paste it later
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("jarvis_prompt", prompt)
        clipboard.setPrimaryClip(clip)

        // Step 2: Try to open with share intent (auto-fills prompt in some apps)
        val shareOpened = tryShareIntent(context, packageName, prompt)

        // Step 3: If share didn't work, just launch the app
        if (!shareOpened) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            } else {
                return "❌ $appName launch nahi ho paya"
            }
        }

        Log.d(TAG, "$appName opened. Prompt clipboard mein hai.")
        return "✅ $appName open kiya. Prompt clipboard mein copy hai — agent paste karega.\n\n📋 Prompt: ${prompt.take(100)}..."
    }

    /**
     * Returns clipboard content (what the AI app generated) so the agent can use it.
     * Agent should use this after calling delegate_ai and waiting.
     */
    fun readClipboard(context: Context): String {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    "✅ Clipboard se liya:\n${text.take(4000)}"
                } else {
                    "✅ Clipboard khaali hai"
                }
            } else {
                "✅ Clipboard khaali hai"
            }
        } catch (e: Exception) {
            "❌ Clipboard read error: ${e.message}"
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun findTargetApp(context: Context, preferredName: String?): Triple<String, String, String?>? {
        val pm = context.packageManager

        // Try preferred app first
        if (preferredName != null) {
            val preferred = AI_APPS.firstOrNull { (name, pkg, _) ->
                name.contains(preferredName, ignoreCase = true) ||
                preferredName.contains(name, ignoreCase = true)
            }
            if (preferred != null) {
                return try {
                    pm.getPackageInfo(preferred.second, 0)
                    preferred
                } catch (_: Exception) { null }
            }
        }

        // Return first installed AI app
        return AI_APPS.firstOrNull { (_, pkg, _) ->
            try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
        }
    }

    /**
     * Try to send prompt via ACTION_SEND to the AI app.
     * Some apps like Gemini accept share intents and auto-fill the text field.
     */
    private fun tryShareIntent(context: Context, packageName: String, prompt: String): Boolean {
        return try {
            // Limit prompt for share intent (some apps crash on huge strings)
            val shareText = prompt.take(8000)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
                // Target specific app
                component = null  // Let system choose, or set package:
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Share intent failed for $packageName: ${e.message}. Will launch normally.")
            false
        }
    }
}
