package dev.krinry.jarvis.data.chat

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val role: String,
    val content: String,
    val attachments: String = "", // JSON string of attachment URIs
    val modelUsed: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false
)

fun ChatMessage.getAttachments(): List<Attachment> {
    if (attachments.isBlank()) return emptyList()
    return try {
        val parts = attachments.split("\n")
        parts.mapNotNull { Attachment.fromUri(it) }
    } catch (_: Exception) {
        emptyList()
    }
}

fun ChatMessage.withAttachments(atts: List<Attachment>): ChatMessage {
    return copy(attachments = atts.joinToString("\n") { it.toString() })
}
