package dev.krinry.jarvis.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.krinry.jarvis.audio.SoundEffectsManager
import dev.krinry.jarvis.receiver.JarvisDeviceAdminReceiver
import dev.krinry.jarvis.security.SecureKeyStore
import dev.krinry.jarvis.service.JarvisNotificationListenerService
import dev.krinry.jarvis.system.JarvisServiceLauncher
import dev.krinry.jarvis.system.ShizukuBridge
import dev.krinry.jarvis.system.SystemTelemetry
import dev.krinry.jarvis.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * StarkDashboardScreen — Authentic Iron Man Holographic Command Deck.
 *
 * Visual Features:
 * - Real-time Mark armor diagnostic HUD telemetry
 * - Interactive central Arc Reactor with rotating electromagnetic coils
 * - Live telemetry matrix (Battery, Thermals, RAM, Neural Link, ADB Shell)
 * - 8-Node Authority Matrix with luminous status beacons
 * - Tactical Action Deck (Voice link, Optic scan, Lockdown, Protocols)
 */
@Composable
fun StarkDashboardScreen(
    onNavigateToProtocols: () -> Unit
) {
    val context = LocalContext.current

    // Live clock and telemetry updates
    var currentTime by remember { mutableStateOf("") }
    var telemetry by remember { mutableStateOf(SystemTelemetry.getDiagnostics(context)) }
    var isBubbleActive by remember { mutableStateOf(JarvisServiceLauncher.isBubbleRunning()) }

    LaunchedEffect(Unit) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        while (true) {
            currentTime = sdf.format(Date())
            telemetry = SystemTelemetry.getDiagnostics(context)
            isBubbleActive = JarvisServiceLauncher.isBubbleRunning()
            delay(1000)
        }
    }

    // Authorities check
    val hasShizuku = ShizukuBridge.hasShizukuPermission()
    val hasAdmin = JarvisDeviceAdminReceiver.isDeviceAdminActive(context)
    val hasAccessibility = JarvisServiceLauncher.isAccessibilityEnabled(context)
    val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
    val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val hasNotifs = JarvisNotificationListenerService.isNotificationAccessGranted(context)
    val hasTelecom = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    val authoritiesGrantedCount = listOf(hasShizuku, hasAdmin, hasAccessibility, hasOverlay, hasAudio, hasNotifs, hasTelecom, hasCamera).count { it }

    val infiniteTransition = rememberInfiniteTransition(label = "hudPulse")
    val gridAlpha by infiniteTransition.animateFloat(
        initialValue = 0.03f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Reverse),
        label = "gridAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .drawBehind {
                // Subtle holographic grid lines
                val step = 40.dp.toPx()
                for (x in 0..(size.width / step).toInt()) {
                    drawLine(
                        color = StarkCyan.copy(alpha = gridAlpha),
                        start = Offset(x * step, 0f),
                        end = Offset(x * step, size.height),
                        strokeWidth = 1f
                    )
                }
                for (y in 0..(size.height / step).toInt()) {
                    drawLine(
                        color = StarkCyan.copy(alpha = gridAlpha),
                        start = Offset(0f, y * step),
                        end = Offset(size.width, y * step),
                        strokeWidth = 1f
                    )
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // =====================================================================
        // === 1. TOP KRINRY STATUS HEADER ===
        // =====================================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isBubbleActive) StarkGreen else StarkGold)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "KRINRY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = StarkCyan,
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "• MADE BY KRINRY",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = StarkGold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "JARVIS BY KRINRY",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                    color = DarkOnSurface,
                    maxLines = 1,
                    softWrap = false
                )
            }

            // Right telemetry chip (guaranteed no wrapping)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DarkCard,
                border = BorderStroke(1.dp, DarkCardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = currentTime,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = StarkCyan,
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(11.dp)
                            .background(DarkCardBorder)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${telemetry.batteryLevel}%",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (telemetry.batteryLevel > 20) StarkGreen else StarkRed,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // =====================================================================
        // === 2. CENTERPIECE: HOLOGRAPHIC ARC REACTOR CORE ===
        // =====================================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(DarkSurfaceVariant, DarkCard, DarkBackground),
                        radius = 600f
                    )
                )
                .border(1.dp, DarkCardBorder, RoundedCornerShape(24.dp))
                .padding(vertical = 28.dp, horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Interactive Arc Reactor View
                Box(
                    modifier = Modifier
                        .size(170.dp)
                        .clickable {
                            SoundEffectsManager.playWakeSound()
                            if (!isBubbleActive) {
                                JarvisServiceLauncher.startBubbleService(context)
                                isBubbleActive = true
                                Toast.makeText(context, "Arc Reactor Engaged, Sir", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Jarvis is actively listening, Sir", Toast.LENGTH_SHORT).show()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            ArcReactorView(ctx).apply {
                                setState(if (isBubbleActive) ArcReactorView.State.LISTENING else ArcReactorView.State.IDLE)
                            }
                        },
                        update = { view ->
                            view.setState(if (isBubbleActive) ArcReactorView.State.LISTENING else ArcReactorView.State.IDLE)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(Modifier.height(18.dp))

                Text(
                    text = if (isBubbleActive) "REACTOR CORE // ONLINE" else "REACTOR CORE // STANDBY",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    color = if (isBubbleActive) StarkCyan else StarkGold
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = if (isBubbleActive) "Awaiting voice command or \"Hey Jarvis\"" else "Tap reactor or switch below to activate",
                    fontSize = 12.sp,
                    color = DarkOnSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(16.dp))

                // Tactical Core Switch Button
                OutlinedButton(
                    onClick = {
                        SoundEffectsManager.playWakeSound()
                        if (isBubbleActive) {
                            JarvisServiceLauncher.stopBubbleService(context)
                            isBubbleActive = false
                        } else {
                            JarvisServiceLauncher.startBubbleService(context)
                            isBubbleActive = true
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (isBubbleActive) StarkCyan else StarkGold),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isBubbleActive) StarkCyan.copy(alpha = 0.12f) else StarkGold.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(
                        imageVector = if (isBubbleActive) Icons.Default.PowerSettingsNew else Icons.Default.Bolt,
                        contentDescription = null,
                        tint = if (isBubbleActive) StarkCyan else StarkGold,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isBubbleActive) "DISENGAGE PROTOCOLS" else "INITIALIZE J.A.R.V.I.S.",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontSize = 12.sp,
                        color = if (isBubbleActive) StarkCyan else StarkGold
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // =====================================================================
        // === 3. LIVE STARK TELEMETRY MATRIX ===
        // =====================================================================
        Text(
            text = "SYSTEM TELEMETRY MATRIX",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = StarkCyan,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TelemetryCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.BatteryChargingFull,
                label = "ARC POWER",
                value = "${telemetry.batteryLevel}%",
                detail = "${telemetry.batteryTemperatureCelsius}°C • ${telemetry.chargingType}",
                accentColor = if (telemetry.batteryLevel > 20) StarkCyan else StarkRed
            )
            TelemetryCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Psychology,
                label = "NEURAL CORE",
                value = "GEMINI LIVE",
                detail = "Voice: ${SecureKeyStore.getGeminiVoice(context)} • 24kHz",
                accentColor = StarkGold
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TelemetryCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Shield,
                label = "SYS AUTHORITY",
                value = if (hasShizuku && hasAdmin) "ARMED" else "$authoritiesGrantedCount/8 READY",
                detail = if (hasShizuku) "Shizuku ADB: Active" else "Shizuku: Offline",
                accentColor = if (hasShizuku) StarkGreen else StarkGold
            )
            TelemetryCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Memory,
                label = "MEMORY & DISK",
                value = "${telemetry.ramUsagePercent}% RAM",
                detail = "Free: ${String.format(Locale.US, "%.1f", telemetry.freeStorageGb)} GB",
                accentColor = StarkCyanGlow
            )
        }

        Spacer(Modifier.height(24.dp))

        // =====================================================================
        // === 4. SYSTEM AUTHORITY MATRIX (8 NODES) ===
        // =====================================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AUTHORITY SUBSYSTEMS ($authoritiesGrantedCount/8)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = StarkCyan
            )

            Text(
                text = "CONFIGURE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = StarkGold,
                modifier = Modifier.clickable { onNavigateToProtocols() }
            )
        }

        Spacer(Modifier.height(10.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkCard,
            border = BorderStroke(1.dp, DarkCardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthorityPill(Modifier.weight(1f), "SHIZUKU", hasShizuku)
                    AuthorityPill(Modifier.weight(1f), "ADMIN", hasAdmin)
                    AuthorityPill(Modifier.weight(1f), "A11Y SVC", hasAccessibility)
                    AuthorityPill(Modifier.weight(1f), "OVERLAY", hasOverlay)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthorityPill(Modifier.weight(1f), "MIC LINK", hasAudio)
                    AuthorityPill(Modifier.weight(1f), "NOTIFS", hasNotifs)
                    AuthorityPill(Modifier.weight(1f), "TELECOM", hasTelecom)
                    AuthorityPill(Modifier.weight(1f), "OPTIC CAM", hasCamera)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // =====================================================================
        // === 5. TACTICAL ACTION DECK ===
        // =====================================================================
        Text(
            text = "TACTICAL QUICK CONTROLS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = StarkCyan,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TacticalButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Lock,
                title = "LOCKDOWN",
                subtitle = "Device Admin",
                tint = StarkRed
            ) {
                if (hasAdmin) {
                    SoundEffectsManager.playToolSound()
                    JarvisDeviceAdminReceiver.lockDevice(context)
                } else {
                    Toast.makeText(context, "Activate Device Admin authority in Protocols, Sir", Toast.LENGTH_SHORT).show()
                    onNavigateToProtocols()
                }
            }

            TacticalButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Tune,
                title = "PROTOCOLS",
                subtitle = "Settings & Keys",
                tint = StarkGold
            ) {
                onNavigateToProtocols()
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "⚡ JARVIS • MADE BY KRINRY",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = StarkCyan.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))
    }
}

// =============================================================================
// === High-Tech HUD Components ===
// =============================================================================

@Composable
private fun TelemetryCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    detail: String,
    accentColor: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, DarkCardBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = accentColor, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = DarkOnSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = value,
                fontFamily = FontFamily.Monospace,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                color = DarkOnSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                fontSize = 11.sp,
                color = DarkOnSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AuthorityPill(
    modifier: Modifier = Modifier,
    label: String,
    isActive: Boolean
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (isActive) StarkGreen.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, if (isActive) StarkGreen.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isActive) StarkGreen else StarkGold)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = if (isActive) DarkOnSurface else DarkOnSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TacticalButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, letterSpacing = 1.sp, color = DarkOnSurface)
                Text(subtitle, fontSize = 10.sp, color = DarkOnSurfaceVariant)
            }
        }
    }
}
