package dev.krinry.jarvis.ui.settings

import android.content.Context
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.ai.ModelInfo
import dev.krinry.jarvis.service.FloatingBubbleService
import dev.krinry.jarvis.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SectionHeader(text: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp, top = 4.dp)) {
        Icon(icon, null, tint = JarvisAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp, color = JarvisAccent)
    }
}

@Composable
fun SettingsRow(icon: ImageVector, title: String, subtitle: String, iconColor: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(iconColor.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun PermissionRow(icon: ImageVector, title: String, subtitle: String, isGranted: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (isGranted) JarvisSuccess.copy(alpha = 0.15f) else JarvisPrimary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (isGranted) JarvisSuccess else JarvisPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isGranted) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(JarvisSuccess.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, tint = JarvisSuccess, modifier = Modifier.size(16.dp)) }
            } else {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(JarvisPrimary).padding(horizontal = 14.dp, vertical = 6.dp)) { Text("Grant", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerDialog(context: Context, target: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showFreeOnly by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch { isLoading = true; models = GroqApiClient.fetchAvailableModels(context); isLoading = false }
    }

    val filteredModels = models.filter { m -> (searchQuery.isEmpty() || m.id.contains(searchQuery, true) || m.name.contains(searchQuery, true)) && (!showFreeOnly || m.isFree) }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (target == "primary") "Primary Model" else "Fallback Model") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 450.dp)) {
            OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("Search models...") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = showFreeOnly, onCheckedChange = { showFreeOnly = it }); Text("Free only", fontSize = 14.sp); Spacer(Modifier.weight(1f)); Text("${filteredModels.size} models", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(8.dp))
            if (isLoading) { Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else if (filteredModels.isEmpty()) { Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { Text("No models found\nCheck API key", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            else { LazyColumn(Modifier.fillMaxWidth().weight(1f)) { items(filteredModels) { model -> Surface(onClick = { onSelect(model.id) }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(model.name.take(40), fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1); Text(model.id, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }; if (model.isFree) { Surface(shape = RoundedCornerShape(6.dp), color = JarvisSuccess.copy(alpha = 0.15f)) { Text("FREE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = JarvisSuccess, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) } } } } } } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

fun checkAccessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}

fun startBubbleService(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) { Toast.makeText(context, "Grant overlay permission first", Toast.LENGTH_LONG).show(); return }
    try { val intent = Intent(context, FloatingBubbleService::class.java).apply { action = FloatingBubbleService.ACTION_START }; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent) }
    catch (e: Exception) { Toast.makeText(context, "Could not start Jarvis: ${e.message?.take(50)}", Toast.LENGTH_LONG).show() }
}

fun stopBubbleService(context: Context) {
    try { context.startService(Intent(context, FloatingBubbleService::class.java).apply { action = FloatingBubbleService.ACTION_STOP }) } catch (_: Exception) {}
}
