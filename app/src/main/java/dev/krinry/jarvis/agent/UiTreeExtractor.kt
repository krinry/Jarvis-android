package dev.krinry.jarvis.agent

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * UiTreeExtractor — Parses the current screen's AccessibilityNodeInfo tree
 * into a clean, simplified JSON array for the LLM.
 *
 * Now includes BOUNDS (x, y, w, h) for every node so the agent can use
 * gesture-based tap as a fallback when ACTION_CLICK fails.
 */
object UiTreeExtractor {

    private const val TAG = "UiTreeExtractor"
    private const val MAX_DEPTH = 15
    private const val MAX_NODES = 120

    data class UiNode(
        val id: Int,
        val text: String,
        val contentDescription: String,
        val className: String,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checked: Boolean?,
        val focused: Boolean,
        val bounds: Rect,  // Screen bounds for gesture fallback
        val nodeInfo: AccessibilityNodeInfo
    )

    /**
     * Extract UI tree from root node into a list of UiNodes.
     */
    fun extractTree(root: AccessibilityNodeInfo?): List<UiNode> {
        if (root == null) return emptyList()

        val nodes = mutableListOf<UiNode>()
        var idCounter = 1

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || nodes.size >= MAX_NODES) return

            // Skip invisible nodes
            if (!node.isVisibleToUser) return

            val text = node.text?.toString()?.trim() ?: ""
            val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
            val className = node.className?.toString()?.substringAfterLast('.') ?: ""

            // Get screen bounds
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Skip nodes with zero-size bounds (layout containers)
            val hasSize = bounds.width() > 0 && bounds.height() > 0

            // Include node if it has text, is actionable, or is an important type
            val isActionable = node.isClickable || node.isEditable || node.isScrollable ||
                    node.isCheckable || node.isFocusable || node.isLongClickable
            val hasContent = text.isNotEmpty() || contentDesc.isNotEmpty()
            val isImportant = className in listOf(
                "Button", "EditText", "ImageButton", "TextView", "ImageView",
                "CheckBox", "RadioButton", "Switch", "ToggleButton",
                "Spinner", "SeekBar", "SearchView", "TabView", "RecyclerView",
                "ListView", "ScrollView", "ViewPager"
            )

            if (hasSize && (hasContent || isActionable || isImportant)) {
                nodes.add(
                    UiNode(
                        id = idCounter++,
                        text = text.take(80),
                        contentDescription = contentDesc.take(80),
                        className = className,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        checked = if (node.isCheckable) node.isChecked else null,
                        focused = node.isFocused,
                        bounds = bounds,
                        nodeInfo = node
                    )
                )
            }

            // Traverse children
            for (i in 0 until node.childCount) {
                try {
                    val child = node.getChild(i)
                    if (child != null) {
                        traverse(child, depth + 1)
                    }
                } catch (_: Exception) {
                    // Skip stale nodes
                }
            }
        }

        try {
            traverse(root, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting UI tree", e)
        }

        Log.d(TAG, "Extracted ${nodes.size} UI nodes")
        return nodes
    }

    /**
     * Convert UI nodes to a compact JSON string for the LLM.
     * Includes bounds as [x, y] center point for gesture fallback.
     */
    fun toJson(nodes: List<UiNode>): String {
        val array = JSONArray()
        for (node in nodes) {
            val obj = JSONObject().apply {
                put("id", node.id)
                if (node.text.isNotEmpty()) put("text", node.text)
                if (node.contentDescription.isNotEmpty()) put("desc", node.contentDescription)
                put("type", node.className)
                // Center coordinates for gesture-based tap
                put("cx", node.bounds.centerX())
                put("cy", node.bounds.centerY())
                if (node.clickable) put("clickable", true)
                if (node.editable) put("editable", true)
                if (node.scrollable) put("scrollable", true)
                if (node.checked != null) put("checked", node.checked)
                if (node.focused) put("focused", true)
            }
            array.put(obj)
        }
        return array.toString()
    }

    /**
     * Get a node by its assigned ID.
     */
    fun findNodeById(nodes: List<UiNode>, id: Int): UiNode? {
        return nodes.find { it.id == id }
    }

    /**
     * Find a node by text content (fuzzy match).
     * Used as fallback when node_id doesn't match.
     */
    fun findNodeByText(nodes: List<UiNode>, text: String): UiNode? {
        // Exact match first
        val exact = nodes.find {
            it.text.equals(text, ignoreCase = true) ||
            it.contentDescription.equals(text, ignoreCase = true)
        }
        if (exact != null) return exact

        // Partial match
        return nodes.find {
            it.text.contains(text, ignoreCase = true) ||
            it.contentDescription.contains(text, ignoreCase = true)
        }
    }
}
