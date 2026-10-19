package io.github.thupapuha.macrotracker.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Fixed colors so each macro looks the same everywhere in the app. */
object MacroColors {
    val Protein = Color(0xFFEF5350)
    val Carbs = Color(0xFF42A5F5)
    val Fat = Color(0xFFFFB300)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F0B8),
    onPrimaryContainer = Color(0xFF002204),
    secondary = Color(0xFF52634F),
    secondaryContainer = Color(0xFFD5E8CF),
    tertiary = Color(0xFF38656A),
    background = Color(0xFFF7FBF2),
    surface = Color(0xFFF7FBF2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD67D),
    onPrimary = Color(0xFF0E3900),
    primaryContainer = Color(0xFF1F5100),
    onPrimaryContainer = Color(0xFFB8F396),
    secondary = Color(0xFFBACBB4),
    secondaryContainer = Color(0xFF3B4B38),
    tertiary = Color(0xFFA0CFD4),
    background = Color(0xFF101510),
    surface = Color(0xFF101510),
)

@Composable
fun MacroTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
