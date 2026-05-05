package dev.krinry.jarvis.ui.chat

import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.krinry.jarvis.data.chat.Attachment
import dev.krinry.jarvis.data.chat.ChatMessage
import dev.krinry.jarvis.data.chat.getAttachments
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatMessageItem(message: ChatMessage, onAttachmentClick: (Attachment) -> Unit = {}) {
    val isUser = message.role == "user"
    val isError = message.isError
    var showThinking by remember { mutableStateOf(false) }

    // 🔥 FIX: Live Streaming & Think Parser (proper multi-line search)
    val parsedData = remember(message.content, isUser) {
        val raw = message.content
        if (isUser) return@remember Pair(null, raw)

        val thinkStart = raw.indexOf("<think>")
        if (thinkStart == -1) {
            return@remember Pair(null, raw)
        }

        // Search for closing tag AFTER the opening tag position
        val thinkEnd = raw.indexOf("</think>", thinkStart)
        if (thinkEnd != -1) {
            val thinkingText = raw.substring(thinkStart + 7, thinkEnd).trim()
            val cleanText = raw.substring(0, thinkStart).trim() + "\n" + raw.substring(thinkEnd + 8).trim()
            Pair(thinkingText, cleanText.trim())
        } else {
            val thinkingText = raw.substring(thinkStart + 7).trim()
            val cleanText = raw.substring(0, thinkStart).trim()
            Pair(thinkingText, cleanText)
        }
    }

    val thinkingText = parsedData.first
    val cleanContent = parsedData.second

    // 🔥 FIX: Theme Adaptive Colors for User Bubble
    val bubbleColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer // Light me light blue, Dark me dark blue/gray
        else -> Color.Transparent
    }

    val textColor = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    val cornerRadius = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (isUser) 20.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 20.dp
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isUser) 48.dp else 8.dp,
                end = if (isUser) 8.dp else 24.dp,
                top = 8.dp,
                bottom = 8.dp
            ),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .then(if (isUser) Modifier.widthIn(max = 340.dp) else Modifier.fillMaxWidth())
                .then(
                    if (isUser || isError) {
                        Modifier
                            .clip(cornerRadius)
                            .background(bubbleColor)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    } else {
                        Modifier.padding(vertical = 4.dp)
                    }
                )
        ) {

            // 💭 THINKING BOX UI 💭
            if (!isUser && thinkingText != null) {
                Surface(
                    onClick = { showThinking = !showThinking },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .wrapContentWidth()
                        .padding(bottom = if (cleanContent.isNotBlank()) 12.dp else 0.dp)
                ) {
                    Column(modifier = Modifier.animateContentSize()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val infiniteTransition = rememberInfiniteTransition(label = "thinking")
                            val scale by infiniteTransition.animateFloat(
                                initialValue = 1f, targetValue = 1.2f,
                                animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
                                label = "scale"
                            )
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .scale(if (cleanContent.isEmpty()) scale else 1f) // Animation stops when output starts
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (cleanContent.isEmpty()) "Thinking..." else "Thought Process",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = if (showThinking) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle Thinking",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        if (showThinking) {
                            SelectionContainer {
                                Text(
                                    text = thinkingText,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 📎 ATTACHMENTS 📎
            val attachments = message.getAttachments()
            if (attachments.isNotEmpty()) {
                AttachmentsRow(attachments, onAttachmentClick)
                if (cleanContent.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // ✍️ MAIN CONTENT (Using Premium Markdown Library) ✍️
            if (cleanContent.isNotBlank()) {
                SelectionContainer {
                    if (isUser) {
                        Text(
                            text = cleanContent,
                            color = textColor,
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                    } else {
                        // This library handles live streaming, code blocks, tables flawlessly!
                        Markdown(
                            content = cleanContent,
                            colors = markdownColor(
                                text = textColor,
                                codeText = MaterialTheme.colorScheme.onSurfaceVariant,
                                codeBackground = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            typography = markdownTypography(
                                text = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 🕒 TIMESTAMP & MODEL NAME 🕒
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTime(message.timestamp),
                    fontSize = 11.sp,
                    color = textColor.copy(alpha = 0.5f)
                )
                if (!isUser && message.modelUsed != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = message.modelUsed!!,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentsRow(attachments: List<Attachment>, onClick: (Attachment) -> Unit) {
    val flowRowModifier = Modifier.fillMaxWidth()
    if (attachments.size == 1) {
        AttachmentPreview(attachment = attachments[0], modifier = Modifier.fillMaxWidth(), onClick = onClick)
    } else {
        FlowRow(
            modifier = flowRowModifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 3
        ) {
            attachments.forEach { att ->
                AttachmentPreview(attachment = att, modifier = Modifier.widthIn(max = 90.dp, min = 80.dp), onClick = onClick)
            }
        }
    }
}

@Composable
private fun AttachmentPreview(attachment: Attachment, modifier: Modifier = Modifier, onClick: (Attachment) -> Unit) {
    when (attachment) {
        is Attachment.Image -> {
            AsyncImage(
                model = Uri.parse(attachment.uri), contentDescription = "Image",
                modifier = modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { onClick(attachment) },
                contentScale = ContentScale.Crop
            )
        }
        is Attachment.Audio -> {
            Box(modifier = modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).clickable { onClick(attachment) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AudioFile, "Audio", tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(24.dp))
            }
        }
        is Attachment.Pdf -> {
            Box(modifier = modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer).clickable { onClick(attachment) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PictureAsPdf, "PDF", tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(24.dp))
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}