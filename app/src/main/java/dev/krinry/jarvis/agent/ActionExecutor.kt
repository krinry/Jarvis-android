package dev.krinry.jarvis.agent

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.krinry.jarvis.service.AutoAgentService
import org.json.JSONObject

/**
 * ActionExecutor — Parses LLM JSON and executes actions.
 *
 * Key improvements:
 * - Gesture tap fallback when node click fails
 * - Text-based node lookup when node_id doesn't match (screen changed)
 * - Hindi status messages
 * - tap_xy action for direct coordinate taps
 * - swipe action for custom swipe gestures
 */
object ActionExecutor {

    private const val TAG = "ActionExecutor"

    data class AgentAction(
        val action: String,
        val nodeId: Int?,
        val text: String?,
        val appName: String?,
        val url: String?,
        val speech: String?,
        val status: String,
        val x: Int?,         // For tap_xy
        val y: Int?,         // For tap_xy
        val reason: String?  // LLM's reasoning
    )

    fun parseResponse(json: String): AgentAction? {
        return try {
            val cleanJson = json
                .replace("```json", "").replace("```", "")
                .trim()
                .let { raw ->
                    val start = raw.indexOf('{')
                    val end = raw.lastIndexOf('}')
                    if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
                }

            val obj = JSONObject(cleanJson)
            AgentAction(
                action = obj.optString("action", "wait"),
                nodeId = if (obj.has("node_id") && !obj.isNull("node_id")) obj.optInt("node_id", -1) else null,
                text = obj.optString("text", "").takeIf { it.isNotEmpty() },
                appName = obj.optString("app_name", "").takeIf { it.isNotEmpty() },
                url = obj.optString("url", "").takeIf { it.isNotEmpty() },
                speech = obj.optString("speech", "").takeIf { it.isNotEmpty() },
                status = obj.optString("status", "in_progress"),
                x = if (obj.has("x")) obj.optInt("x") else null,
                y = if (obj.has("y")) obj.optInt("y") else null,
                reason = obj.optString("reason", "").takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed: $json", e)
            null
        }
    }

    /**
     * Execute an action. Returns Hindi status string.
     */
    fun execute(action: AgentAction, nodes: List<UiTreeExtractor.UiNode>): String {
        val service = AutoAgentService.instance
            ?: return "❌ Accessibility service nahi chal rahi"

        return when (action.action) {
            "click" -> executeClick(action, nodes, service)
            "type" -> executeType(action, nodes, service)
            "scroll_down" -> executeScroll(true, action, nodes, service)
            "scroll_up" -> executeScroll(false, action, nodes, service)
            "back" -> { service.pressBack(); "✅ Back dabaya" }
            "home" -> { service.pressHome(); "✅ Home dabaya" }
            "recent" -> { service.openRecents(); "✅ Recent apps khule" }
            "open_app" -> {
                val appName = action.appName ?: action.text ?: return "❌ App ka naam nahi diya"
                openAppByName(service.applicationContext, appName)
            }
            "open_url" -> executeOpenUrl(action, service)
            "tap_xy" -> executeTapXY(action, service)
            "swipe" -> executeSwipe(action, service)
            "wait" -> "⏳ Screen load ho raha hai..."
            "done" -> "✅ Kaam ho gaya!"
            else -> "❓ Unknown action: ${action.action}"
        }
    }

    // =========================================================================
    // Click — 3 strategies: node click → gesture tap at bounds → text fallback
    // =========================================================================
    private fun executeClick(
        action: AgentAction,
        nodes: List<UiTreeExtractor.UiNode>,
        service: AutoAgentService
    ): String {
        val nodeId = action.nodeId ?: return "❌ Click ke liye node_id chahiye"

        // Strategy 1: Find node by ID
        var uiNode = UiTreeExtractor.findNodeById(nodes, nodeId)

        // Strategy 2: If not found by ID, try text match
        if (uiNode == null && action.text != null) {
            uiNode = UiTreeExtractor.findNodeByText(nodes, action.text)
            if (uiNode != null) {
                Log.d(TAG, "Node $nodeId nahi mila, text se mila: '${action.text}' → id=${uiNode.id}")
            }
        }

        // Strategy 3: If still not found, try reason text for clues
        if (uiNode == null && action.reason != null) {
            uiNode = UiTreeExtractor.findNodeByText(nodes, action.reason)
        }

        if (uiNode == null) {
            Log.w(TAG, "Node $nodeId nahi mila, koi text match bhi nahi")
            return "❌ Element nahi mila (node $nodeId) — screen badal gaya"
        }

        val label = uiNode.text.ifEmpty { uiNode.contentDescription }.ifEmpty { "element" }

        // Try accessibility click first, then gesture tap at bounds
        val success = service.clickNode(uiNode.nodeInfo)
        if (success) {
            return "✅ '$label' pe click kiya"
        }

        // Fallback: gesture tap at bounds center
        val bounds = uiNode.bounds
        if (bounds.width() > 0 && bounds.height() > 0) {
            service.tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            return "✅ '$label' pe tap kiya (gesture)"
        }

        return "❌ '$label' pe click nahi ho paya"
    }

    // =========================================================================
    // Type — Click field first, then set text
    // =========================================================================
    private fun executeType(
        action: AgentAction,
        nodes: List<UiTreeExtractor.UiNode>,
        service: AutoAgentService
    ): String {
        val nodeId = action.nodeId ?: return "❌ Type ke liye node_id chahiye"
        val text = action.text ?: return "❌ Text nahi diya type ke liye"

        var uiNode = UiTreeExtractor.findNodeById(nodes, nodeId)

        // Fallback: find any editable field
        if (uiNode == null) {
            uiNode = nodes.find { it.editable }
            if (uiNode != null) {
                Log.d(TAG, "Node $nodeId nahi mila, editable field use kar rahe hain: id=${uiNode.id}")
            }
        }

        if (uiNode == null) {
            return "❌ Text field nahi mila (node $nodeId)"
        }

        // Click to focus
        service.clickNode(uiNode.nodeInfo)
        Thread.sleep(300)

        val success = service.setTextOnNode(uiNode.nodeInfo, text)
        return if (success) "✅ Type kiya: '$text'" else "❌ Type nahi ho paya"
    }

    // =========================================================================
    // Scroll — Gesture swipe fallback
    // =========================================================================
    private fun executeScroll(
        forward: Boolean,
        action: AgentAction,
        nodes: List<UiTreeExtractor.UiNode>,
        service: AutoAgentService
    ): String {
        val nodeId = action.nodeId

        // Try to find a scrollable node
        val scrollNode = if (nodeId != null) {
            UiTreeExtractor.findNodeById(nodes, nodeId)
        } else {
            // Auto-find scrollable container
            nodes.find { it.scrollable }
        }

        if (scrollNode != null) {
            if (forward) {
                service.scrollForward(scrollNode.nodeInfo)
            } else {
                service.scrollBackward(scrollNode.nodeInfo)
            }
        } else {
            // Global swipe (always works)
            val root = service.getRootNode()
            if (root != null) {
                if (forward) service.scrollForward(root)
                else service.scrollBackward(root)
            }
        }

        return if (forward) "✅ Neeche scroll kiya" else "✅ Upar scroll kiya"
    }

    // =========================================================================
    // Tap at XY coordinates (direct gesture)
    // =========================================================================
    private fun executeTapXY(action: AgentAction, service: AutoAgentService): String {
        val x = action.x ?: return "❌ x coordinate chahiye"
        val y = action.y ?: return "❌ y coordinate chahiye"
        service.tapAt(x.toFloat(), y.toFloat())
        return "✅ Tap kiya ($x, $y) pe"
    }

    // =========================================================================
    // Custom swipe gesture
    // =========================================================================
    private fun executeSwipe(action: AgentAction, service: AutoAgentService): String {
        // Use text field for direction hint
        val direction = action.text?.lowercase() ?: "down"
        when (direction) {
            "left" -> service.performGlobalSwipe(800f, 1200f, 200f, 1200f, 300)
            "right" -> service.performGlobalSwipe(200f, 1200f, 800f, 1200f, 300)
            "up" -> service.performGlobalSwipe(540f, 1500f, 540f, 500f, 350)
            else -> service.performGlobalSwipe(540f, 500f, 540f, 1500f, 350)
        }
        return "✅ Swipe kiya: $direction"
    }

    // =========================================================================
    // Open URL
    // =========================================================================
    private fun executeOpenUrl(action: AgentAction, service: AutoAgentService): String {
        val url = action.url ?: action.text ?: return "❌ URL nahi diya"
        val context = service.applicationContext
        return try {
            val finalUrl = if (url.startsWith("http://") || url.startsWith("https://")) url
                           else "https://$url"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "✅ URL khola: $finalUrl"
        } catch (e: Exception) {
            "❌ URL nahi khul paya: ${e.message}"
        }
    }

    // =========================================================================
    // App Launcher — Robust with multiple fallbacks
    // =========================================================================
    private fun openAppByName(context: android.content.Context, appName: String): String {
        val pm = context.packageManager

        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else { 0 }

        val activities = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, resolveFlags)
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryIntentActivities failed", e)
            emptyList()
        }

        Log.d(TAG, "${activities.size} launchable apps mile, dhundh rahe hain: '$appName'")

        // 1. Exact match
        val exactMatch = activities.firstOrNull { info ->
            info.loadLabel(pm).toString().equals(appName, ignoreCase = true)
        }

        // 2. Partial match
        val fuzzyMatch = exactMatch ?: activities.firstOrNull { info ->
            val label = info.loadLabel(pm).toString()
            label.contains(appName, ignoreCase = true) ||
            appName.contains(label, ignoreCase = true)
        }

        // 3. Package name match
        val packageMatch = fuzzyMatch ?: activities.firstOrNull { info ->
            info.activityInfo.packageName.contains(appName, ignoreCase = true)
        }

        // 4. Word-by-word match (e.g., "whats app" → "WhatsApp")
        val wordMatch = packageMatch ?: run {
            val searchWords = appName.lowercase().replace(" ", "")
            activities.firstOrNull { info ->
                val label = info.loadLabel(pm).toString().lowercase().replace(" ", "")
                label.contains(searchWords) || searchWords.contains(label)
            }
        }

        val resolved = wordMatch
        if (resolved != null) {
            val label = resolved.loadLabel(pm).toString()
            val packageName = resolved.activityInfo.packageName
            return try {
                val intent = pm.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "✅ '$label' khol diya"
                } else {
                    val directIntent = Intent().apply {
                        setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(directIntent)
                    "✅ '$label' khol diya (direct)"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Launch failed: '$label'", e)
                "❌ '$label' nahi khul paya: ${e.message}"
            }
        }

        Log.w(TAG, "App nahi mili: '$appName'")
        return "❌ '$appName' app nahi mili device pe"
    }
}
