package dev.krinry.jarvis.agent

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

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
    private const val MAX_NODES = 150  // Increased for complex screens

    // Types to skip even if they have content (purely decorative)
    private val SKIP_TYPES = setOf(
        "View", "FrameLayout", "LinearLayout", "RelativeLayout",
        "ConstraintLayout", "CardView", "CoordinatorLayout"
    )

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
                // Skip decorative containers unless they are clickable/actionable
                val isDecorativeContainer = className in SKIP_TYPES && !isActionable && !hasContent
                if (!isDecorativeContainer) {
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

    // Types where node_id click reliably works — no need for x,y coordinates
    private val STANDARD_CLICK_TYPES = setOf(
        "Button", "EditText", "ImageButton", "CheckBox", "RadioButton",
        "Switch", "ToggleButton", "Spinner", "SeekBar"
    )

    /**
     * Ultra-compact JSON for LLM — minimizes token usage.
     * Short keys: i=id, t=text, d=desc, T=type, x=centerX, y=centerY
     * Only includes non-empty/non-default fields.
     * x,y only for nodes needing gesture-tap fallback (saves ~15% per node).
     */
    fun toJson(nodes: List<UiNode>): String {
        val sb = StringBuilder("[")
        var first = true
        for (node in nodes) {
            if (!first) sb.append(",")
            first = false

            sb.append("{\"i\":").append(node.id)

            val text = node.text.take(50)
            if (text.isNotEmpty()) sb.append(",\"t\":\"").append(escapeJson(text)).append("\"")

            val desc = node.contentDescription.take(50)
            if (desc.isNotEmpty()) sb.append(",\"d\":\"").append(escapeJson(desc)).append("\"")

            // Only include type if it's useful
            val shortType = when (node.className) {
                "Button" -> "B"
                "EditText" -> "E"
                "ImageButton" -> "IB"
                "TextView" -> "TV"
                "ImageView" -> "IV"
                "CheckBox" -> "CB"
                "Switch" -> "SW"
                "RecyclerView" -> "RV"
                else -> node.className.take(10)
            }
            sb.append(",\"T\":\"").append(shortType).append("\"")

            // x,y only for non-standard types that may need gesture tap fallback
            val needsCoords = node.className !in STANDARD_CLICK_TYPES
            if (needsCoords) {
                sb.append(",\"x\":").append(node.bounds.centerX())
                sb.append(",\"y\":").append(node.bounds.centerY())
            }

            // Only actionable flags that are true
            if (node.clickable) sb.append(",\"c\":1")
            if (node.editable) sb.append(",\"e\":1")
            if (node.scrollable) sb.append(",\"s\":1")
            if (node.checked != null) sb.append(",\"ck\":").append(if (node.checked) 1 else 0)

            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "")
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
