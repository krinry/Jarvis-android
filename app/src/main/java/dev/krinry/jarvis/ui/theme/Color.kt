package dev.krinry.jarvis.ui.theme

import androidx.compose.ui.graphics.Color

// === Stark Industries / Iron Man Brand Colors ===
val StarkCyan = Color(0xFF00F0FF)           // Arc Reactor Core Cyan
val StarkCyanGlow = Color(0xFF38E5FF)       // Plasma Blue Glow
val StarkCyanDim = Color(0x3300F0FF)        // 20% Cyan hairline / glow
val StarkGold = Color(0xFFFFB703)           // Mark Armor Gold Accent
val StarkGoldGlow = Color(0xFFFFD166)       // Overdrive Gold
val StarkRed = Color(0xFFEF233C)            // Admin / Weapon / Alert Red
val StarkGreen = Color(0xFF06D6A0)          // Systems Nominal Green
val StarkBlueDark = Color(0xFF0A111E)       // Deep Tech Blue

// Primary & Secondary Brand Tokens (Replaces generic purple with Stark Cyan & Gold)
val JarvisPrimary = StarkCyan               // Arc Reactor Cyan
val JarvisSecondary = StarkGold             // Mark Gold
val JarvisAccent = StarkCyanGlow            // Plasma Glow
val JarvisSuccess = StarkGreen              // Protocols Nominal
val JarvisWarning = StarkGold               // Amber Overdrive
val JarvisError = StarkRed                  // Lockdown Red
val JarvisOrange = Color(0xFFFB8500)        // Thruster Orange

// Stark Obsidian HUD Dark Theme (Cinematic Sci-Fi, Zero AI Slop)
val DarkBackground = Color(0xFF040811)      // Deep Cosmic Obsidian Void
val DarkSurface = Color(0xFF08101E)         // Stark Glass Surface
val DarkSurfaceVariant = Color(0xFF0F1B2F)  // Secondary Chamber
val DarkCard = Color(0xFF0A1324)            // HUD Card Obsidian
val DarkCardBorder = Color(0x3300F0FF)      // Holographic Cyan Hairline
val DarkOnSurface = Color(0xFFF0F6FC)       // Crisp Tech White
val DarkOnSurfaceVariant = Color(0xFF8B9CB5)// Titanium Slate Gray

// Legacy / fallback compatibility
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)