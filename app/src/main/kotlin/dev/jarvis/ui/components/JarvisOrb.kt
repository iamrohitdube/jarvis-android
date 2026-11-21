package dev.jarvis.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jarvis.ui.theme.Brand
import kotlin.math.cos
import kotlin.math.sin

enum class OrbState { IDLE, THINKING, LISTENING, SPEAKING, LOCKED }

/**
 * The JARVIS orb: a swirling plasma sphere in arc-reactor blue, framed by HUD rings (tick marks, segmented arcs)
 * that turn slowly at rest, speed up while thinking, swell with the voice while listening and pulse while
 * speaking. The plasma is a GPU shader on Android 13+; older phones get a simpler drawn version.
 * Small sizes (avatars) skip the finer ring detail.
 */
@Composable
fun JarvisOrb(size: Dp, modifier: Modifier = Modifier, state: OrbState = OrbState.IDLE, level: Float = 0f) {
    val fast = state == OrbState.THINKING
    val voice by animateFloatAsState(
        when (state) {
            OrbState.LISTENING -> 0.15f + 0.85f * level.coerceIn(0f, 1f)
            OrbState.SPEAKING -> 0.55f
            OrbState.THINKING -> 0.35f
            else -> 0.1f
        },
        tween(110), label = "voice",
    )
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (fast) 2600 else 16000, easing = LinearEasing)), label = "spin")
    val counter by t.animateFloat(360f, 0f, infiniteRepeatable(tween(if (fast) 1800 else 11000, easing = LinearEasing)), label = "counter")
    val pulse by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (state == OrbState.SPEAKING) 380 else 2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val dim = if (state == OrbState.LOCKED) 0.5f else 1f
    val detailed = size >= 80.dp
    val energy = (voice + if (state == OrbState.SPEAKING) 0.35f * pulse else 0.08f * pulse).coerceIn(0f, 1f)

    Box(modifier.size(size)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PlasmaShader(energy, dim, speed = if (fast) 2.2f else 1f, Modifier.fillMaxSize())
        } else {
            Canvas(Modifier.fillMaxSize()) { drawPlasmaFallback(energy, dim, spin) }
        }
        Canvas(Modifier.fillMaxSize()) { drawHud(spin, counter, energy, dim, detailed) }
    }
}

/** Small orb used as JARVIS's avatar next to replies. */
@Composable
fun OrbAvatar(thinking: Boolean, modifier: Modifier = Modifier) =
    JarvisOrb(30.dp, modifier, if (thinking) OrbState.THINKING else OrbState.IDLE)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun PlasmaShader(energy: Float, dim: Float, speed: Float, modifier: Modifier) {
    val shader = remember { RuntimeShader(PLASMA_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val time by produceState(0f) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { value = (it - start) / 1000f }
    }
    Canvas(modifier) {
        shader.setFloatUniform("res", size.width, size.height)
        shader.setFloatUniform("t", time * speed)
        shader.setFloatUniform("amp", energy)
        shader.setFloatUniform("glow", dim)
        drawRect(brush)
    }
}

/** HUD rings: ticks, segmented arcs and a thin inner ring, all in arc-reactor blue. */
private fun DrawScope.drawHud(spin: Float, counter: Float, energy: Float, dim: Float, detailed: Boolean) {
    val r = size.minDimension / 2f
    val c = center
    val swell = 1f + 0.05f * energy
    val cyan = Brand.Cyan

    // Segmented arcs, counter-rotating
    val arcR = r * 0.80f * swell
    val arcTopLeft = Offset(c.x - arcR, c.y - arcR)
    val arcSize = Size(arcR * 2, arcR * 2)
    rotate(counter, c) {
        listOf(-80f to 64f, 10f to 22f, 70f to 96f, 200f to 40f).forEach { (start, sweep) ->
            drawArc(
                cyan.copy(alpha = (0.55f + 0.4f * energy) * dim), start, sweep, useCenter = false,
                topLeft = arcTopLeft, size = arcSize, style = Stroke(r * if (detailed) 0.022f else 0.07f, cap = StrokeCap.Round),
            )
        }
    }
    if (!detailed) return

    // Outer tick ring
    val tickOuter = r * 0.97f
    val tickInner = r * 0.92f
    rotate(spin, c) {
        for (i in 0 until 72) {
            val a = Math.toRadians(i * 5.0)
            val long = i % 6 == 0
            val inner = if (long) tickInner - r * 0.03f else tickInner
            drawLine(
                cyan.copy(alpha = (if (long) 0.55f else 0.22f) * dim),
                Offset(c.x + cos(a).toFloat() * inner, c.y + sin(a).toFloat() * inner),
                Offset(c.x + cos(a).toFloat() * tickOuter, c.y + sin(a).toFloat() * tickOuter),
                strokeWidth = r * if (long) 0.010f else 0.006f,
            )
        }
    }
    // Thin dashed ring
    val dashR = r * 0.87f
    rotate(-spin * 0.6f, c) {
        for (i in 0 until 36) {
            drawArc(
                Brand.Blue.copy(alpha = 0.35f * dim), i * 10f, 5f, useCenter = false,
                topLeft = Offset(c.x - dashR, c.y - dashR), size = Size(dashR * 2, dashR * 2), style = Stroke(r * 0.008f),
            )
        }
    }
    // Inner hairline
    drawCircle(cyan.copy(alpha = 0.18f * dim), radius = r * 0.70f * swell, center = c, style = Stroke(r * 0.006f))
}

/** Pre-Android-13 plasma: layered glows with a slowly turning swirl. */
private fun DrawScope.drawPlasmaFallback(energy: Float, dim: Float, spin: Float) {
    val r = size.minDimension / 2f
    val c = center
    drawCircle(
        Brush.radialGradient(listOf(Brand.Cyan.copy(alpha = 0.30f * dim), Brand.Blue.copy(alpha = 0.10f * dim), Color.Transparent), c, r),
        radius = r, center = c,
    )
    val core = r * (0.52f + 0.06f * energy)
    drawCircle(
        Brush.radialGradient(
            listOf(Brand.Ice.copy(alpha = 0.95f * dim), Brand.Cyan.copy(alpha = 0.8f * dim), Brand.Deep.copy(alpha = 0.7f * dim), Color.Transparent),
            c, core,
        ),
        radius = core, center = c,
    )
    rotate(spin * 2f, c) {
        for (i in 0 until 3) {
            val rr = core * (0.55f + i * 0.15f)
            drawArc(
                Brand.Ice.copy(alpha = 0.35f * dim), i * 120f, 80f, useCenter = false,
                topLeft = Offset(c.x - rr, c.y - rr), size = Size(rr * 2, rr * 2), style = Stroke(r * 0.03f, cap = StrokeCap.Round),
            )
        }
    }
}

/** Domain-warped noise sphere ("smoke in a glass ball") with a bright rim and wisps escaping the edge. */
private const val PLASMA_AGSL = """
uniform float2 res;
uniform float t;
uniform float amp;
uniform float glow;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * noise(p);
        p = p * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}

half4 main(float2 fc) {
    float2 uv = (fc - res * 0.5) / (min(res.x, res.y) * 0.5);
    float r = length(uv);
    float R = 0.56 + 0.07 * amp;
    float2 q = uv * 2.3;
    float2 w = float2(fbm(q + float2(0.0, t * 0.18)), fbm(q + float2(5.2, -t * 0.14)));
    float n = fbm(q + (2.2 + amp) * w + float2(t * 0.06, -t * 0.08));
    float body = smoothstep(R, R - 0.30, r);
    float rim = smoothstep(R + 0.03, R - 0.01, r) * smoothstep(R - 0.16, R, r);
    float smoke = pow(n, 1.5) * body * (1.3 + 1.2 * amp);
    float wisps = smoothstep(0.58, 0.92, n) * smoothstep(R + 0.22 + 0.15 * amp, R - 0.05, r) * (0.5 + amp);
    float halo = exp(-max(r - R, 0.0) * 7.0) * (0.25 + 0.35 * amp);
    float core = exp(-r * r * 12.0) * (0.35 + 0.5 * amp);
    float3 deep = float3(0.02, 0.20, 0.62);
    float3 cyan = float3(0.33, 0.86, 1.0);
    float3 ice = float3(0.86, 0.98, 1.0);
    float3 col = deep * smoke * 1.3 + cyan * (smoke * smoke * 1.5 + rim * 0.85 + wisps * 0.7 + halo) + ice * core;
    col = clamp(col * glow, 0.0, 1.0);
    float a = max(max(col.r, col.g), col.b);
    return half4(half3(col), half(a));
}
"""
