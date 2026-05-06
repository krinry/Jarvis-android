package dev.krinry.jarvis.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krinry.jarvis.ui.theme.JarvisPrimary
import dev.krinry.jarvis.ui.theme.JarvisSecondary

/**
 * Permission Authorization Box — Shows when AI wants to execute a sensitive action.
 * Appears similar to Thinking block but with Allow/Deny buttons.
 */
@Composable
fun PermissionAuthorizationBox(
    actionType: String,
    target: String,
    details: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = true) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = JarvisSecondary.copy(alpha = 0.4f)
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                // Header - Warning icon + Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Permission Required",
                        tint = JarvisSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Permission Required",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = JarvisSecondary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action details in code block style
                SelectionContainer {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.05f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                        ) {
                            // Action type
                            Row {
                                Text(
                                    text = "Action: ",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = actionType,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = JarvisPrimary
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Target
                            Row {
                                Text(
                                    text = "Target: ",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = target,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            if (details.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Details: $details",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Allow / Deny Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    // Deny Button (Red)
                    TextButton(
                        onClick = onDeny,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color(0xFFE53935)
                        )
                    ) {
                        Text(
                            text = "Don't Allow",
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Allow Button (Green)
                    Button(
                        onClick = onAllow,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF43A047)
                        )
                    ) {
                        Text(
                            text = "Allow",
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}