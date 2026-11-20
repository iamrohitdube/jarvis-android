package dev.jarvis.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.data.OnboardingStep
import dev.jarvis.data.StepStatus
import dev.jarvis.ui.components.BrandGlow
import dev.jarvis.ui.components.Hint
import dev.jarvis.ui.components.JarvisOrb
import dev.jarvis.ui.security.PinSection
import dev.jarvis.ui.security.SecurityViewModel
import dev.jarvis.ui.settings.ApiKeySection
import dev.jarvis.ui.settings.ConnectorsSection
import dev.jarvis.ui.settings.SettingsViewModel
import dev.jarvis.ui.theme.Brand
import dev.jarvis.ui.theme.withBrandGradient
import dev.jarvis.ui.icons.AppIcons

/**
 * Resumable first-launch wizard. Each step has a status (todo/done/skipped) persisted in settings.
 * The first unfinished step is expanded; finished steps collapse but can be reopened.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
    securityVm: SecurityViewModel = hiltViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    val steps = s.onboarding
    val firstTodo = OnboardingStep.entries.firstOrNull { steps.getValue(it) == StepStatus.TODO }
    var expanded by remember(firstTodo) { mutableStateOf(firstTodo) }
    val doneCount = steps.values.count { it != StepStatus.TODO }
    val progress by animateFloatAsState(doneCount / OnboardingStep.entries.size.toFloat(), label = "progress")

    Scaffold(
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    Button(onClick = onFinished, enabled = s.onboardingComplete, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(if (s.onboardingComplete) "Get started" else "Finish or skip each step")
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            BrandGlow(Modifier.size(480.dp).align(Alignment.TopCenter).offset(y = (-180).dp))
            Column(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    JarvisOrb(112.dp)
                    Spacer(Modifier.height(20.dp))
                    Text("Welcome to JARVIS", style = MaterialTheme.typography.headlineMedium.withBrandGradient())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Everything stays on this phone. Your questions, and data from connectors you use, go to Anthropic's API to generate answers. No analytics.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { progress }, modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("$doneCount of ${OnboardingStep.entries.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                StepCard(1, OnboardingStep.ANTHROPIC_KEY, "Connect Claude", steps.getValue(OnboardingStep.ANTHROPIC_KEY), expanded == OnboardingStep.ANTHROPIC_KEY,
                    onToggle = { expanded = if (expanded == OnboardingStep.ANTHROPIC_KEY) null else OnboardingStep.ANTHROPIC_KEY }) {
                    ApiKeySection(vm, onKeyOk = { vm.setStep(OnboardingStep.ANTHROPIC_KEY, StepStatus.DONE) })
                }
                StepCard(2, OnboardingStep.SECURITY, "Protect sensitive answers", steps.getValue(OnboardingStep.SECURITY), expanded == OnboardingStep.SECURITY,
                    onToggle = { expanded = if (expanded == OnboardingStep.SECURITY) null else OnboardingStep.SECURITY }) {
                    Hint("Set a JARVIS PIN for things like your net worth. Fingerprint works too, and is used if you skip this.")
                    PinSection(securityVm)
                    if (steps.getValue(OnboardingStep.SECURITY) == StepStatus.TODO) {
                        TextButton(onClick = { securityVm.skipOnboardingStep() }) { Text("Skip, use fingerprint only") }
                    }
                }
                StepCard(3, OnboardingStep.CONNECTORS, "Connect your accounts", steps.getValue(OnboardingStep.CONNECTORS), expanded == OnboardingStep.CONNECTORS,
                    onToggle = { expanded = if (expanded == OnboardingStep.CONNECTORS) null else OnboardingStep.CONNECTORS }) {
                    Hint("Sign in, then tap Test. Optional: you can connect later in Settings.")
                    ConnectorsSection(vm, onConnectorOk = { vm.setStep(OnboardingStep.CONNECTORS, StepStatus.DONE) })
                    if (steps.getValue(OnboardingStep.CONNECTORS) == StepStatus.TODO) {
                        TextButton(onClick = { vm.setStep(OnboardingStep.CONNECTORS, StepStatus.SKIPPED) }) { Text("Skip for now") }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    step: OnboardingStep,
    title: String,
    status: StepStatus,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val badge by animateColorAsState(
        when (status) {
            StepStatus.DONE -> Brand.Success
            StepStatus.SKIPPED -> MaterialTheme.colorScheme.outline
            StepStatus.TODO -> MaterialTheme.colorScheme.primary
        }, label = "badge",
    )
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(badge.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    when (status) {
                        StepStatus.DONE -> Icon(AppIcons.Check, null, tint = badge, modifier = Modifier.size(18.dp))
                        StepStatus.SKIPPED -> Icon(AppIcons.SkipNext, null, tint = badge, modifier = Modifier.size(18.dp))
                        StepStatus.TODO -> Text("$number", style = MaterialTheme.typography.labelLarge, color = badge)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        step.title + " · " + when (status) { StepStatus.TODO -> "to do"; StepStatus.DONE -> "done"; StepStatus.SKIPPED -> "skipped" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    content()
                }
            }
        }
    }
}
