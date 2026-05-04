package dev.krinry.jarvis.ui.chat

import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
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
    val context = LocalContext.current

    val bubbleColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = if (isUser) 16.dp else 4.dp,
                        topEnd = if (isUser) 4.dp else 16.dp,
                        bottomStart = 16.dp,
                        bottomEnd = 16.dp
                    )
                )
                .background(bubbleColor)
                .padding(12.dp)
                .animateContentSize()
        ) {
            // Attachments
            val attachments = message.getAttachments()
            if (attachments.isNotEmpty()) {
                AttachmentsRow(attachments, onAttachmentClick)
                if (message.content.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Content
            if (message.content.isNotBlank()) {
                Text(
                    text = message.content,
                    color = textColor,
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
            }

            // Timestamp + model
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTime(message.timestamp),
                    fontSize = 11.sp,
                    color = textColor.copy(alpha = 0.6f)
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
private fun AttachmentsRow(
    attachments: List<Attachment>,
    onClick: (Attachment) -> Unit
) {
    val flowRowModifier = Modifier.fillMaxWidth()

    if (attachments.size == 1) {
        // Single attachment - large preview
        AttachmentPreview(attachment = attachments[0], modifier = Modifier.fillMaxWidth(), onClick = onClick)
    } else {
        // Multiple attachments - grid
        FlowRow(
            modifier = flowRowModifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxItemsInEachRow = 3
        ) {
            attachments.forEach { att ->
                AttachmentPreview(
                    attachment = att,
                    modifier = Modifier.widthIn(max = 90.dp, min = 80.dp),
                    onClick = onClick
                )
            }
        }
    }
}

@Composable
private fun AttachmentPreview(
    attachment: Attachment,
    modifier: Modifier = Modifier,
    onClick: (Attachment) -> Unit
) {
    val context = LocalContext.current

    when (attachment) {
        is Attachment.Image -> {
            AsyncImage(
                model = Uri.parse(attachment.uri),
                contentDescription = "Image attachment",
                modifier = modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClick(attachment) },
                contentScale = ContentScale.Crop
            )
        }
        is Attachment.Audio -> {
            Box(
                modifier = modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .clickable { onClick(attachment) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AudioFile,
                    contentDescription = "Audio",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        is Attachment.Pdf -> {
            Box(
                modifier = modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable { onClick(attachment) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = "PDF",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
