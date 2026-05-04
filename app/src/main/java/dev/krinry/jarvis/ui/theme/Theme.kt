package dev.krinry.jarvis.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.krinry.jarvis.security.SecureKeyStore

private val JarvisDarkScheme = darkColorScheme(
    primary = JarvisPrimary,
    onPrimary = JarvisOnPrimary,
    primaryContainer = JarvisPrimaryContainer,
    onPrimaryContainer = JarvisOnPrimaryContainer,
    secondary = JarvisSecondary,
    onSecondary = JarvisOnSecondary,
    secondaryContainer = JarvisSecondaryContainer,
    onSecondaryContainer = JarvisOnSecondaryContainer,
    tertiary = JarvisTertiary,
    onTertiary = JarvisOnTertiary,
    tertiaryContainer = JarvisTertiaryContainer,
    onTertiaryContainer = JarvisOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = JarvisError,
    onError = JarvisOnError,
    errorContainer = JarvisErrorContainer,
    onErrorContainer = JarvisOnErrorContainer,
    outline = JarvisOutline,
    outlineVariant = JarvisOutlineVariant,
    surfaceTint = JarvisSurfaceTint,
    inverseSurface = JarvisSurfaceContainerLowest,
    inverseOnSurface = JarvisSurfaceContainerLow,
    inversePrimary = Color(0xFFbec6e0)
)

private val JarvisLightScheme = lightColorScheme(
    primary = JarvisPrimary,
    onPrimary = JarvisOnPrimary,
    primaryContainer = JarvisPrimaryContainer,
    onPrimaryContainer = JarvisOnPrimaryContainer,
    secondary = JarvisSecondary,
    onSecondary = JarvisOnSecondary,
    secondaryContainer = JarvisSecondaryContainer,
    onSecondaryContainer = JarvisOnSecondaryContainer,
    tertiary = JarvisTertiary,
    onTertiary = JarvisOnTertiary,
    tertiaryContainer = JarvisTertiaryContainer,
    onTertiaryContainer = JarvisOnTertiaryContainer,
    background = JarvisBackground,
    onBackground = JarvisOnBackground,
    surface = JarvisSurfaceContainerLowest,
    onSurface = JarvisOnSurface,
    surfaceVariant = JarvisSurfaceVariant,
    onSurfaceVariant = JarvisOnSurfaceVariant,
    error = JarvisError,
    onError = JarvisOnError,
    errorContainer = JarvisErrorContainer,
    onErrorContainer = JarvisOnErrorContainer,
    outline = JarvisOutline,
    outlineVariant = JarvisOutlineVariant,
    surfaceTint = JarvisSurfaceTint,
    inverseSurface = Color(0xFF2d3133),
    inverseOnSurface = Color(0xFFeff1f3),
    inversePrimary = Color(0xFFbec6e0),
    surfaceContainerLowest = JarvisSurfaceContainerLowest,
    surfaceContainerLow = JarvisSurfaceContainerLow,
    surfaceContainer = JarvisSurfaceContainer,
    surfaceContainerHigh = JarvisSurfaceContainerHigh,
    surfaceContainerHighest = JarvisSurfaceContainerHighest
)

@Composable
fun JarvisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val useDarkTheme = if (SecureKeyStore.isDarkMode(context)) true else darkTheme

    val colorScheme = if (useDarkTheme) JarvisDarkScheme else JarvisLightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}