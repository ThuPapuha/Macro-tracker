package io.github.thupapuha.macrotracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Fixed colors so each macro looks the same everywhere in the app. */
object MacroColors {
    val Protein = Color(0xFFF87171)
    val Carbs = Color(0xFF60A5FA)
    val Fat = Color(0xFFFBBF24)
}

/** Brand accents used for avatars, story rings and highlights. */
object Brand {
    val Emerald = Color(0xFF34D399)
    val Sky = Color(0xFF60A5FA)
    val Pink = Color(0xFFF472B6)
    val Amber = Color(0xFFFBBF24)
    val Violet = Color(0xFFA78BFA)

    /** Ring around members who reached their goal of the day, like a story. */
    val storyRing = Brush.sweepGradient(listOf(Emerald, Sky, Violet, Pink, Amber, Emerald))
    val hero = Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF3B82F6)))
    val gym = Brush.linearGradient(listOf(Color(0xFFF472B6), Color(0xFF8B5CF6)))
    val weight = Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFEF4444)))

    /** Gold for personal records. */
    val Record = Color(0xFFF59E0B)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF059669),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF1E3A8A),
    tertiary = Color(0xFFDB2777),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFCE7F3),
    onTertiaryContainer = Color(0xFF831843),
    error = Color(0xFFDC2626),
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF111418),
    surface = Color(0xFFF4F6F8),
    onSurface = Color(0xFF111418),
    surfaceVariant = Color(0xFFE2E7EC),
    onSurfaceVariant = Color(0xFF5B6470),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDF0F3),
    surfaceContainerHighest = Color(0xFFE6EAEE),
    outline = Color(0xFF8A939E),
    outlineVariant = Color(0xFFD5DBE1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF002A1D),
    primaryContainer = Color(0xFF0E3B2E),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFF60A5FA),
    onSecondary = Color(0xFF00213F),
    secondaryContainer = Color(0xFF16304F),
    onSecondaryContainer = Color(0xFFBFDBFE),
    tertiary = Color(0xFFF472B6),
    onTertiary = Color(0xFF3F0021),
    tertiaryContainer = Color(0xFF4A1736),
    onTertiaryContainer = Color(0xFFFBCFE8),
    error = Color(0xFFF87171),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFE7EAEE),
    surface = Color(0xFF0E1013),
    onSurface = Color(0xFFE7EAEE),
    surfaceVariant = Color(0xFF2A2F37),
    onSurfaceVariant = Color(0xFFA3ABB6),
    surfaceContainerLowest = Color(0xFF0A0C0E),
    surfaceContainerLow = Color(0xFF171A1F),
    surfaceContainer = Color(0xFF15181C),
    surfaceContainerHigh = Color(0xFF22262D),
    surfaceContainerHighest = Color(0xFF2A2F37),
    outline = Color(0xFF6B7480),
    outlineVariant = Color(0xFF343A43),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AppTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun MacroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
