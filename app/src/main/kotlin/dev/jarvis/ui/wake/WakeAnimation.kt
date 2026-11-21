package dev.jarvis.ui.wake

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jarvis.ui.components.JarvisOrb
import dev.jarvis.ui.components.OrbState
import dev.jarvis.ui.theme.Brand
import kotlin.math.PI
import kotlin.math.sin

/** What the wake overlay is showing. */
enum class WakePhase { WAKING, LISTENING, THINKING, SPEAKING, DONE }

/**
 * Full-screen "Hey Jarvis" animation (JARVIS's own design):
 * the screen dims to navy, the orb blooms up from the bottom, aurora waves flow along the bottom edge and
 * swell with the user's voice, a soft light traces the screen edges, and the live transcript floats above.
 */
@Composable
fun WakeAnimation(
    phase: WakePhase,
    level: Float,
    transcript: String,
    reply: String,
    modifier: Modifier = Modifier,
) {
    // Bloom-in on appear, fade-out on DONE.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(phase == WakePhase.DONE) {
        if (phase == WakePhase.DONE) appear.animateTo(0f, tween(360, easing = FastOutSlowInEasing))
        else appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow))
    }
    val voice by animateFloatAsState(
        when (phase) {
            WakePhase.LISTENING -> 0.25f + 0.75f * level.coerceIn(0f, 1f)
            WakePhase.SPEAKING -> 0.55f
            WakePhase.THINKING -> 0.18f
            else -> 0.1f
        },
        tween(120), label = "voice",
    )

    val t = rememberInfiniteTransition(label = "wake")
    val phaseShift by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "shift")
    val edge by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "edge")
    // While speaking, the waves breathe on their own (a soft synthetic envelope).
    val breath by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val amp = if (phase == WakePhase.SPEAKING) voice * breath else voice

    Box(
        modifier.fillMaxSize().alpha(appear.value.coerceIn(0f, 1f))
            .background(Brush.verticalGradient(listOf(Color(0xE6070A12), Color(0xF20A0E17), Color(0xFF0A0E17)))),
    ) {
        // Edge light
        Canvas(Modifier.fillMaxSize()) { drawEdgeGlow(edge, 0.35f + 0.65f * voice) }
        // Aurora waves along the bottom
        Canvas(Modifier.fillMaxWidth().height(260.dp).align(Alignment.BottomCenter)) { drawAurora(phaseShift, amp) }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.weight(1f))
            AnimatedContent(
                targetState = when {
                    reply.isNotBlank() && phase == WakePhase.SPEAKING -> reply
                    transcript.isNotBlank() -> transcript
                    phase == WakePhase.THINKING -> "Thinking…"
                    else -> "Listening…"
                },
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "words",
            ) { words ->
                Text(
                    words, color = Color.White, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall, maxLines = 6,
                )
            }
            Spacer(Modifier.height(40.dp))
            JarvisOrb(
                size = 150.dp,
                modifier = Modifier.scale(0.6f + 0.4f * appear.value),
                state = when (phase) {
                    WakePhase.LISTENING, WakePhase.WAKING -> OrbState.LISTENING
                    WakePhase.THINKING -> OrbState.THINKING
                    WakePhase.SPEAKING -> OrbState.SPEAKING
                    WakePhase.DONE -> OrbState.IDLE
                },
                level = if (phase == WakePhase.LISTENING) level else 0f,
            )
            Spacer(Modifier.height(180.dp))
        }
    }
}

/** Three translucent sine bands in the brand colours, drifting at different speeds. */
private fun DrawScope.drawAurora(shift: Float, amp: Float) {
    val bands = listOf(
        Triple(Brand.Blue, 1.0f, 0f),
        Triple(Brand.Cyan, 1.35f, 1.7f),
        Triple(Brand.Violet, 0.8f, 3.4f),
    )
    bands.forEachIndexed { i, (color, freq, offset) ->
        val path = Path()
        val baseY = size.height * (0.55f + i * 0.08f)
        val height = size.height * 0.32f * amp * (1f - i * 0.18f)
        path.moveTo(0f, size.height)
        var x = 0f
        val step = size.width / 48f
        while (x <= size.width + step) {
            val p = x / size.width
            val y = baseY - height * sin((p * 2 * PI * freq + shift * (1 + i * 0.35f) + offset).toFloat()) *
                (0.6f + 0.4f * sin((p * PI).toFloat()))
            path.lineTo(x, y)
            x += step
        }
        path.lineTo(size.width, size.height)
        path.close()
        drawPath(
            path,
            Brush.verticalGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.12f), Color.Transparent), startY = baseY - height, endY = size.height),
        )
    }
}

/** A soft gradient light running round the screen edge; brighter with the voice. */
private fun DrawScope.drawEdgeGlow(progress: Float, intensity: Float) {
    val stroke = 6.dp.toPx()
    val inset = stroke / 2
    val colors = listOf(Brand.Blue, Brand.Cyan, Brand.Violet, Brand.Blue)
    // Rotate the gradient start round the edge by sliding the gradient endpoints.
    val angle = progress * 2 * PI
    val cx = size.width / 2; val cy = size.height / 2
    val dx = (kotlin.math.cos(angle) * size.width).toFloat(); val dy = (sin(angle) * size.height).toFloat()
    drawRoundRect(
        brush = Brush.linearGradient(colors.map { it.copy(alpha = 0.85f * intensity) }, start = Offset(cx - dx, cy - dy), end = Offset(cx + dx, cy + dy)),
        topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(36.dp.toPx()),
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
    // Inner glow
    drawRoundRect(
        brush = Brush.linearGradient(colors.map { it.copy(alpha = 0.18f * intensity) }, start = Offset(cx + dx, cy + dy), end = Offset(cx - dx, cy - dy)),
        topLeft = Offset(inset + stroke, inset + stroke),
        size = Size(size.width - 3 * stroke, size.height - 3 * stroke),
        cornerRadius = CornerRadius(30.dp.toPx()),
        style = Stroke(stroke * 3),
    )
}
