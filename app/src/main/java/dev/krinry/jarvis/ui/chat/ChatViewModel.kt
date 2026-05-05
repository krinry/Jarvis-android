package dev.krinry.jarvis.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.data.chat.Attachment
import dev.krinry.jarvis.data.chat.ChatDao
import dev.krinry.jarvis.data.chat.ChatDatabase
import dev.krinry.jarvis.data.chat.ChatMessage
import dev.krinry.jarvis.security.SecureKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val dao: ChatDao = ChatDatabase.getInstance(application).chatDao()
    private val context = application.applicationContext

    val messages: StateFlow<List<ChatMessage>> = dao.getAllMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedModel = MutableStateFlow(SecureKeyStore.getPrimaryModel(context))
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

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
                // Collect recent messages for context
                val recent = dao.getLastMessages(10)
                val chatHistory = buildChatHistory(recent)

                // Get selected model
                val model = _selectedModel.value

                // Call LLM
                val response = GroqApiClient.chat(
                    context = context,
                    messages = chatHistory + mapOf(
                        "role" to "user",
                        "content" to text
                    )
                )

                // Insert assistant response
                val assistantMsg = ChatMessage(
                    role = "assistant",
                    content = response ?: "No response from AI",
                    modelUsed = model,
                    isError = response == null
                )
                dao.insertMessage(assistantMsg)

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
}
