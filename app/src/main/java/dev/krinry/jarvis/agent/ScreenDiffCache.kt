package dev.krinry.jarvis.agent

import android.util.Log

/**
 * ScreenDiffCache — Caches UI tree between iterations.
 *
 * Instead of sending the FULL UI JSON every step, computes a diff:
 * - If <30% nodes changed → sends compact diff (added/removed/changed)
 * - If >30% changed or first call → sends full UI
 *
 * Result: ~50-70% fewer UI tokens on steps 2+.
 */
class ScreenDiffCache {

    companion object {
        private const val TAG = "ScreenDiffCache"
        private const val DIFF_THRESHOLD = 0.30 // 30% change = send full
    }

    // Previous iteration's node data: id → compact string representation
    private var previousNodes = mutableMapOf<String, String>()
    private var previousFullJson: String = ""
    private var isFirstCall = true

    /**
     * Clear cache (call on new task start).
     */
    fun clear() {
        previousNodes.clear()
        previousFullJson = ""
        isFirstCall = true
        Log.d(TAG, "Cache cleared")
    }

    /**
     * Compute diff or return full JSON.
     *
     * @param currentNodes Current UI nodes from UiTreeExtractor
     * @param currentFullJson Full compact JSON from UiTreeExtractor.toJson()
     * @return Either full JSON or compact diff string
     */
    fun getDiffOrFull(
        currentNodes: List<UiTreeExtractor.UiNode>,
        currentFullJson: String
    ): String {
        if (isFirstCall || previousNodes.isEmpty()) {
            // First call → send full, cache for next time
            cacheCurrentState(currentNodes, currentFullJson)
            isFirstCall = false
            Log.d(TAG, "First call: sending full UI (${currentNodes.size} nodes)")
            return currentFullJson
        }

        // Build current node signatures
        val currentSigs = buildNodeSignatures(currentNodes)

        // Compute diff
        val added = mutableListOf<String>()    // New nodes not in previous
        val removed = mutableListOf<String>()  // Previous nodes not in current
        val changed = mutableListOf<String>()  // Same key but different content

        // Find added & changed
        for ((key, sig) in currentSigs) {
            val prevSig = previousNodes[key]
            if (prevSig == null) {
                added.add(sig)
            } else if (prevSig != sig) {
                changed.add(sig)
            }
        }

        // Find removed
        for (key in previousNodes.keys) {
            if (key !in currentSigs) {
                removed.add(key)
            }
        }

        val totalChanges = added.size + removed.size + changed.size
        val totalNodes = maxOf(currentSigs.size, previousNodes.size, 1)
        val changeRatio = totalChanges.toDouble() / totalNodes

        // Cache current state for next iteration
        cacheCurrentState(currentNodes, currentFullJson)

        if (changeRatio > DIFF_THRESHOLD || totalChanges > 50) {
            // Too many changes — send full
            Log.d(TAG, "Large change (${(changeRatio * 100).toInt()}%): sending full UI")
            return currentFullJson
        }

        if (totalChanges == 0) {
            // No changes — send minimal marker
            Log.d(TAG, "No UI changes")
            return "UI_SAME"
        }

        // Build compact diff string
        val diffParts = mutableListOf<String>()
        if (added.isNotEmpty()) diffParts.add("+[${added.joinToString(",")}]")
        if (removed.isNotEmpty()) diffParts.add("-[${removed.joinToString(",")}]")
        if (changed.isNotEmpty()) diffParts.add("~[${changed.joinToString(",")}]")

        val diff = "DIFF:${diffParts.joinToString("")}"
        Log.d(TAG, "Diff mode: +${added.size} -${removed.size} ~${changed.size} " +
                "(${(changeRatio * 100).toInt()}% changed, ${diff.length} chars vs ${currentFullJson.length} full)")
        return diff
    }

    /**
     * Build a signature map for nodes.
     * Key: text+type combo (stable across re-renders), Value: compact node string
     */
    private fun buildNodeSignatures(nodes: List<UiTreeExtractor.UiNode>): Map<String, String> {
        val sigs = mutableMapOf<String, String>()
        for (node in nodes) {
            // Key: a stable identifier that survives re-renders (IDs change each parse)
            val key = "${node.className}|${node.text.take(30)}|${node.contentDescription.take(30)}"

            // Value: compact representation with current ID
            val sb = StringBuilder("{\"i\":${node.id}")
            if (node.text.isNotEmpty()) sb.append(",\"t\":\"${node.text.take(50)}\"")
            if (node.contentDescription.isNotEmpty()) sb.append(",\"d\":\"${node.contentDescription.take(50)}\"")
            val shortType = when (node.className) {
                "Button" -> "B"; "EditText" -> "E"; "ImageButton" -> "IB"
                "TextView" -> "TV"; "ImageView" -> "IV"; "CheckBox" -> "CB"
                "Switch" -> "SW"; "RecyclerView" -> "RV"
                else -> node.className.take(10)
            }
            sb.append(",\"T\":\"$shortType\"}")

            // Handle duplicate keys by appending index
            var finalKey = key
            var counter = 1
            while (finalKey in sigs) {
                finalKey = "${key}#${counter++}"
            }
            sigs[finalKey] = sb.toString()
        }
        return sigs
    }

    private fun cacheCurrentState(nodes: List<UiTreeExtractor.UiNode>, fullJson: String) {
        previousNodes = buildNodeSignatures(nodes).toMutableMap()
        previousFullJson = fullJson
    }
}
