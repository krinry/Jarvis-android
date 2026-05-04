package dev.krinry.jarvis.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.security.SecureKeyStore
import dev.krinry.jarvis.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }

    val isAccessibilityEnabled = remember(refreshKey) { checkAccessibilityEnabled(context) }
    val hasOverlayPermission = remember(refreshKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
    }
    var hasAudioPermission by remember(refreshKey) {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val audioPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> hasAudioPermission = ok; if (ok) refreshKey++ }
    var hasContactsPermission by remember(refreshKey) {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
    }
    val contactsPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> hasContactsPermission = ok; if (ok) refreshKey++ }

    var agentEnabled by remember { mutableStateOf(SecureKeyStore.isAgentEnabled(context)) }
    val allReady = isAccessibilityEnabled && hasOverlayPermission && hasAudioPermission && hasContactsPermission

    val providers = remember { GroqApiClient.getProviders() }
    var selectedProviderId by remember { mutableStateOf(SecureKeyStore.getApiProvider(context)) }
    val selectedProvider = remember(selectedProviderId) { GroqApiClient.getProvider(selectedProviderId) ?: providers.first() }
    var apiKey by remember(selectedProviderId) { mutableStateOf(selectedProvider.getApiKey(context) ?: "") }
    var primaryModel by remember { mutableStateOf(SecureKeyStore.getPrimaryModel(context)) }
    var fallbackModel by remember { mutableStateOf(SecureKeyStore.getFallbackModel(context)) }
    var showModelPicker by remember { mutableStateOf(false) }
    var modelPickerTarget by remember { mutableStateOf("primary") }
    var providerExpanded by remember { mutableStateOf(false) }
    var sttProvider by remember { mutableStateOf(SecureKeyStore.getSttProvider(context)) }
    var ttsProvider by remember { mutableStateOf(SecureKeyStore.getTtsProvider(context)) }
    var wakeWordEnabled by remember { mutableStateOf(SecureKeyStore.isWakeWordEnabled(context)) }
    var nativeAudioEnabled by remember { mutableStateOf(SecureKeyStore.isNativeAudioEnabled(context)) }
    var darkModeEnabled by remember { mutableStateOf(SecureKeyStore.isDarkMode(context)) }

    LaunchedEffect(Unit) { kotlinx.coroutines.delay(500); refreshKey++ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {

            // HERO
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(JarvisPrimary, Color(0xFF8E7CF3), JarvisSecondary.copy(alpha = 0.6f)))).padding(24.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape), contentAlignment = Alignment.Center) { Text("J", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White) }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) { Text("JARVIS", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = 3.sp); Text("AI Voice Agent", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, letterSpacing = 1.sp) }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (agentEnabled) Icons.Default.PowerSettingsNew else Icons.Default.PowerOff, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(if (agentEnabled) "Agent Active" else "Agent Offline", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Text(if (agentEnabled) "Tap bubble to give command" else "Turn on to start", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp) }
                        Switch(checked = agentEnabled, onCheckedChange = { enabled -> agentEnabled = enabled; SecureKeyStore.setAgentEnabled(context, enabled); if (enabled) startBubbleService(context) else stopBubbleService(context); refreshKey++ }, colors = SwitchDefaults.colors(checkedTrackColor = Color.White.copy(alpha = 0.35f), checkedThumbColor = Color.White, uncheckedTrackColor = Color.White.copy(alpha = 0.1f), uncheckedThumbColor = Color.White.copy(alpha = 0.5f)))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            // PERMISSIONS
            SectionHeader("Setup", Icons.Default.Shield)
            PermissionRow(Icons.Default.Accessibility, "Accessibility Service", "Read & interact with apps", isAccessibilityEnabled) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
            PermissionRow(Icons.Default.Layers, "Display Over Apps", "Floating AI bubble", hasOverlayPermission) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
            PermissionRow(Icons.Default.Mic, "Microphone", "Voice commands", hasAudioPermission) { audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            PermissionRow(Icons.Default.Contacts, "Contacts", "Required to call/SMS names", hasContactsPermission) { contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS) }
            if (agentEnabled) {
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (allReady) JarvisSuccess.copy(alpha = 0.1f) else JarvisError.copy(alpha = 0.1f)).padding(12.dp), contentAlignment = Alignment.Center) { Text(if (allReady) "All set! Tap floating bubble to start." else "Grant all permissions above.", fontSize = 13.sp, color = if (allReady) JarvisSuccess else JarvisError, textAlign = TextAlign.Center) }
            }
            Spacer(Modifier.height(24.dp))

            // AI PROVIDER
            SectionHeader("AI Configuration", Icons.Default.Hub)
            Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Provider", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    ExposedDropdownMenuBox(expanded = providerExpanded, onExpandedChange = { providerExpanded = it }) {
                        OutlinedTextField(value = selectedProvider.displayName, onValueChange = {}, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) }, modifier = Modifier.fillMaxWidth().menuAnchor(), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = JarvisPrimary))
                        ExposedDropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                            providers.forEach { provider -> DropdownMenuItem(text = { Text(provider.displayName) }, onClick = { selectedProviderId = provider.id; SecureKeyStore.setApiProvider(context, provider.id); apiKey = provider.getApiKey(context) ?: ""; primaryModel = ""; fallbackModel = ""; SecureKeyStore.setPrimaryModel(context, ""); SecureKeyStore.setFallbackModel(context, ""); providerExpanded = false }) }
                        }
                    }
                }
            }
            Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("${selectedProvider.displayName} API Key", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = apiKey, onValueChange = { newKey -> apiKey = newKey; selectedProvider.saveApiKey(context, newKey) }, placeholder = { Text("Enter API key...") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = JarvisPrimary))
                }
            }
            Spacer(Modifier.height(16.dp))

            // MODELS
            SectionHeader("Models", Icons.Default.Psychology)
            SettingsRow(Icons.Default.AutoAwesome, "Primary Model", primaryModel.ifEmpty { selectedProvider.defaultModel }, JarvisSecondary) { modelPickerTarget = "primary"; showModelPicker = true }
            SettingsRow(Icons.Default.SwapHoriz, "Fallback Model", fallbackModel.ifEmpty { selectedProvider.defaultFallbackModel }, JarvisAccent) { modelPickerTarget = "fallback"; showModelPicker = true }
            Spacer(Modifier.height(16.dp))

            // STT
            SectionHeader("Voice Recognition (STT)", Icons.Default.Mic)
            StringToggleSection("STT Engine", if (sttProvider == "gemini") "Gemini (Better Hindi)" else "Whisper (Groq)", listOf("whisper" to "Whisper", "gemini" to "Gemini"), sttProvider) { sttProvider = it; SecureKeyStore.setSttProvider(context, it) }
            Spacer(Modifier.height(16.dp))

            // TTS
            SectionHeader("Voice Response (TTS)", Icons.Default.VolumeUp)
            StringToggleSection("TTS Engine", if (ttsProvider == "gemini") "Gemini (Natural Voice)" else "Platform (Android)", listOf("platform" to "Platform", "gemini" to "Gemini"), ttsProvider) { ttsProvider = it; SecureKeyStore.setTtsProvider(context, it) }
            Spacer(Modifier.height(16.dp))

            // WAKE WORD
            SectionHeader("Always-on Wake Word", Icons.Default.RecordVoiceOver)
            SwitchSection("Listen for 'Jarvis'", "Say 'Jarvis' to activate without tapping", wakeWordEnabled) { enabled -> wakeWordEnabled = enabled; SecureKeyStore.setWakeWordEnabled(context, enabled); if (agentEnabled) { stopBubbleService(context); startBubbleService(context) } }
            Spacer(Modifier.height(16.dp))

            // NATIVE AUDIO
            SectionHeader("Native Audio Dialog (Beta)", Icons.Default.Hearing)
            SwitchSection("Gemini 2.5 Flash Native Audio", "Skips STT/TTS for raw voice processing", nativeAudioEnabled) { enabled -> nativeAudioEnabled = enabled; SecureKeyStore.setNativeAudioEnabled(context, enabled) }
            Spacer(Modifier.height(16.dp))

            // DARK MODE
            SectionHeader("Appearance", Icons.Default.DarkMode)
            BoolToggleSection("Theme", if (darkModeEnabled) "Dark" else "Light", darkModeEnabled) { darkModeEnabled = it; SecureKeyStore.setDarkMode(context, it) }
            Spacer(Modifier.height(40.dp))

            Text("Jarvis v2.0  \u2022  by Krinry", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(context = context, target = modelPickerTarget, onSelect = { modelId ->
            if (modelPickerTarget == "primary") { primaryModel = modelId; SecureKeyStore.setPrimaryModel(context, modelId) } else { fallbackModel = modelId; SecureKeyStore.setFallbackModel(context, modelId) }
            showModelPicker = false
        }, onDismiss = { showModelPicker = false })
    }
}

@Composable
private fun StringToggleSection(title: String, subtitle: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface); Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row { options.forEach { (id, label) -> val isSelected = id == selected; Surface(modifier = Modifier.padding(horizontal = 4.dp), shape = RoundedCornerShape(10.dp), color = if (isSelected) JarvisPrimary else MaterialTheme.colorScheme.outlineVariant, onClick = { onSelect(id) }) { Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) } } }
            }
        }
    }
}

@Composable
private fun BoolToggleSection(title: String, subtitle: String, selected: Boolean, onSelect: (Boolean) -> Unit) {
    val options = listOf(false to "Light", true to "Dark")
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface); Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row { options.forEach { (id, label) -> val isSelected = id == selected; Surface(modifier = Modifier.padding(horizontal = 4.dp), shape = RoundedCornerShape(10.dp), color = if (isSelected) JarvisPrimary else MaterialTheme.colorScheme.outlineVariant, onClick = { onSelect(id) }) { Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) } } }
            }
        }
    }
}

@Composable
private fun SwitchSection(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface); Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedTrackColor = JarvisPrimary.copy(alpha = 0.5f), checkedThumbColor = JarvisPrimary))
            }
        }
    }
}
