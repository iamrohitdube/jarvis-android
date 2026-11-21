package dev.jarvis.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand palette: arc-reactor blue on near-black (the JARVIS HUD look). */
object Brand {
    val Cyan = Color(0xFF5CE1FF)      // arc-reactor glow
    val Blue = Color(0xFF1E8BFF)      // electric blue
    val Violet = Color(0xFF3D6BFF)    // kept for older call sites: a deeper blue accent
    val Deep = Color(0xFF0A3A8C)      // plasma shadow
    val Ice = Color(0xFFDDF7FF)       // hot core / highlights
    val Navy = Color(0xFF03060C)      // background
    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)

    val gradient = listOf(Ice, Cyan, Blue)
    fun horizontal() = Brush.horizontalGradient(gradient)
    fun linear() = Brush.linearGradient(gradient)
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5CE1FF),
    onPrimary = Color(0xFF00273A),
    primaryContainer = Color(0xFF0C3B5C),
    onPrimaryContainer = Color(0xFFCDF3FF),
    secondary = Color(0xFF7FB8FF),
    onSecondary = Color(0xFF002E5F),
    secondaryContainer = Color(0xFF0E2E57),
    onSecondaryContainer = Color(0xFFD5E6FF),
    tertiary = Color(0xFF9FD8FF),
    onTertiary = Color(0xFF003352),
    tertiaryContainer = Color(0xFF123B5A),
    onTertiaryContainer = Color(0xFFD6EEFF),
    background = Brand.Navy,
    onBackground = Color(0xFFE4F2FA),
    surface = Brand.Navy,
    onSurface = Color(0xFFE4F2FA),
    surfaceVariant = Color(0xFF13202E),
    onSurfaceVariant = Color(0xFF8FA6B8),
    surfaceContainerLowest = Color(0xFF010205),
    surfaceContainerLow = Color(0xFF08111C),
    surfaceContainer = Color(0xFF0C1724),
    surfaceContainerHigh = Color(0xFF111E2D),
    surfaceContainerHighest = Color(0xFF172636),
    outline = Color(0xFF2E4559),
    outlineVariant = Color(0xFF1A2A3A),
    error = Color(0xFFFF8E86),
    onError = Color(0xFF5C0D08),
    errorContainer = Color(0xFF4E1916),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE4F2FA),
    inverseOnSurface = Color(0xFF0C1724),
    scrim = Color(0xFF000000),
)

@Suppress("unused")
private val LightColors = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE5FF),
    onPrimaryContainer = Color(0xFF0B1A4D),
    secondary = Color(0xFF00838F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDF5F9),
    onSecondaryContainer = Color(0xFF00363C),
    tertiary = Color(0xFF6E4BD8),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECE3FF),
    onTertiaryContainer = Color(0xFF24104F),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF141924),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF141924),
    surfaceVariant = Color(0xFFE5E8F0),
    onSurfaceVariant = Color(0xFF586174),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F2F8),
    surfaceContainer = Color(0xFFEBEEF5),
    surfaceContainerHigh = Color(0xFFE5E8F1),
    surfaceContainerHighest = Color(0xFFDFE3ED),
    outline = Color(0xFFC1C7D4),
    outlineVariant = Color(0xFFDDE1EA),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** Generous, friendly corners (production assistant apps use 16-28dp). */
val JarvisShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val base = Typography()
val JarvisTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
)

/**
 * JARVIS is always dark (the HUD look), whatever the system setting; no wallpaper-based dynamic colour.
 * The light scheme is kept unused for a possible future option.
 */
@Composable
fun JarvisTheme(@Suppress("UNUSED_PARAMETER") dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        shapes = JarvisShapes,
        typography = JarvisTypography,
        content = content,
    )
}

/** A gradient text style for the wordmark. */
fun TextStyle.withBrandGradient(): TextStyle = copy(brush = Brand.linear())
