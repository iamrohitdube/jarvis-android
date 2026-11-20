package dev.jarvis.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jarvis.ui.theme.Brand

enum class OrbState { IDLE, THINKING, LISTENING, SPEAKING, LOCKED }

/**
 * The JARVIS orb: a glowing core inside a rotating gradient ring, with an orbiting arc.
 * Idle = slow breathing; thinking = faster spin and pulse; locked = calm and dimmed.
 */
@Composable
fun JarvisOrb(size: Dp, modifier: Modifier = Modifier, state: OrbState = OrbState.IDLE, level: Float = 0f) {
    val fast = state == OrbState.THINKING || state == OrbState.SPEAKING
    // Listening: the orb swells with the voice level.
    val voiceScale by animateFloatAsState(
        if (state == OrbState.LISTENING) 0.9f + 0.22f * level.coerceIn(0f, 1f) else 1f,
        tween(90), label = "voice",
    )
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (fast) 1400 else 6000, easing = LinearEasing)), label = "spin",
    )
    val counter by t.animateFloat(
        360f, 0f,
        infiniteRepeatable(tween(if (fast) 2200 else 9000, easing = LinearEasing)), label = "counter",
    )
    val pulse by t.animateFloat(
        0.86f, 1f,
        infiniteRepeatable(tween(if (fast) 520 else 2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    val dim = if (state == OrbState.LOCKED) 0.55f else 1f

    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f * voiceScale
        val c = center

        // Soft outer glow
        drawCircle(
            Brush.radialGradient(
                listOf(Brand.Cyan.copy(alpha = 0.32f * dim), Brand.Blue.copy(alpha = 0.12f * dim), Color.Transparent),
                center = c, radius = r * pulse,
            ),
            radius = r * pulse, center = c,
        )

        // Main ring with a rotating sweep gradient
        val ringR = r * 0.68f
        val ringW = r * 0.085f
        rotate(spin, c) {
            drawCircle(
                Brush.sweepGradient(listOf(Brand.Blue, Brand.Cyan, Brand.Violet, Brand.Blue).map { it.copy(alpha = dim) }, c),
                radius = ringR, center = c, style = Stroke(ringW),
            )
        }

        // Orbiting arcs
        val arcR = r * 0.88f
        val arcSize = Size(arcR * 2, arcR * 2)
        val arcTopLeft = Offset(c.x - arcR, c.y - arcR)
        rotate(counter, c) {
            drawArc(
                Color(0xFF8AF4FF).copy(alpha = 0.85f * dim), startAngle = -90f, sweepAngle = 70f, useCenter = false,
                topLeft = arcTopLeft, size = arcSize, style = Stroke(r * 0.05f, cap = StrokeCap.Round),
            )
            drawArc(
                Brand.Violet.copy(alpha = 0.6f * dim), startAngle = 110f, sweepAngle = 40f, useCenter = false,
                topLeft = arcTopLeft, size = arcSize, style = Stroke(r * 0.035f, cap = StrokeCap.Round),
            )
        }

        // Core
        val coreR = r * 0.36f * (0.94f + 0.06f * pulse)
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = dim), Color(0xFF8AF4FF).copy(alpha = dim), Brand.Blue.copy(alpha = dim)),
                center = Offset(c.x - coreR * 0.25f, c.y - coreR * 0.25f), radius = coreR * 1.35f,
            ),
            radius = coreR, center = c,
        )
    }
}

/** Small static-sized orb used as JARVIS's avatar next to replies. */
@Composable
fun OrbAvatar(thinking: Boolean, modifier: Modifier = Modifier) =
    JarvisOrb(28.dp, modifier, if (thinking) OrbState.THINKING else OrbState.IDLE)
