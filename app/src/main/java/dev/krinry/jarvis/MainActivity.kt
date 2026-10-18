package dev.krinry.jarvis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krinry.jarvis.system.ShizukuBridge
import dev.krinry.jarvis.ui.StarkDashboardScreen
import dev.krinry.jarvis.ui.theme.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Shizuku ADB & Root Shell Bridge
        ShizukuBridge.init()

        enableEdgeToEdge()
        setContent {
            JarvisTheme {
                StarkMainScreen()
            }
        }
    }
}

@Composable
fun StarkMainScreen() {
    // 0 = Stark Command Deck (HUD), 1 = System Protocols (Settings)
    var currentTab by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                shape = RoundedCornerShape(20.dp),
                color = DarkCard,
                border = BorderStroke(1.dp, DarkCardBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Command Deck Tab
                    Surface(
                        onClick = { currentTab = 0 },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = if (currentTab == 0) StarkCyan.copy(alpha = 0.15f) else Color.Transparent,
                        border = if (currentTab == 0) BorderStroke(1.dp, StarkCyan.copy(alpha = 0.5f)) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Dashboard,
                                contentDescription = null,
                                tint = if (currentTab == 0) StarkCyan else DarkOnSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "COMMAND DECK",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp,
                                color = if (currentTab == 0) StarkCyan else DarkOnSurfaceVariant
                            )
                        }
                    }

                    // System Protocols Tab
                    Surface(
                        onClick = { currentTab = 1 },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = if (currentTab == 1) StarkGold.copy(alpha = 0.15f) else Color.Transparent,
                        border = if (currentTab == 1) BorderStroke(1.dp, StarkGold.copy(alpha = 0.5f)) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = null,
                                tint = if (currentTab == 1) StarkGold else DarkOnSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "PROTOCOLS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp,
                                color = if (currentTab == 1) StarkGold else DarkOnSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = paddingValues.calculateBottomPadding())
        ) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> width } + fadeOut()
                    }
                },
                label = "deckTabTransition"
            ) { tab ->
                when (tab) {
                    0 -> StarkDashboardScreen(
                        onNavigateToProtocols = { currentTab = 1 }
                    )
                    1 -> AgentSettingsScreen(
                        onBack = { currentTab = 0 }
                    )
                }
            }
        }
    }
}