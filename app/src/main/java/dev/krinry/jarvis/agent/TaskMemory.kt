package dev.krinry.jarvis.agent

import android.util.Log

/**
 * TaskMemory — Lightweight summary-based memory for the agent.
 *
 * Instead of sending full raw chat history (which causes token bloat),
 * this maintains a compact running summary:
 *   "Task: X | Plan: 1,2,3 | Done: 1,2 | Now: 3 | Last: clicked Send"
 *
 * Result: ~80% fewer tokens vs raw history per LLM call.
 */
class TaskMemory {

    companion object {
        private const val TAG = "TaskMemory"
    }

    var originalCommand: String = ""
        private set

    // AI's plan steps (e.g., ["open WhatsApp", "find Mom", "type hi", "click send"])
    private val planSteps = mutableListOf<String>()

    // Indices of completed steps (0-based)
    private val completedStepIndices = mutableSetOf<Int>()

    // Current step index being executed
    var currentStepIndex: Int = 0
        private set

    // Short description of last action result
    var lastActionResult: String = ""
        private set

    // Last error (if any)
    var lastError: String? = null
        private set

    // Step counter for the agent loop
    var iterationCount: Int = 0
        private set

    /**
     * Initialize memory for a new task.
     */
    fun startNewTask(command: String) {
        originalCommand = command
        planSteps.clear()
        completedStepIndices.clear()
        currentStepIndex = 0
        lastActionResult = ""
        lastError = null
        iterationCount = 0
        Log.d(TAG, "New task: $command")
    }

    /**
     * Set the plan steps from AI's first response.
     */
    fun setPlan(steps: List<String>) {
        planSteps.clear()
        planSteps.addAll(steps)
        Log.d(TAG, "Plan set: ${steps.joinToString(" → ")}")
    }

    val hasPlan: Boolean get() = planSteps.isNotEmpty()

    /**
     * Mark current step as done, advance to next.
     */
    fun markCurrentStepDone(resultSummary: String) {
        completedStepIndices.add(currentStepIndex)
        lastActionResult = resultSummary.take(60) // Keep it short
        if (currentStepIndex < planSteps.size - 1) {
            currentStepIndex++
        }
        lastError = null
    }

    /**
     * Record a failed action.
     */
    fun recordError(error: String) {
        lastError = error.take(60)
        lastActionResult = "FAILED: ${error.take(40)}"
    }

    /**
     * Increment iteration counter.
     */
    fun nextIteration() {
        iterationCount++
    }

    /**
     * Generate compact context string for LLM (~50-100 tokens vs ~2000+ raw history).
     *
     * Format: "Task: X | Plan: 1.step 2.step | Done: 1,2 | Step: 3 | Last: result | Err: msg"
     */
    fun toCompactContext(): String {
        val sb = StringBuilder()

        sb.append("Task:").append(originalCommand.take(80))

        if (planSteps.isNotEmpty()) {
            sb.append("|Plan:")
            planSteps.forEachIndexed { i, step ->
                sb.append("${i + 1}.${step.take(25)}")
                if (i < planSteps.size - 1) sb.append(",")
            }
        }

        if (completedStepIndices.isNotEmpty()) {
            sb.append("|Done:${completedStepIndices.sorted().map { it + 1 }.joinToString(",")}")
        }

        if (planSteps.isNotEmpty()) {
            sb.append("|Step:${currentStepIndex + 1}")
        }

        if (lastActionResult.isNotEmpty()) {
            sb.append("|Last:$lastActionResult")
        }

        lastError?.let {
            sb.append("|Err:$it")
        }

        return sb.toString()
    }

    /**
     * Get the current plan step description (for TTS/status).
     */
    fun getCurrentStepDesc(): String? {
        return planSteps.getOrNull(currentStepIndex)
    }

    /**
     * Get plan steps list (for overlay display).
     */
    fun getPlanSteps(): List<String> = planSteps.toList()

    /**
     * Get completed step indices (for overlay display).
     */
    fun getCompletedIndices(): Set<Int> = completedStepIndices.toSet()

    /**
     * Get total plan steps count.
     */
    fun totalSteps(): Int = planSteps.size

    /**
     * Get completed steps count.
     */
    fun completedSteps(): Int = completedStepIndices.size
}
