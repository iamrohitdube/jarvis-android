package dev.jarvis.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.jarvis.ui.theme.Brand
import kotlinx.coroutines.delay
import dev.jarvis.ui.icons.AppIcons

/** Rounded icon tile used as the leading element of settings cards and rows. */
@Composable
fun IconTile(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, size: Int = 40) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.55).dp)) }
}

/** A grouped settings card: icon tile + title/subtitle header, then content. */
@Composable
fun SettingsCard(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon, tint)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

/** A tappable navigation row (icon, title, subtitle, chevron). */
@Composable
fun NavRow(title: String, icon: ImageVector, subtitle: String? = null, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(AppIcons.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

enum class ChipTone { NEUTRAL, GOOD, WARN, BAD, BRAND }

/** Small rounded status pill. */
@Composable
fun StatusChip(text: String, tone: ChipTone = ChipTone.NEUTRAL, icon: ImageVector? = null) {
    val (bg, fg) = when (tone) {
        ChipTone.GOOD -> Brand.Success.copy(alpha = 0.16f) to Brand.Success
        ChipTone.WARN -> Brand.Warning.copy(alpha = 0.16f) to Brand.Warning
        ChipTone.BAD -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f) to MaterialTheme.colorScheme.error
        ChipTone.BRAND -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) to MaterialTheme.colorScheme.primary
        ChipTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** Animated inline status line (info or error) that appears/disappears smoothly. */
@Composable
fun StatusMessage(text: String?, error: Boolean = false) {
    AnimatedVisibility(visible = text != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (error) AppIcons.ErrorOutline else AppIcons.Info, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text.orEmpty(), style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

/** Hint text in the muted style. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

/**
 * A save button that confirms with a check mark for 2 seconds after it's tapped, so the user sees
 * the save happened (no dialog to close).
 */
@Composable
fun SaveButton(label: String = "Save", enabled: Boolean = true, onClick: () -> Unit) {
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) { delay(2000); saved = false } }
    Button(
        onClick = { onClick(); saved = true },
        enabled = enabled,
        colors = if (saved) ButtonDefaults.buttonColors(containerColor = Brand.Success, contentColor = Color(0xFF052E1C)) else ButtonDefaults.buttonColors(),
    ) {
        AnimatedContent(saved, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "save") { done ->
            if (done) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Check, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Saved")
            } else Text(label)
        }
    }
}

/** Three bouncing dots shown while JARVIS is thinking. */
@Composable
fun TypingDots(color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(520, delayMillis = i * 160), RepeatMode.Reverse), label = "dot$i",
            )
            Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(color))
        }
    }
}

/** Section label above a group of cards. */
@Composable
fun SectionLabel(text: String) =
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
    )

/** Subtle brand glow behind screen headers. */
@Composable
fun BrandGlow(modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(Brand.Blue.copy(alpha = 0.22f), Brand.Cyan.copy(alpha = 0.06f), Color.Transparent),
            )
        )
    )
}
