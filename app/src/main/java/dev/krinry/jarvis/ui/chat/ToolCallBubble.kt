package dev.krinry.jarvis.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krinry.jarvis.ui.theme.*

/**
 * ToolCallBubble — Shows a tool call in the chat with icon, name, args, result, and status.
 *
 * Three states:
 *   🔄 Running  — animated pulse, "Executing..."
 *   ✅ Success  — green accent, result text
 *   ❌ Failed   — red accent, error text
 *
 * Collapsible: tap to expand/collapse arguments and full result.
 */
@Composable
fun ToolCallBubble(
    toolName: String,
    arguments: String,
    result: String?,
    status: ToolCallStatus,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val (accentColor, statusIcon, statusText) = when (status) {
        ToolCallStatus.RUNNING -> Triple(
            JarvisSecondary,
            Icons.Default.Sync,
            "Executing..."
        )
        ToolCallStatus.SUCCESS -> Triple(
            JarvisSuccess,
            Icons.Default.CheckCircle,
            "Done"
        )
        ToolCallStatus.FAILED -> Triple(
            JarvisError,
            Icons.Default.Error,
            "Failed"
        )
        ToolCallStatus.DENIED -> Triple(
            Color(0xFFE53935),
            Icons.Default.Block,
            "Denied"
        )
    }

    val toolIcon = getToolIcon(toolName)
    val toolCategory = getToolCategory(toolName)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = accentColor.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow))
                .padding(10.dp)
        ) {
            // ── Header Row: Icon + Tool Name + Status ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Tool category icon
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = toolIcon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Tool name + category
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = toolName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = toolCategory,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                // Status indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (status == ToolCallStatus.RUNNING) {
                        // Animated pulse for running
                        val infiniteTransition = rememberInfiniteTransition(label = "tool_pulse")
                        val pulseScale by infiniteTransition.animateFloat(
                            initialValue = 0.8f,
                            targetValue = 1.2f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(500),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulse"
                        )
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier
                                .size(14.dp)
                                .scale(pulseScale)
                        )
                    } else {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = accentColor
                    )
                }

                // Expand/collapse arrow
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Toggle details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(16.dp)
                )
            }

            // ── Short preview (always visible) ──
            if (arguments.isNotBlank() && !expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = arguments.take(80).replace("\n", " ") + if (arguments.length > 80) "…" else "",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── Expanded details ──
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))

                // Arguments block
                if (arguments.isNotBlank()) {
                    Text(
                        text = "Arguments",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    SelectionContainer {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = arguments.take(500),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // Result block
                if (!result.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Result",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    SelectionContainer {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (status == ToolCallStatus.SUCCESS)
                                JarvisSuccess.copy(alpha = 0.08f)
                            else
                                JarvisError.copy(alpha = 0.08f)
                        ) {
                            Text(
                                text = result.take(500),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (status == ToolCallStatus.SUCCESS)
                                    JarvisSuccess
                                else
                                    JarvisError,
                                modifier = Modifier.padding(8.dp),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

enum class ToolCallStatus {
    RUNNING, SUCCESS, FAILED, DENIED
}

// =========================================================================
// Icon mapping for tool categories
// =========================================================================

private fun getToolIcon(toolName: String): ImageVector = when (toolName) {
    // File system
    "create_dir", "list_files" -> Icons.Default.Folder
    "write_file", "read_file" -> Icons.Default.Description
    "delete_path" -> Icons.Default.Delete
    "move_file" -> Icons.Default.DriveFileMove
    // Terminal
    "termux_run", "termux_write_file", "termux_read_file", "termux_modify_file" -> Icons.Default.Terminal
    // Network
    "http_get", "http_post" -> Icons.Default.Http
    "open_browser", "open_url" -> Icons.Default.Language
    "execute_javascript" -> Icons.Default.Code
    "close_browser" -> Icons.Default.Close
    // Device
    "call" -> Icons.Default.Call
    "send_sms" -> Icons.Default.Sms
    "set_alarm", "set_timer" -> Icons.Default.Alarm
    "navigate" -> Icons.Default.Navigation
    "search_web" -> Icons.Default.Search
    "flashlight" -> Icons.Default.FlashlightOn
    "set_volume" -> Icons.Default.VolumeUp
    "find_contact" -> Icons.Default.Contacts
    "read_notifications", "dismiss_notification", "open_notifications" -> Icons.Default.Notifications
    // UI
    "click", "tap_xy", "long_press" -> Icons.Default.TouchApp
    "type" -> Icons.Default.Keyboard
    "swipe", "scroll_down", "scroll_up" -> Icons.Default.Swipe
    "back" -> Icons.Default.ArrowBack
    "home" -> Icons.Default.Home
    "recent" -> Icons.Default.History
    "open_app" -> Icons.Default.Apps
    "screenshot", "analyze_screen" -> Icons.Default.Screenshot
    "copy", "paste", "select_all", "read_clipboard" -> Icons.Default.ContentCopy
    // AI
    "delegate_ai" -> Icons.Default.SmartToy
    // Control
    "ask_user" -> Icons.Default.QuestionAnswer
    "wait" -> Icons.Default.HourglassEmpty
    "task_complete" -> Icons.Default.CheckCircle
    else -> Icons.Default.Build
}

private fun getToolCategory(toolName: String): String = when (toolName) {
    "create_dir", "write_file", "read_file", "list_files", "delete_path", "move_file" -> "📁 File System"
    "termux_run", "termux_write_file", "termux_read_file", "termux_modify_file" -> "🖥️ Terminal"
    "http_get", "http_post" -> "🌐 Network"
    "open_browser", "execute_javascript", "close_browser", "open_url" -> "🌐 Browser"
    "call", "send_sms", "set_alarm", "set_timer", "navigate", "search_web",
    "flashlight", "set_volume", "find_contact", "read_notifications",
    "dismiss_notification" -> "📞 Device"
    "click", "type", "tap_xy", "long_press", "swipe", "scroll_down", "scroll_up",
    "back", "home", "recent", "open_app", "screenshot", "analyze_screen",
    "copy", "paste", "select_all", "open_notifications", "read_clipboard" -> "🖱️ UI Control"
    "delegate_ai" -> "🤖 AI Delegation"
    "ask_user", "wait", "task_complete" -> "❓ Control"
    else -> "🔧 Tool"
}
