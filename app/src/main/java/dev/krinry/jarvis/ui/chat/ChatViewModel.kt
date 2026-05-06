package dev.krinry.jarvis.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.agent.ActionExecutor
import dev.krinry.jarvis.agent.AgentType
import dev.krinry.jarvis.agent.RouterAgent
import dev.krinry.jarvis.agent.ToolCallingEngine
import dev.krinry.jarvis.data.chat.Attachment
import dev.krinry.jarvis.data.chat.ChatDao
import dev.krinry.jarvis.data.chat.ChatDatabase
import dev.krinry.jarvis.data.chat.ChatMessage
import dev.krinry.jarvis.security.SecureKeyStore
import dev.krinry.jarvis.service.AutoAgentService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * PendingAction — Action ke liye user permission maangta hai.
 */
data class PendingAction(
    val actionType: String,   // e.g., "write_file", "delete_path", "termux_run"
    val target: String,      // e.g., "/storage/emulated/0/Jarvis/index.html"
    val details: String,     // e.g., "Creating file with 50 lines of HTML"
    val fullJson: String   // Original JSON from LLM
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val dao: ChatDao = ChatDatabase.getInstance(application).chatDao()
    private val context = application.applicationContext

    val messages: StateFlow<List<ChatMessage>> = dao.getAllMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedModel = MutableStateFlow(SecureKeyStore.getPrimaryModel(context))
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    // 🔒 Pending Action State (for user authorization)
    private val _pendingAction = MutableStateFlow<PendingAction?>(null)
    val pendingAction: StateFlow<PendingAction?> = _pendingAction.asStateFlow()

    // 🔄 CompletableDeferred for blocking the tool loop until user responds
    private var pendingActionDeferred: CompletableDeferred<Boolean>? = null

    // Tool call message IDs tracked for live updates
    private val toolCallMessageIds = mutableMapOf<String, Long>()

    init {
        if (_selectedModel.value.isBlank()) {
            _selectedModel.value = GroqApiClient.getActiveProvider(context).defaultModel
        }
    }

    fun sendMessage(text: String, attachments: List<Attachment> = emptyList()) {
        if (text.isBlank() && attachments.isEmpty()) return

        viewModelScope.launch {
            // Insert user message
            val userMsg = ChatMessage(
                role = "user",
                content = text,
                attachments = attachments.joinToString("\n") { it.toString() }
            )
            val userId = dao.insertMessage(userMsg)

            _isLoading.value = true

            try {
                // Check if Tool Calling is enabled
                val toolCallingEnabled = SecureKeyStore.isToolCallingEnabled(context)

                if (toolCallingEnabled) {
                    // ════════════════════════════════════════════
                    // 🛠️ TOOL CALLING PATH
                    // ════════════════════════════════════════════
                    sendWithToolCalling(text)
                } else {
                    // ════════════════════════════════════════════
                    // 💬 NORMAL CHAT PATH (legacy)
                    // ════════════════════════════════════════════
                    sendNormalChat(text)
                }
            } catch (e: Exception) {
                val errorMsg = ChatMessage(
                    role = "assistant",
                    content = "Error: ${e.message ?: "Something went wrong"}",
                    isError = true
                )
                dao.insertMessage(errorMsg)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ============================================================
    // 💬 Normal Chat (legacy path — no tool calling)
    // ============================================================

    private suspend fun sendNormalChat(text: String) {
        val recent = dao.getLastMessages(10)
        val chatHistory = buildChatHistory(recent)
        val model = _selectedModel.value

        val response = GroqApiClient.chat(
            context = context,
            messages = chatHistory + mapOf(
                "role" to "user",
                "content" to text
            )
        )

        val assistantMsg = ChatMessage(
            role = "assistant",
            content = response ?: "No response from AI",
            modelUsed = model,
            isError = response == null
        )
        dao.insertMessage(assistantMsg)
    }

    // ============================================================
    // 🛠️ Tool Calling Path
    // ============================================================

    private suspend fun sendWithToolCalling(command: String) {
        val engine = ToolCallingEngine(context)
        toolCallMessageIds.clear()

        // Wire callbacks

        // 1. onToolCallUpdate — insert/update tool call messages in chat
        engine.onToolCallUpdate = { toolName, status, arguments, result ->
            viewModelScope.launch {
                val key = "$toolName-${arguments.hashCode()}"
                val argsClean = arguments.replace("|", "\\|")
                val resultClean = result?.replace("|", "\\|") ?: ""
                val content = "[TOOL_CALL]$toolName|$status|$argsClean|$resultClean"

                val existingId = toolCallMessageIds[key]
                if (existingId != null) {
                    // Update existing message in-place to avoid layout jitter
                    dao.updateMessageContent(existingId, content)
                } else {
                    val msg = ChatMessage(role = "assistant", content = content)
                    val newId = dao.insertMessage(msg)
                    toolCallMessageIds[key] = newId
                }
            }
        }

        // 2. onPendingAction — shows Allow/Deny dialog, blocks via CompletableDeferred
        engine.onPendingAction = { actionType, target, details, fullJson ->
            val deferred = CompletableDeferred<Boolean>()
            pendingActionDeferred = deferred
            _pendingAction.value = PendingAction(actionType, target, details, fullJson)
            // Block the tool loop here until user responds
            deferred.await()
        }

        // 3. onAskUser — insert a question and wait for user response
        // (For now, just insert the question as a message — future: real input dialog)
        engine.onAskUser = { question ->
            dao.insertMessage(
                ChatMessage(role = "assistant", content = "🗣 $question")
            )
            null // For now, no interactive input in chat mode
        }

        // 4. onStatusUpdate — optional logging
        engine.onStatusUpdate = { status ->
            Log.d(TAG, "ToolCallingEngine: $status")
        }

        // Run the tool loop
        val agentType = detectAgentType(command)
        val finalResponse = engine.runToolLoop(command, agentType)

        // Insert the final assistant response
        if (!finalResponse.isNullOrBlank()) {
            val assistantMsg = ChatMessage(
                role = "assistant",
                content = finalResponse,
                modelUsed = _selectedModel.value
            )
            dao.insertMessage(assistantMsg)
        }
    }

    /**
     * Determine which agent should handle the command.
     * Uses RouterAgent (LLM) for high accuracy, with a keyword fallback.
     */
    private suspend fun detectAgentType(command: String): AgentType {
        // 1. Try LLM-based routing first (high accuracy)
        try {
            return RouterAgent.determineRoute(command, context)
        } catch (e: Exception) {
            Log.e(TAG, "RouterAgent failed, falling back to keywords", e)
        }

        // 2. Fallback to simple keywords if LLM router fails
        val lower = command.lowercase()
        return when {
            // Coder keywords
            lower.contains("code") || lower.contains("file") || lower.contains("create") ||
            lower.contains("write") || lower.contains("project") || lower.contains("website") ||
            lower.contains("html") || lower.contains("terminal") || lower.contains("install") ||
            lower.contains("run") || lower.contains("git") || lower.contains("build") ||
            lower.contains("tool") || lower.contains("function") -> AgentType.CODER_AGENT

            // UI / Phone control keywords
            lower.contains("click") || lower.contains("open") || lower.contains("app") ||
            lower.contains("screen") || lower.contains("tap") || lower.contains("swipe") ||
            lower.contains("whatsapp") || lower.contains("settings") || lower.contains("call") ||
            lower.contains("sms") || lower.contains("message") || lower.contains("contact") -> AgentType.UI_AGENT

            // Default to general
            else -> AgentType.GENERAL_CHAT
        }
    }

    // ============================================================
    // 🔧 Shared Utilities
    // ============================================================

    fun setModel(modelId: String) {
        _selectedModel.value = modelId
        SecureKeyStore.setPrimaryModel(context, modelId)
    }

    fun clearChat() {
        viewModelScope.launch {
            dao.clearAll()
        }
    }

    fun resendMessage(messageId: Long) {
        viewModelScope.launch {
            val all = dao.getAllMessagesOnce()
            val msg = all.find { it.id == messageId } ?: return@launch
            sendMessage(msg.content, msg.getAttachments())
        }
    }

    private fun buildChatHistory(messages: List<ChatMessage>): List<Map<String, String>> {
        return messages.map { msg ->
            mapOf(
                "role" to msg.role,
                "content" to msg.content
            )
        }
    }

    private fun ChatMessage.getAttachments(): List<Attachment> {
        if (attachments.isBlank()) return emptyList()
        return try {
            attachments.split("\n").mapNotNull { Attachment.fromUri(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================================================
    // 🔒 Action Permission System
    // ============================================================

    /**
     * Actions that require user permission before execution.
     */
    private val PERMISSION_REQUIRED_ACTIONS = setOf(
        "write_file", "delete_path", "move_file", "create_dir",
        "termux_run", "termux_write_file", "termux_modify_file",
        "http_post"
    )

    /**
     * Set a pending action that requires user authorization (legacy path).
     */
    fun setPendingAction(actionType: String, target: String, details: String, fullJson: String) {
        if (actionType in PERMISSION_REQUIRED_ACTIONS) {
            _pendingAction.value = PendingAction(actionType, target, details, fullJson)
        }
    }

    /**
     * Handle user's Allow/Deny response for pending action.
     * Works for both legacy and tool-calling paths.
     */
    fun handleActionPermission(isAllowed: Boolean) {
        val action = _pendingAction.value ?: return
        _pendingAction.value = null

        // If tool-calling path: resume the CompletableDeferred
        val deferred = pendingActionDeferred
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(isAllowed)
            pendingActionDeferred = null
            return
        }

        // Legacy path: execute directly
        viewModelScope.launch {
            if (isAllowed) {
                try {
                    val result = ActionExecutor.executeFromJson(action.fullJson)
                    dao.insertMessage(
                        ChatMessage(
                            role = "system",
                            content = "✅ Action allowed: ${action.actionType} on ${action.target}\nResult: $result"
                        )
                    )
                } catch (e: Exception) {
                    dao.insertMessage(
                        ChatMessage(
                            role = "system",
                            content = "❌ Action failed: ${e.message}"
                        )
                    )
                }
            } else {
                dao.insertMessage(
                    ChatMessage(
                        role = "system",
                        content = "🔒 Action denied by user: ${action.actionType} on ${action.target}"
                    )
                )
            }
        }
    }

    /**
     * Clear any pending action.
     */
    fun clearPendingAction() {
        _pendingAction.value = null
        pendingActionDeferred?.complete(false)
        pendingActionDeferred = null
    }
}
