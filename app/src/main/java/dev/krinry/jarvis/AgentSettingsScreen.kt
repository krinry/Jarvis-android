package dev.krinry.jarvis

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.security.SecureKeyStore
import dev.krinry.jarvis.service.AutoAgentService
import dev.krinry.jarvis.service.FloatingBubbleService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }

    // State
    val isAccessibilityEnabled = remember(refreshKey) { checkAccessibilityEnabled(context) }
    val hasOverlayPermission = remember(refreshKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
    }
    var hasAudioPermission by remember(refreshKey) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
        if (isGranted) refreshKey++
    }
    var agentEnabled by remember { mutableStateOf(SecureKeyStore.isAgentEnabled(context)) }
    var groqApiKey by remember { mutableStateOf(SecureKeyStore.getGroqApiKey(context) ?: "") }
    var openRouterApiKey by remember { mutableStateOf(SecureKeyStore.getOpenRouterApiKey(context) ?: "") }
    var showGroqKeyDialog by remember { mutableStateOf(false) }
    var showOpenRouterKeyDialog by remember { mutableStateOf(false) }
    var useEdgeFunction by remember { mutableStateOf(SecureKeyStore.shouldUseEdgeFunction(context)) }
    var apiProvider by remember { mutableStateOf(SecureKeyStore.getApiProvider(context)) }
    var primaryModel by remember { mutableStateOf(SecureKeyStore.getPrimaryModel(context)) }
    var fallbackModel by remember { mutableStateOf(SecureKeyStore.getFallbackModel(context)) }
    var requestDelayMs by remember { mutableStateOf(SecureKeyStore.getRequestDelayMs(context)) }
    var showModelPicker by remember { mutableStateOf(false) }
    var modelPickerTarget by remember { mutableStateOf("primary") } // "primary" or "fallback"
    var showDelayDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(500)
        refreshKey++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Krinry AI Agent", fontWeight = FontWeight.Bold)
                        Text("Full device control with AI", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Hero card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF6C5CE7), Color(0xFFA29BFE))),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(24.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(48.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.SmartToy, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Jarvis Mode", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("Voice-control any app", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                        }
                        Switch(
                            checked = agentEnabled,
                            onCheckedChange = { enabled ->
                                agentEnabled = enabled
                                SecureKeyStore.setAgentEnabled(context, enabled)
                                if (enabled) startBubbleService(context) else stopBubbleService(context)
                                refreshKey++
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = Color.White.copy(alpha = 0.4f),
                                checkedThumbColor = Color.White
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Permissions
            SectionHeader("Required Permissions")

            PermissionCard(Icons.Default.Accessibility, "Accessibility Service",
                "Read and interact with apps", checkAccessibilityEnabled(context)) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            Spacer(Modifier.height(8.dp))
            PermissionCard(Icons.Default.OpenInNew, "Display Over Apps",
                "Floating AI bubble", hasOverlayPermission) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }
            Spacer(Modifier.height(8.dp))
            PermissionCard(Icons.Default.Mic, "Microphone",
                "Hear your voice commands", hasAudioPermission) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            Spacer(Modifier.height(16.dp))

            // === API Provider Selection ===
            SectionHeader("API Provider")

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderChip("Groq", apiProvider == "groq", Color(0xFFF97316)) {
                    apiProvider = "groq"
                    SecureKeyStore.setApiProvider(context, "groq")
                }
                ProviderChip("OpenRouter", apiProvider == "openrouter", Color(0xFF6366F1)) {
                    apiProvider = "openrouter"
                    SecureKeyStore.setApiProvider(context, "openrouter")
                }
            }

            Spacer(Modifier.height(12.dp))

            // === API Keys ===
            SectionHeader("API Keys")

            // Groq key
            SettingsCard(Icons.Default.Key, "Groq API Key",
                if (groqApiKey.isNotEmpty()) "••••${groqApiKey.takeLast(4)}" else "Not set",
                Color(0xFFF97316)
            ) { showGroqKeyDialog = true }

            Spacer(Modifier.height(8.dp))

            // OpenRouter key
            SettingsCard(Icons.Default.Key, "OpenRouter API Key",
                if (openRouterApiKey.isNotEmpty()) "••••${openRouterApiKey.takeLast(4)}" else "Not set (free models available!)",
                Color(0xFF6366F1)
            ) { showOpenRouterKeyDialog = true }

            Spacer(Modifier.height(8.dp))



            // === Model Selection ===
            SectionHeader("Model Selection")

            SettingsCard(Icons.Default.Psychology, "Primary Model",
                primaryModel.ifEmpty { "Default" }, Color(0xFF00B894)
            ) {
                modelPickerTarget = "primary"
                showModelPicker = true
            }

            Spacer(Modifier.height(8.dp))

            SettingsCard(Icons.Default.Psychology, "Fallback Model",
                fallbackModel.ifEmpty { "Default" }, Color(0xFF00B894)
            ) {
                modelPickerTarget = "fallback"
                showModelPicker = true
            }

            Spacer(Modifier.height(16.dp))

            // === Request Delay ===
            SectionHeader("Rate Limiting")

            SettingsCard(Icons.Default.Timer, "Request Delay",
                "${requestDelayMs}ms between API calls", Color(0xFFE17055)
            ) { showDelayDialog = true }

            Spacer(Modifier.height(16.dp))

            // Status
            if (agentEnabled) {
                val allReady = isAccessibilityEnabled && hasOverlayPermission && hasAudioPermission
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (allReady) Color(0xFF00B894).copy(alpha = 0.1f)
                        else Color(0xFFFF6B6B).copy(alpha = 0.1f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            if (allReady) "✅ Agent Active — Tap floating bubble to give command!"
                            else "⚠️ Grant all permissions above",
                            fontWeight = FontWeight.Medium, fontSize = 14.sp
                        )
                        if (allReady) {
                            Spacer(Modifier.height(8.dp))
                            Text("Try: \"WhatsApp pe Papa ko message bhejo\"",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // === Dialogs ===

    if (showGroqKeyDialog) {
        ApiKeyDialog("Groq API Key", groqApiKey,
            onSave = { key ->
                groqApiKey = key
                SecureKeyStore.saveGroqApiKey(context, key)
                showGroqKeyDialog = false
                Toast.makeText(context, "Groq key saved!", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showGroqKeyDialog = false }
        )
    }

    if (showOpenRouterKeyDialog) {
        ApiKeyDialog("OpenRouter API Key", openRouterApiKey,
            onSave = { key ->
                openRouterApiKey = key
                SecureKeyStore.saveOpenRouterApiKey(context, key)
                showOpenRouterKeyDialog = false
                Toast.makeText(context, "OpenRouter key saved!", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showOpenRouterKeyDialog = false }
        )
    }

    if (showDelayDialog) {
        DelayPickerDialog(
            currentDelay = requestDelayMs,
            onSave = { delay ->
                requestDelayMs = delay
                SecureKeyStore.setRequestDelayMs(context, delay)
                showDelayDialog = false
            },
            onDismiss = { showDelayDialog = false }
        )
    }

    if (showModelPicker) {
        ModelPickerDialog(
            context = context,
            target = modelPickerTarget,
            onSelect = { modelId ->
                if (modelPickerTarget == "primary") {
                    primaryModel = modelId
                    SecureKeyStore.setPrimaryModel(context, modelId)
                } else {
                    fallbackModel = modelId
                    SecureKeyStore.setFallbackModel(context, modelId)
                }
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }
}

// =============================================================================
// === Reusable Components ===
// =============================================================================

@Composable
private fun SectionHeader(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun SettingsCard(
    icon: ImageVector, title: String, subtitle: String,
    iconColor: Color, onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = iconColor)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
private fun ProviderChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) color else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.height(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(label, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector, title: String, description: String,
    isGranted: Boolean, onGrant: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFF6C5CE7))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isGranted) {
                Box(modifier = Modifier.size(32.dp).clip(CircleShape)
                    .background(Color(0xFF00B894)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            } else {
                Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C5CE7)),
                    shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("Grant", fontSize = 13.sp)
                }
            }
        }
    }
}

// =============================================================================
// === Dialogs ===
// =============================================================================

@Composable
private fun ApiKeyDialog(title: String, currentKey: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var tempKey by remember { mutableStateOf(currentKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = tempKey, onValueChange = { tempKey = it },
                label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(tempKey) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DelayPickerDialog(currentDelay: Long, onSave: (Long) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(500L, 1000L, 1500L, 2000L, 3000L, 5000L, 8000L, 10000L)
    var selected by remember { mutableStateOf(currentDelay) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request Delay") },
        text = {
            Column {
                Text("API calls ke beech me kitna wait kare:", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                options.forEach { delay ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selected = delay }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == delay, onClick = { selected = delay })
                        Spacer(Modifier.width(8.dp))
                        val label = when {
                            delay >= 1000 -> "${delay / 1000.0}s"
                            else -> "${delay}ms"
                        }
                        Text(label, fontWeight = if (selected == delay) FontWeight.Bold else FontWeight.Normal)
                        if (delay == 2000L) {
                            Spacer(Modifier.width(8.dp))
                            Text("(recommended)", fontSize = 12.sp, color = Color(0xFF00B894))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerDialog(
    context: Context, target: String,
    onSelect: (String) -> Unit, onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<GroqApiClient.ModelInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showFreeOnly by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch {
            isLoading = true
            models = GroqApiClient.fetchAvailableModels(context)
            isLoading = false
        }
    }

    val filteredModels = models.filter { model ->
        val matchesSearch = searchQuery.isEmpty() ||
            model.id.contains(searchQuery, ignoreCase = true) ||
            model.name.contains(searchQuery, ignoreCase = true)
        val matchesFree = !showFreeOnly || model.isFree
        matchesSearch && matchesFree
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target == "primary") "Primary Model" else "Fallback Model") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp)) {
                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search models...") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(8.dp))

                // Free filter
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showFreeOnly, onCheckedChange = { showFreeOnly = it })
                    Text("Only free models", fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${filteredModels.size} models", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(8.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (filteredModels.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp),
                        contentAlignment = Alignment.Center) {
                        Text("No models found\nCheck API key", fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(filteredModels) { model ->
                            Surface(
                                onClick = { onSelect(model.id) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(model.name.take(40), fontWeight = FontWeight.Medium,
                                            fontSize = 13.sp, maxLines = 1)
                                        Text(model.id, fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1)
                                    }
                                    if (model.isFree) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFF00B894).copy(alpha = 0.15f)
                                        ) {
                                            Text("FREE", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                                color = Color(0xFF00B894),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        dismissButton = {}
    )
}

// =============================================================================
// === Helpers ===
// =============================================================================

private fun checkAccessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}

private fun startBubbleService(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
        Toast.makeText(context, "Grant overlay permission first", Toast.LENGTH_LONG).show()
        return
    }
    context.startService(Intent(context, FloatingBubbleService::class.java).apply {
        action = FloatingBubbleService.ACTION_START
    })
}

private fun stopBubbleService(context: Context) {
    context.startService(Intent(context, FloatingBubbleService::class.java).apply {
        action = FloatingBubbleService.ACTION_STOP
    })
}
