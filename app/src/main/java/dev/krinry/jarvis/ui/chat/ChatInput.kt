package dev.krinry.jarvis.ui.chat

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.ai.ModelInfo
import dev.krinry.jarvis.ui.theme.JarvisPrimary
import dev.krinry.jarvis.ui.theme.JarvisSecondary
import kotlinx.coroutines.launch

/**
 * Modern chat input component similar to the React PromptInput
 * Features:
 * - Auto-expanding textarea
 * - Submit on Enter (Shift+Enter for new line)
 * - Loading state with stop button
 * - Rounded card design
 * - Clean modern UI
 */
@Composable
fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
    placeholder: String = "Ask me anything...",
    enabled: Boolean = true,
    maxLines: Int = 5
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val containerColor = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Text input area
            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = placeholderColor,
                        fontSize = 16.sp,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled && !isLoading,
                    textStyle = TextStyle(
                        color = textColor,
                        fontSize = 16.sp,
                        lineHeight = 20.sp
                    ),
                    cursorBrush = SolidColor(JarvisPrimary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (value.isNotBlank() && !isLoading) {
                                onSubmit()
                                keyboardController?.hide()
                            }
                        }
                    ),
                    maxLines = maxLines,
                    decorationBox = { innerTextField ->
                        Box {
                            innerTextField()
                        }
                    }
                )
            }

            // Action row with send/stop button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Send/Stop button
                Surface(
                    onClick = {
                        if (isLoading) {
                            // TODO: Implement stop generation
                        } else if (value.isNotBlank()) {
                            onSubmit()
                        }
                    },
                    enabled = value.isNotBlank() || isLoading,
                    shape = CircleShape,
                    color = if (value.isNotBlank() || isLoading) JarvisPrimary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        AnimatedContent(
                            targetState = isLoading,
                            transitionSpec = { fadeIn().togetherWith(fadeOut()) },
                            label = "SendStopButton"
                        ) { loading ->
                            if (loading) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop generation",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send message",
                                    tint = if (value.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Alternative compact chat input for bottom bar placement
 * Matches the design from the screenshot with dark theme
 * Includes mode selector (full/read/none) and AI model selector
 */
@Composable
fun CompactChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
    placeholder: String = "Ask anything",
    enabled: Boolean = true,
    onMicClick: (() -> Unit)? = null,
    onAttachClick: (() -> Unit)? = null,
    selectedModel: String = "",
    onModelChange: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    
    val darkSurface = Color(0xFF2D2D2D)
    val darkBorder = Color(0xFF404040)
    val tagBackground = Color(0xFF3D3D3D)
    
    // Mode options
    val modeOptions = listOf("full", "read", "none")
    var selectedMode by remember { mutableStateOf("full") }
    
    // Model dropdown
    var showModelDropdown by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    
    // Fetch models on first composition
    LaunchedEffect(Unit) {
        scope.launch {
            availableModels = GroqApiClient.fetchAvailableModels(context)
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(darkSurface)
            .border(1.dp, darkBorder, RoundedCornerShape(24.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top row with mode selector and model selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mode selector
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    modeOptions.forEach { mode ->
                        Surface(
                            onClick = { selectedMode = mode },
                            shape = RoundedCornerShape(16.dp),
                            color = if (selectedMode == mode) JarvisPrimary else tagBackground,
                            modifier = Modifier.height(28.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = mode,
                                    color = if (selectedMode == mode) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedMode == mode) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
                
                // Model selector dropdown
                Box {
                    Surface(
                        onClick = { showModelDropdown = !showModelDropdown },
                        shape = RoundedCornerShape(16.dp),
                        color = tagBackground,
                        modifier = Modifier.height(28.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = selectedModel.ifEmpty { "SWE-1.6" },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select model",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    
                    DropdownMenu(
                        expanded = showModelDropdown,
                        onDismissRequest = { showModelDropdown = false },
                        modifier = Modifier.background(darkSurface)
                    ) {
                        availableModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        model.name,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 13.sp
                                    )
                                },
                                onClick = {
                                    onModelChange(model.id)
                                    showModelDropdown = false
                                },
                                leadingIcon = if (model.id == selectedModel) {
                                    { Icon(Icons.Default.Check, null, tint = JarvisPrimary, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }
                }
            }
            
            // Bottom row with input field and action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Plus/Attach button
                Surface(
                    onClick = { onAttachClick?.invoke() },
                    shape = CircleShape,
                    color = Color.Transparent,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Attach",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                // Text field
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled && !isLoading,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp
                    ),
                    cursorBrush = SolidColor(JarvisPrimary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (value.isNotBlank() && !isLoading) {
                                onSubmit()
                                keyboardController?.hide()
                            }
                        }
                    ),
                    maxLines = 4,
                    decorationBox = { innerTextField ->
                        Box {
                            if (value.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    fontSize = 16.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                
                // Mic or Send button
                Surface(
                    onClick = {
                        if (isLoading) {
                            // Stop generation
                        } else if (value.isBlank() && onMicClick != null) {
                            onMicClick()
                        } else if (value.isNotBlank()) {
                            onSubmit()
                        }
                    },
                    enabled = value.isNotBlank() || isLoading || onMicClick != null,
                    shape = CircleShape,
                    color = if (value.isNotBlank() || isLoading) JarvisPrimary else tagBackground,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        AnimatedContent(
                            targetState = Triple(isLoading, value.isBlank(), onMicClick != null),
                            transitionSpec = { fadeIn().togetherWith(fadeOut()) },
                            label = "ActionButton"
                        ) { (loading, isEmpty, hasMic) ->
                            when {
                                loading -> Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                isEmpty && hasMic -> Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Voice input",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                else -> Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = if (value.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * New chat input with chips design matching the HTML interface
 * Features:
 * - Rounded-3xl container design
 * - Add button, Code button
 * - Mic button, Send/Stop button
 * - Mode selector (full/read/none)
 * - AI model selector
 */
@Composable
fun ChatInputWithChips(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
    placeholder: String = "Ask anything",
    enabled: Boolean = true,
    onMicClick: (() -> Unit)? = null,
    onAttachClick: (() -> Unit)? = null,
    selectedModel: String = "",
    onModelChange: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    // Light theme colors matching HTML design
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    // Mode options
    val modeOptions = listOf("full", "read", "none")
    var selectedMode by remember { mutableStateOf("full") }

    // Model dropdown
    var showModelDropdown by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }

    // Fetch models on first composition
    LaunchedEffect(Unit) {
        scope.launch {
            availableModels = GroqApiClient.fetchAvailableModels(context)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Mode and Model row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mode chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                modeOptions.forEach { mode ->
                    Surface(
                        onClick = { selectedMode = mode },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selectedMode == mode) JarvisPrimary else surfaceVariant,
                        modifier = Modifier.height(28.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = mode,
                                color = if (selectedMode == mode) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                fontWeight = if (selectedMode == mode) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // Model selector dropdown
            Box {
                Surface(
                    onClick = { showModelDropdown = !showModelDropdown },
                    shape = RoundedCornerShape(16.dp),
                    color = surfaceVariant,
                    modifier = Modifier.height(28.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = selectedModel.ifEmpty { "SWE-1.6" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select model",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = showModelDropdown,
                    onDismissRequest = { showModelDropdown = false },
                    modifier = Modifier.background(surfaceVariant)
                ) {
                    availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    model.name,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp
                                )
                            },
                            onClick = {
                                onModelChange(model.id)
                                showModelDropdown = false
                            },
                            leadingIcon = if (model.id == selectedModel) {
                                { Icon(Icons.Default.Check, null, tint = JarvisPrimary, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }
            }
        }

        // Main input container - rounded-3xl
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Text field
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp, max = 120.dp),
                    enabled = enabled && !isLoading,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp
                    ),
                    cursorBrush = SolidColor(JarvisPrimary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (value.isNotBlank() && !isLoading) {
                                onSubmit()
                                keyboardController?.hide()
                            }
                        }
                    ),
                    maxLines = 4,
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            if (value.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                                    fontSize = 16.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                // Bottom row with buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left side: Add and Code buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Add button
                        Surface(
                            onClick = { onAttachClick?.invoke() },
                            shape = CircleShape,
                            color = Color.Transparent,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Attach",
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Code button
                        Surface(
                            onClick = { /* TODO: Code mode */ },
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            modifier = Modifier.height(32.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Code,
                                    contentDescription = "Code",
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Code",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }

                    // Right side: Mic and Send/Stop buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mic button
                        Surface(
                            onClick = { onMicClick?.invoke() },
                            shape = CircleShape,
                            color = Color.Transparent,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Voice",
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Send/Stop button
                        Surface(
                            onClick = {
                                if (isLoading) {
                                    // Stop generation
                                } else if (value.isNotBlank()) {
                                    onSubmit()
                                }
                            },
                            enabled = value.isNotBlank() || isLoading,
                            shape = CircleShape,
                            color = if (value.isNotBlank() || isLoading) JarvisPrimary else surfaceVariant,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                AnimatedContent(
                                    targetState = isLoading,
                                    transitionSpec = { fadeIn().togetherWith(fadeOut()) },
                                    label = "ActionButton"
                                ) { loading ->
                                    if (loading) {
                                        Icon(
                                            imageVector = Icons.Default.Stop,
                                            contentDescription = "Stop",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "Send",
                                            tint = if (value.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
