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
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.ai.ModelInfo
import dev.krinry.jarvis.audio.SoundEffectsManager
import dev.krinry.jarvis.data.ConversationHistoryStore
import dev.krinry.jarvis.receiver.JarvisDeviceAdminReceiver
import dev.krinry.jarvis.security.SecureKeyStore
import dev.krinry.jarvis.service.AutoAgentService
import dev.krinry.jarvis.service.FloatingBubbleService
import dev.krinry.jarvis.service.JarvisNotificationListenerService
import dev.krinry.jarvis.system.ShizukuBridge
import dev.krinry.jarvis.ui.theme.*
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshKey by remember { mutableIntStateOf(0) }

    // Auto-refresh permission status whenever user returns to the app
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
    ) { isGranted -> hasAudioPermission = isGranted; if (isGranted) refreshKey++ }

    val isShizukuAvailable = remember(refreshKey) { ShizukuBridge.isShizukuAvailable() }
    val hasShizukuPermission = remember(refreshKey) { ShizukuBridge.hasShizukuPermission() }
    val isDeviceAdminActive = remember(refreshKey) { JarvisDeviceAdminReceiver.isDeviceAdminActive(context) }
    val isNotificationAccessGranted = remember(refreshKey) { JarvisNotificationListenerService.isNotificationAccessGranted(context) }

    val hasTelecomPermissions = remember(refreshKey) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }

    val telecomPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshKey++ }

    var hasCameraPermission by remember(refreshKey) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> hasCameraPermission = isGranted; if (isGranted) refreshKey++ }

    DisposableEffect(Unit) {
        val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                refreshKey++
            }
        }
        val binderListener = Shizuku.OnBinderReceivedListener {
            refreshKey++
        }
        ShizukuBridge.addPermissionResultListener(permissionListener)
        try { Shizuku.addBinderReceivedListenerSticky(binderListener) } catch (_: Exception) {}
        onDispose {
            ShizukuBridge.removePermissionResultListener(permissionListener)
            try { Shizuku.removeBinderReceivedListener(binderListener) } catch (_: Exception) {}
        }
    }

    var agentEnabled by remember { mutableStateOf(SecureKeyStore.isAgentEnabled(context)) }
    val allReady = isAccessibilityEnabled && hasOverlayPermission && hasAudioPermission

    // Engine Mode: Gemini Live API vs Groq
    var engineMode by remember { mutableStateOf(SecureKeyStore.getEngineMode(context)) }

    // Gemini Live configuration
    var geminiApiKey by remember { mutableStateOf(SecureKeyStore.getGeminiApiKey(context) ?: "") }
    var geminiModel by remember { mutableStateOf(SecureKeyStore.getGeminiLiveModel(context)) }
    var geminiVoice by remember { mutableStateOf(SecureKeyStore.getGeminiVoice(context)) }
    var geminiModelExpanded by remember { mutableStateOf(false) }
    var geminiVoiceExpanded by remember { mutableStateOf(false) }

    val geminiModels = listOf(
        "gemini-3.8-live" to "Gemini 3.8 Live (Low Latency)",
        "gemini-3.8-live-extended-thinking" to "Gemini 3.8 Live Extended Thinking (High Reasoning)"
    )
    val geminiVoices = listOf("Puck", "Aoede", "Charon", "Fenrir", "Kore")

    // Groq configuration state (kept intact)
    val providers = remember { GroqApiClient.getProviders() }
    var selectedProviderId by remember { mutableStateOf(SecureKeyStore.getApiProvider(context)) }
    val selectedProvider = remember(selectedProviderId) { GroqApiClient.getProvider(selectedProviderId) ?: providers.first() }
    var groqApiKey by remember(selectedProviderId) {
        mutableStateOf(selectedProvider.getApiKey(context) ?: "")
    }
    var primaryModel by remember { mutableStateOf(SecureKeyStore.getPrimaryModel(context)) }
    var fallbackModel by remember { mutableStateOf(SecureKeyStore.getFallbackModel(context)) }
    var showModelPicker by remember { mutableStateOf(false) }
    var modelPickerTarget by remember { mutableStateOf("primary") }
    var providerExpanded by remember { mutableStateOf(false) }

    var wakeWordEnabled by remember { mutableStateOf(SecureKeyStore.isWakeWordEnabled(context)) }
    var soundEffectsEnabled by remember { mutableStateOf(SecureKeyStore.isSoundEffectsEnabled(context)) }

    LaunchedEffect(Unit) { kotlinx.coroutines.delay(500); refreshKey++ }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = {}, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // ===== STARK SYSTEM PROTOCOLS HERO =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(DarkSurfaceVariant, DarkCard, DarkSurface)
                        )
                    )
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(20.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(StarkCyan.copy(alpha = 0.12f))
                                .border(1.dp, StarkCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Security, null, tint = StarkCyan, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "SYSTEM PROTOCOLS",
                                color = DarkOnSurface,
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp,
                                letterSpacing = 2.sp
                            )
                            Text(
                                if (engineMode == SecureKeyStore.ENGINE_GEMINI_LIVE) "Gemini 3.8 Live Multimodal Core"
                                else "Groq LLaMA Edge Engine",
                                color = StarkCyan,
                                fontSize = 12.sp,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(DarkBackground.copy(alpha = 0.6f))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (agentEnabled) Icons.Default.PowerSettingsNew else Icons.Default.PowerOff,
                            null,
                            tint = if (agentEnabled) StarkGreen else StarkGold,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (agentEnabled) "OVERLAY ARC REACTOR ACTIVE" else "OVERLAY ARC REACTOR OFFLINE",
                                color = DarkOnSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                if (agentEnabled) "Tap floating reactor or say 'Hey Jarvis'" else "Toggle on to launch floating HUD",
                                color = DarkOnSurfaceVariant,
                                fontSize = 11.sp
                            )
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
                                checkedTrackColor = StarkCyan,
                                checkedThumbColor = Color.White,
                                uncheckedTrackColor = DarkSurfaceVariant
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ===== IRON MAN SYSTEM AUTHORITY =====
            SectionHeader("Iron Man System Authority", Icons.Default.Shield)

            // 1. Shizuku Shell Bridge (Root / ADB power)
            PermissionRow(
                icon = Icons.Default.Terminal,
                title = "Shizuku Shell Authority",
                subtitle = when {
                    hasShizukuPermission -> "ADB Shell access granted (Full Phone Control)"
                    isShizukuAvailable -> "Shizuku service running • Tap to authorize"
                    else -> "Tap to open/start Shizuku for ADB authority"
                },
                isGranted = hasShizukuPermission
            ) {
                if (isShizukuAvailable && !hasShizukuPermission) {
                    ShizukuBridge.requestPermission()
                } else {
                    val shizukuIntent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (shizukuIntent != null) {
                        context.startActivity(shizukuIntent)
                    } else {
                        Toast.makeText(context, "Install & start Shizuku for full ADB phone authority", Toast.LENGTH_LONG).show()
                    }
                }
            }

            // 2. Device Administrator
            PermissionRow(
                icon = Icons.Default.AdminPanelSettings,
                title = "Device Administrator",
                subtitle = if (isDeviceAdminActive) "Active (Instant lock & security power)" else "Tap to activate screen locking & admin policies",
                isGranted = isDeviceAdminActive
            ) {
                if (!isDeviceAdminActive) {
                    context.startActivity(JarvisDeviceAdminReceiver.createEnableAdminIntent(context))
                } else {
                    Toast.makeText(context, "Device Administrator is already active", Toast.LENGTH_SHORT).show()
                }
            }

            // 3. Accessibility Service
            PermissionRow(
                icon = Icons.Default.Accessibility,
                title = "Accessibility Service",
                subtitle = "Read active screen UI tree & gestures",
                isGranted = isAccessibilityEnabled
            ) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }

            // 4. Display Over Apps
            PermissionRow(
                icon = Icons.Default.Layers,
                title = "Display Over Apps",
                subtitle = "Floating Arc-Reactor HUD",
                isGranted = hasOverlayPermission
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
            }

            // 5. Microphone
            PermissionRow(
                icon = Icons.Default.Mic,
                title = "Microphone",
                subtitle = "Real-time 16kHz voice streaming",
                isGranted = hasAudioPermission
            ) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            // 6. Notification Intelligence (OTP detection, Read & Reply)
            PermissionRow(
                icon = Icons.Default.NotificationsActive,
                title = "Notification Intelligence",
                subtitle = if (isNotificationAccessGranted) "Active (Inline replies, OTP interception & reading)" else "Tap to enable notification listener for messages & OTPs",
                isGranted = isNotificationAccessGranted
            ) {
                context.startActivity(JarvisNotificationListenerService.createNotificationSettingsIntent())
            }

            // 7. Telecom Authority (Phone Calls, Contacts, SMS)
            PermissionRow(
                icon = Icons.Default.PhoneInTalk,
                title = "Telecom Authority",
                subtitle = if (hasTelecomPermissions) "Active (Phone calls, contact directory, SMS)" else "Tap to authorize phone calling, contacts & SMS",
                isGranted = hasTelecomPermissions
            ) {
                telecomPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.SEND_SMS
                    )
                )
            }

            // 8. Camera Optic Vision (Look at real world)
            PermissionRow(
                icon = Icons.Default.CameraAlt,
                title = "Camera Optic Vision",
                subtitle = if (hasCameraPermission) "Active (Real-world object & document perception)" else "Tap to authorize camera for 'Jarvis look at this'",
                isGranted = hasCameraPermission
            ) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }

            if (agentEnabled) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (allReady) JarvisSuccess.copy(alpha = 0.1f) else JarvisError.copy(alpha = 0.1f))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (allReady) "✅ All set! Tap floating bubble to speak." else "⚠️ Grant all permissions above.",
                        fontSize = 13.sp, color = if (allReady) JarvisSuccess else JarvisError, textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ===== ENGINE SELECTOR (GEMINI LIVE vs GROQ) =====
            SectionHeader("AI Engine Selection", Icons.Default.ElectricBolt)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Gemini Live Tab
                val isGemini = engineMode == SecureKeyStore.ENGINE_GEMINI_LIVE
                Surface(
                    onClick = {
                        engineMode = SecureKeyStore.ENGINE_GEMINI_LIVE
                        SecureKeyStore.setEngineMode(context, SecureKeyStore.ENGINE_GEMINI_LIVE)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isGemini) JarvisPrimary.copy(alpha = 0.2f) else DarkCard,
                    border = if (isGemini) androidx.compose.foundation.BorderStroke(1.5.dp, JarvisPrimary) else null
                ) {
                    Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Bolt, null, tint = if (isGemini) JarvisPrimary else DarkOnSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text("Gemini Live", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (isGemini) Color.White else DarkOnSurface)
                        Text("Real-time Audio+Tools", fontSize = 11.sp, color = DarkOnSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }

                // Groq Tab
                val isGroq = engineMode == SecureKeyStore.ENGINE_GROQ
                Surface(
                    onClick = {
                        engineMode = SecureKeyStore.ENGINE_GROQ
                        SecureKeyStore.setEngineMode(context, SecureKeyStore.ENGINE_GROQ)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isGroq) JarvisSecondary.copy(alpha = 0.2f) else DarkCard,
                    border = if (isGroq) androidx.compose.foundation.BorderStroke(1.5.dp, JarvisSecondary) else null
                ) {
                    Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Forum, null, tint = if (isGroq) JarvisSecondary else DarkOnSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text("Groq Engine", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (isGroq) Color.White else DarkOnSurface)
                        Text("Whisper + Chat API", fontSize = 11.sp, color = DarkOnSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // =================================================================
            // GEMINI LIVE API CONFIGURATION
            // =================================================================
            if (engineMode == SecureKeyStore.ENGINE_GEMINI_LIVE) {
                SectionHeader("Gemini Live API Settings", Icons.Default.SettingsVoice)

                // Gemini API Key Input
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp), color = DarkCard
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Google Gemini API Key", fontSize = 12.sp, color = DarkOnSurfaceVariant, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = geminiApiKey,
                            onValueChange = { newKey ->
                                geminiApiKey = newKey
                                SecureKeyStore.saveGeminiApiKey(context, newKey)
                            },
                            placeholder = { Text("AIzaSy...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JarvisPrimary,
                                unfocusedBorderColor = DarkSurfaceVariant
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "✨ Gemini Live API enables instant bidirectional voice streaming, native 24kHz Hindi speech (TTS), real-time STT, and direct UI action tools.",
                            fontSize = 11.sp, color = JarvisAccent.copy(alpha = 0.9f)
                        )
                    }
                }

                // Gemini Live Model Selector
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp), color = DarkCard
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Live Model", fontSize = 12.sp, color = DarkOnSurfaceVariant, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        ExposedDropdownMenuBox(
                            expanded = geminiModelExpanded,
                            onExpandedChange = { geminiModelExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = geminiModels.find { it.first == geminiModel }?.second ?: geminiModel,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = geminiModelExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = JarvisPrimary,
                                    unfocusedBorderColor = DarkSurfaceVariant
                                )
                            )
                            ExposedDropdownMenu(
                                expanded = geminiModelExpanded,
                                onDismissRequest = { geminiModelExpanded = false }
                            ) {
                                geminiModels.forEach { (modelId, modelLabel) ->
                                    DropdownMenuItem(
                                        text = { Text(modelLabel) },
                                        onClick = {
                                            geminiModel = modelId
                                            SecureKeyStore.setGeminiLiveModel(context, modelId)
                                            geminiModelExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Gemini Live Voice Selector
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp), color = DarkCard
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Assistant Voice", fontSize = 12.sp, color = DarkOnSurfaceVariant, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        ExposedDropdownMenuBox(
                            expanded = geminiVoiceExpanded,
                            onExpandedChange = { geminiVoiceExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = geminiVoice,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = geminiVoiceExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = JarvisPrimary,
                                    unfocusedBorderColor = DarkSurfaceVariant
                                )
                            )
                            ExposedDropdownMenu(
                                expanded = geminiVoiceExpanded,
                                onDismissRequest = { geminiVoiceExpanded = false }
                            ) {
                                geminiVoices.forEach { voice ->
                                    DropdownMenuItem(
                                        text = { Text(voice) },
                                        onClick = {
                                            geminiVoice = voice
                                            SecureKeyStore.setGeminiVoice(context, voice)
                                            geminiVoiceExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // GROQ CONFIGURATION (KEPT FOR COMPATIBILITY)
            // =================================================================
            if (engineMode == SecureKeyStore.ENGINE_GROQ) {
                SectionHeader("Groq Configuration", Icons.Default.Hub)

                // Provider dropdown
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp), color = DarkCard
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Provider", fontSize = 12.sp, color = DarkOnSurfaceVariant, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        ExposedDropdownMenuBox(expanded = providerExpanded, onExpandedChange = { providerExpanded = it }) {
                            OutlinedTextField(
                                value = selectedProvider.displayName,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = JarvisPrimary,
                                    unfocusedBorderColor = DarkSurfaceVariant
                                )
                            )
                            ExposedDropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                                providers.forEach { provider ->
                                    DropdownMenuItem(
                                        text = { Text(provider.displayName) },
                                        onClick = {
                                            selectedProviderId = provider.id
                                            SecureKeyStore.setApiProvider(context, provider.id)
                                            groqApiKey = provider.getApiKey(context) ?: ""
                                            primaryModel = ""
                                            fallbackModel = ""
                                            SecureKeyStore.setPrimaryModel(context, "")
                                            SecureKeyStore.setFallbackModel(context, "")
                                            providerExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // API Key field
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp), color = DarkCard
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${selectedProvider.displayName} API Key", fontSize = 12.sp, color = DarkOnSurfaceVariant, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = groqApiKey,
                            onValueChange = { newKey ->
                                groqApiKey = newKey
                                selectedProvider.saveApiKey(context, newKey)
                            },
                            placeholder = { Text("Enter API key...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JarvisPrimary,
                                unfocusedBorderColor = DarkSurfaceVariant
                            )
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Models section
                SectionHeader("Models", Icons.Default.Psychology)
                SettingsRow(Icons.Default.AutoAwesome, "Primary Model",
                    primaryModel.ifEmpty { selectedProvider.defaultModel }, JarvisSecondary
                ) { modelPickerTarget = "primary"; showModelPicker = true }

                SettingsRow(Icons.Default.SwapHoriz, "Fallback Model",
                    fallbackModel.ifEmpty { selectedProvider.defaultFallbackModel }, JarvisAccent
                ) { modelPickerTarget = "fallback"; showModelPicker = true }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("Iron Man Butler Controls", Icons.Default.RecordVoiceOver)

            SwitchRow(
                icon = Icons.Default.Mic,
                title = "Always-On 'Hey Jarvis' Wake Word",
                subtitle = "Summon Jarvis hands-free by speaking 'Hey Jarvis' anytime",
                checked = wakeWordEnabled,
                iconColor = JarvisAccent
            ) { enabled ->
                wakeWordEnabled = enabled
                SecureKeyStore.setWakeWordEnabled(context, enabled)
                Toast.makeText(context, if (enabled) "Wake word listener activated" else "Wake word deactivated", Toast.LENGTH_SHORT).show()
            }

            SwitchRow(
                icon = Icons.Default.GraphicEq,
                title = "Marvel Sci-Fi Sound Effects",
                subtitle = "HUD bootup hum, repulsor action chirp & confirmation tones",
                checked = soundEffectsEnabled,
                iconColor = JarvisSecondary
            ) { enabled ->
                soundEffectsEnabled = enabled
                SecureKeyStore.setSoundEffectsEnabled(context, enabled)
                if (enabled) {
                    SoundEffectsManager.playWakeSound()
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("Offline Memory & Context", Icons.Default.History)

            val historyStore = remember { ConversationHistoryStore.getInstance(context) }
            var messageCount by remember(refreshKey) { mutableIntStateOf(historyStore.getMessageCount()) }

            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(16.dp), color = DarkCard
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                                .background(JarvisPrimary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Storage, null, tint = JarvisPrimary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Persistent Conversation Memory", fontWeight = FontWeight.Medium, fontSize = 15.sp, color = DarkOnSurface)
                            Text(
                                if (messageCount == 0) "No conversations stored yet" else "$messageCount entries stored offline",
                                fontSize = 12.sp, color = DarkOnSurfaceVariant
                            )
                        }
                        if (messageCount > 0) {
                            IconButton(onClick = {
                                historyStore.clearAllHistory()
                                messageCount = 0
                                Toast.makeText(context, "Memory cleared", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.DeleteOutline, "Clear History", tint = DarkOnSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Jarvis automatically saves all user conversations, timestamps, and task summaries offline so context is never lost across sessions.",
                        fontSize = 11.sp, color = DarkOnSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
            Text("Jarvis v1.2 • with Gemini Live API", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 12.sp, color = DarkOnSurfaceVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(24.dp))
        }
    }

    // ===== MODEL PICKER DIALOG =====
    if (showModelPicker) {
        ModelPickerDialog(
            context = context, target = modelPickerTarget,
            onSelect = { modelId ->
                if (modelPickerTarget == "primary") {
                    primaryModel = modelId; SecureKeyStore.setPrimaryModel(context, modelId)
                } else {
                    fallbackModel = modelId; SecureKeyStore.setFallbackModel(context, modelId)
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
private fun SectionHeader(text: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp, top = 8.dp)) {
        Icon(icon, null, tint = StarkCyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp, color = StarkCyan)
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, iconColor: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, DarkCardBorder)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(iconColor.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = DarkOnSurface)
                Text(subtitle, fontSize = 12.sp, color = DarkOnSurfaceVariant, maxLines = 1)
            }
            Icon(Icons.Default.ChevronRight, null, tint = DarkOnSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, subtitle: String, isGranted: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, if (isGranted) StarkGreen.copy(alpha = 0.35f) else DarkCardBorder)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (isGranted) StarkGreen.copy(alpha = 0.15f) else StarkGold.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = if (isGranted) StarkGreen else StarkGold, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = DarkOnSurface)
                Text(subtitle, fontSize = 12.sp, color = DarkOnSurfaceVariant)
            }
            if (isGranted) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(StarkGreen.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = StarkGreen, modifier = Modifier.size(16.dp))
                }
            } else {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(StarkCyan).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text("Grant", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    iconColor: Color = StarkCyan,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, DarkCardBorder)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = DarkOnSurface)
                Text(subtitle, fontSize = 12.sp, color = DarkOnSurfaceVariant)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = StarkCyan,
                    uncheckedTrackColor = DarkSurfaceVariant
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerDialog(context: Context, target: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showFreeOnly by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch { isLoading = true; models = GroqApiClient.fetchAvailableModels(context); isLoading = false }
    }

    val filteredModels = models.filter { model ->
        (searchQuery.isEmpty() || model.id.contains(searchQuery, ignoreCase = true) || model.name.contains(searchQuery, ignoreCase = true)) &&
        (!showFreeOnly || model.isFree)
    }

    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (target == "primary") "Primary Model" else "Fallback Model") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 450.dp)) {
                OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("Search models...") },
                    singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showFreeOnly, onCheckedChange = { showFreeOnly = it })
                    Text("Free only", fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${filteredModels.size} models", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                if (isLoading) {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (filteredModels.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        Text("No models found\nCheck API key", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(filteredModels) { model ->
                            Surface(onClick = { onSelect(model.id) }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(model.name.take(40), fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
                                        Text(model.id, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    if (model.isFree) {
                                        Surface(shape = RoundedCornerShape(6.dp), color = JarvisSuccess.copy(alpha = 0.15f)) {
                                            Text("FREE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = JarvisSuccess, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
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

private fun checkAccessibilityEnabled(context: Context): Boolean = dev.krinry.jarvis.system.JarvisServiceLauncher.isAccessibilityEnabled(context)

private fun startBubbleService(context: Context) = dev.krinry.jarvis.system.JarvisServiceLauncher.startBubbleService(context)

private fun stopBubbleService(context: Context) = dev.krinry.jarvis.system.JarvisServiceLauncher.stopBubbleService(context)
