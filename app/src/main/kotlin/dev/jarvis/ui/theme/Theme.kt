package dev.jarvis.ui.theme

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand palette: electric blue → cyan → violet, on deep navy. */
object Brand {
    val Blue = Color(0xFF4F6BFF)
    val Cyan = Color(0xFF22D3EE)
    val Violet = Color(0xFFA78BFA)
    val Navy = Color(0xFF0A0E17)
    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)

    val gradient = listOf(Blue, Cyan, Violet)
    fun horizontal() = Brush.horizontalGradient(gradient)
    fun linear() = Brush.linearGradient(gradient)
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8EA6FF),
    onPrimary = Color(0xFF0B1A4D),
    primaryContainer = Color(0xFF26387E),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFF67E8F9),
    onSecondary = Color(0xFF00363C),
    secondaryContainer = Color(0xFF0E4A53),
    onSecondaryContainer = Color(0xFFC4F7FF),
    tertiary = Color(0xFFC4B1FF),
    onTertiary = Color(0xFF2E1A66),
    tertiaryContainer = Color(0xFF45307C),
    onTertiaryContainer = Color(0xFFEADDFF),
    background = Brand.Navy,
    onBackground = Color(0xFFE6EAF3),
    surface = Brand.Navy,
    onSurface = Color(0xFFE6EAF3),
    surfaceVariant = Color(0xFF1C2333),
    onSurfaceVariant = Color(0xFF9CA5B8),
    surfaceContainerLowest = Color(0xFF06090F),
    surfaceContainerLow = Color(0xFF0F141F),
    surfaceContainer = Color(0xFF131926),
    surfaceContainerHigh = Color(0xFF19202F),
    surfaceContainerHighest = Color(0xFF202838),
    outline = Color(0xFF3B4458),
    outlineVariant = Color(0xFF262E3F),
    error = Color(0xFFFF8E86),
    onError = Color(0xFF5C0D08),
    errorContainer = Color(0xFF4E1916),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE6EAF3),
    inverseOnSurface = Color(0xFF151A24),
    scrim = Color(0xFF000000),
)

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

/** Brand colours in both modes (no wallpaper-based dynamic colour, so JARVIS always looks like JARVIS). */
@Composable
fun JarvisTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = JarvisShapes,
        typography = JarvisTypography,
        content = content,
    )
}

/** A gradient text style for the wordmark. */
fun TextStyle.withBrandGradient(): TextStyle = copy(brush = Brand.linear())
