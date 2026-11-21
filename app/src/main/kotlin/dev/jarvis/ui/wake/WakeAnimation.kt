package dev.jarvis.ui.wake

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jarvis.ui.components.JarvisOrb
import dev.jarvis.ui.components.OrbState
import dev.jarvis.ui.icons.AppIcons
import dev.jarvis.ui.theme.Brand

/** What the wake screen is showing. */
enum class WakePhase { WAKING, LISTENING, THINKING, SPEAKING, DONE }

/**
 * The "Hey Jarvis" screen: pure black (OLED pixels off) with only the glowing orb and words, like the HUD in the
 * films. The orb blooms in, its plasma follows the voice, HUD rings spin up while thinking, and words fade in
 * below. Bottom: the mic (tap to stop talking) and close.
 */
@Composable
fun WakeAnimation(
    phase: WakePhase,
    level: Float,
    transcript: String,
    reply: String,
    onMic: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shown when JARVIS can learn from a false wake (personal check trained). */
    onNotForMe: (() -> Unit)? = null,
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(phase == WakePhase.DONE) {
        if (phase == WakePhase.DONE) appear.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
        else appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow))
    }
    val a = appear.value.coerceIn(0f, 1f)
    // First frame: just black and text, so the screen appears at once; the orb (GPU shader) joins a frame later.
    var orbReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; orbReady = true }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp).alpha(a),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Text(
                "JARVIS", color = Brand.Cyan.copy(alpha = 0.75f), letterSpacing = 6.sp,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(36.dp))
            AnimatedContent(
                targetState = when (phase) {
                    WakePhase.THINKING -> "Thinking…"
                    WakePhase.SPEAKING -> ""
                    WakePhase.DONE -> ""
                    else -> "I'm listening…"
                },
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "status",
            ) { s ->
                Text(s, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Light)
            }
            Spacer(Modifier.weight(0.6f))
            if (!orbReady) Spacer(Modifier.size(290.dp)) else JarvisOrb(
                size = 290.dp,
                modifier = Modifier.scale(0.55f + 0.45f * appear.value),
                state = when (phase) {
                    WakePhase.LISTENING, WakePhase.WAKING -> OrbState.LISTENING
                    WakePhase.THINKING -> OrbState.THINKING
                    WakePhase.SPEAKING -> OrbState.SPEAKING
                    WakePhase.DONE -> OrbState.IDLE
                },
                level = if (phase == WakePhase.LISTENING) level else 0f,
            )
            Spacer(Modifier.weight(0.4f))
            // Words: what you said, then JARVIS's reply; older lines fade out at the top.
            AnimatedContent(
                targetState = if (reply.isNotBlank() && phase == WakePhase.SPEAKING) reply else transcript,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                label = "words",
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            ) { words ->
                Box(Modifier.fillMaxWidth().fadeTop(), contentAlignment = Alignment.BottomCenter) {
                    Text(
                        words, color = Color.White, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Normal, maxLines = 5,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onNotForMe != null) {
                    androidx.compose.material3.TextButton(onClick = onNotForMe) {
                        Text("Not for me", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
                    }
                } else Spacer(Modifier.size(48.dp))
                MicButton(active = phase == WakePhase.LISTENING || phase == WakePhase.SPEAKING, onClick = onMic)
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(AppIcons.Close, "Close", tint = Color.White.copy(alpha = 0.75f))
                }
            }
        }
    }
}

/** Glowing round mic button. */
@Composable
private fun MicButton(active: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(96.dp).background(
                Brush.radialGradient(listOf(Brand.Cyan.copy(alpha = if (active) 0.35f else 0.15f), Color.Transparent)),
            )
        )
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(64.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(Brand.Cyan, Brand.Blue))),
        ) {
            Icon(AppIcons.Mic, "Stop talking", tint = Color(0xFF00121F))
        }
    }
}

/** Fades the top of the words so long text dissolves upwards. */
private fun Modifier.fadeTop(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black, 1f to Color.Black),
            blendMode = BlendMode.DstIn,
        )
    }
