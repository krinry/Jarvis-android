package dev.krinry.jarvis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.krinry.jarvis.data.chat.ChatDatabase
import dev.krinry.jarvis.security.SecureKeyStore
import dev.krinry.jarvis.ui.chat.ChatHistoryDrawerContent
import dev.krinry.jarvis.ui.chat.ChatScreen
import dev.krinry.jarvis.ui.settings.SettingsScreen
import dev.krinry.jarvis.ui.theme.JarvisTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var isDarkMode by remember { mutableStateOf(SecureKeyStore.isDarkMode(this)) }
            
            JarvisTheme(darkTheme = isDarkMode) {
                val navController = rememberNavController()
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val dao = remember { ChatDatabase.getInstance(this).chatDao() }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ChatHistoryDrawerContent(
                            messagesFlow = dao.getAllMessages(),
                            onNewChat = { scope.launch { drawerState.close() } },
                            onDeleteAll = {
                                kotlinx.coroutines.MainScope().launch { dao.clearAll() }
                            },
                            modifier = Modifier
                                .fillMaxHeight()
                                .widthIn(max = 300.dp)
                        )
                    },
                    gesturesEnabled = drawerState.isOpen
                ) {
                    NavHost(navController = navController, startDestination = "chat") {
                        composable("chat") {
                            ChatScreen(
                                onOpenHistory = { scope.launch { drawerState.open() } },
                                onOpenSettings = { navController.navigate("settings") },
                                onThemeToggle = { 
                                    isDarkMode = !isDarkMode
                                    SecureKeyStore.setDarkMode(this@MainActivity, isDarkMode)
                                },
                                isDarkMode = isDarkMode
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}