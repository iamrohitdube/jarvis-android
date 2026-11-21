package dev.jarvis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jarvis.ui.theme.Brand

/** Frosted-glass surface: a faint top-lit fill and a thin light border (the HUD panel look). */
fun Modifier.glass(shape: Shape, tint: Color = Color.White, strength: Float = 1f): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.085f * strength), tint.copy(alpha = 0.03f * strength))), shape)
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f * strength), Brand.Cyan.copy(alpha = 0.06f * strength))), shape)

/** App background: near-black with a soft arc-reactor glow from the top and a faint one from the bottom. */
@Composable
fun JarvisBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.fillMaxSize().background(Brand.Navy).drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(Brand.Blue.copy(alpha = 0.24f), Brand.Deep.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(size.width / 2f, -size.height * 0.06f), radius = size.maxDimension * 0.75f,
                )
            )
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Brand.Deep.copy(alpha = 0.16f))))
        },
    ) {
        content()
    }
}

/** Round glass icon button (top bars, quick actions). */
@Composable
fun GlassIconButton(icon: ImageVector, description: String?, size: Dp = 44.dp, tint: Color = Color.White, onClick: () -> Unit) {
    Box(Modifier.size(size).glass(CircleShape), contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick, modifier = Modifier.size(size)) {
            Icon(icon, description, tint = tint.copy(alpha = 0.9f), modifier = Modifier.size(size * 0.45f))
        }
    }
}
