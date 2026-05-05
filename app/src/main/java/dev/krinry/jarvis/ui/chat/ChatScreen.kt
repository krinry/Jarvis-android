package dev.krinry.jarvis.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.data.chat.Attachment
import dev.krinry.jarvis.security.SecureKeyStore
import dev.krinry.jarvis.ui.settings.startBubbleService
import dev.krinry.jarvis.ui.settings.stopBubbleService
import dev.krinry.jarvis.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onThemeToggle: () -> Unit,
    isDarkMode: Boolean,
    viewModel: ChatViewModel = viewModel()
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    var inputText by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf(listOf<Attachment>()) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var jarvisEnabled by remember { mutableStateOf(SecureKeyStore.isAgentEnabled(context)) }

    val selectedProvider = remember { GroqApiClient.getActiveProvider(context) }
    var selectedModel by remember { mutableStateOf(SecureKeyStore.getPrimaryModel(context).ifEmpty { selectedProvider.defaultModel }) }

    // Scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Image picker
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { pendingAttachments = pendingAttachments + Attachment.Image(it.toString()) }
    }

    // Audio picker
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { pendingAttachments = pendingAttachments + Attachment.Audio(it.toString()) }
    }

    // PDF picker
    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { pendingAttachments = pendingAttachments + Attachment.Pdf(it.toString()) }
    }

    // Mic permission
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Microphone permission needed", Toast.LENGTH_SHORT).show()
        }
    }

    val hasMicPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    // Voice typing (speech to text)
    val voiceInputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            data?.firstOrNull()?.let { recognizedText ->
                inputText = if (inputText.isBlank()) recognizedText else "$inputText $recognizedText"
            }
        }
    }

    fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
        }
        try {
            voiceInputLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Voice input not available", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Jarvis AI", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.Menu, contentDescription = "History")
                    }
                },
                actions = {
                    // Jarvis toggle (agent on/off) - show in both themes
                    IconButton(onClick = {
                        val newState = !jarvisEnabled
                        SecureKeyStore.setAgentEnabled(context, newState)
                        jarvisEnabled = newState
                        if (newState) startBubbleService(context) else stopBubbleService(context)
                    }) {
                        Icon(
                            imageVector = if (jarvisEnabled) Icons.Default.PowerSettingsNew else Icons.Default.PowerOff,
                            contentDescription = if (jarvisEnabled) "Jarvis On" else "Jarvis Off",
                            tint = if (jarvisEnabled) JarvisSecondary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onThemeToggle) {
                        Icon(
                            imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle Theme"
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
    ) {
        if (pendingAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pendingAttachments.forEachIndexed { idx, att ->
                    AttachmentChip(att) {
                        pendingAttachments = pendingAttachments.toMutableList().apply { removeAt(idx) }
                    }
                }
            }
        }

        ScreenshotStyleChatInput(
            value = inputText,
            onValueChange = { inputText = it },
            onSubmit = {
                if (inputText.isNotBlank() || pendingAttachments.isNotEmpty()) {
                    viewModel.sendMessage(inputText, pendingAttachments)
                    inputText = ""
                    pendingAttachments = emptyList()
                    keyboardController?.hide()
                }
            },
            isLoading = isLoading,
            placeholder = "Ask anything",
            enabled = true,
            onMicClick = { startVoiceInput() },
            onAttachClick = { showAttachSheet = true },
            selectedModel = selectedModel,
            onModelChange = { model ->
                selectedModel = model
                viewModel.setModel(model)
            },
            // YAHAN BHI FIX HAI: top/bottom padding hata di hai taaki box bottom edge ko touch kare
            modifier = Modifier.fillMaxWidth() 
        )
    }
},
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (messages.isEmpty()) {
                EmptyChatPlaceholder()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        bottom = 80.dp,
                        start = 16.dp,
                        end = 16.dp
                    )
                ) {
                    items(messages, key = { it.id }) { msg ->
                        ChatMessageItem(message = msg)
                    }
                    if (isLoading) {
                        item {
                            LoadingBubble()
                        }
                    }
                }
            }
        }
    }

    // Attachment bottom sheet
    if (showAttachSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachSheet = false }) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Attach", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AttachOption(
                        icon = Icons.Default.Image,
                        label = "Image",
                        color = JarvisSecondary
                    ) {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        showAttachSheet = false
                    }
                    AttachOption(
                        icon = Icons.Default.AudioFile,
                        label = "Audio",
                        color = JarvisAccent
                    ) {
                        audioPicker.launch(arrayOf("audio/*"))
                        showAttachSheet = false
                    }
                    AttachOption(
                        icon = Icons.Default.PictureAsPdf,
                        label = "PDF",
                        color = JarvisError
                    ) {
                        pdfPicker.launch(arrayOf("application/pdf"))
                        showAttachSheet = false
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AttachmentChip(attachment: Attachment, onRemove: () -> Unit) {
    val label = when (attachment) {
        is Attachment.Image -> "IMG"
        is Attachment.Audio -> "AUD"
        is Attachment.Pdf -> "PDF"
    }
    val tint = when (attachment) {
        is Attachment.Image -> JarvisSecondary
        is Attachment.Audio -> JarvisAccent
        is Attachment.Pdf -> JarvisError
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = tint.copy(alpha = 0.12f),
        modifier = Modifier.clickable { onRemove() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 11.sp, color = tint, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.Default.Close, null, tint = tint.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun AttachOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun EmptyChatPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(JarvisPrimary.copy(alpha = 0.3f), JarvisSecondary.copy(alpha = 0.2f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.SmartToy, null, tint = JarvisPrimary, modifier = Modifier.size(40.dp))
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            "How can I help you today?",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Type a message or tap the mic to speak",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun LoadingBubble() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(3) { index ->
                    val anim = remember { androidx.compose.animation.core.Animatable(0.4f) }
                    LaunchedEffect(Unit) {
                        anim.animateTo(
                            targetValue = 1f,
                            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                animation = androidx.compose.animation.core.tween(600, delayMillis = index * 150),
                                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                            )
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = anim.value)
                            )
                    )
                }
            }
        }
    }
}
