package dev.jarvis.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.data.OnboardingStep
import dev.jarvis.data.StepStatus
import dev.jarvis.ui.settings.ApiKeySection
import dev.jarvis.ui.settings.ConnectorsSection
import dev.jarvis.ui.settings.Hint
import dev.jarvis.ui.settings.SettingsViewModel

/**
 * Resumable first-launch wizard. Each step has a status (todo/done/skipped) persisted in settings.
 * Phase 1: step 1 (Anthropic key) and step 4 (connectors). Later phases add voice, security, etc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onFinished: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    val steps = s.onboarding

    Scaffold(topBar = { TopAppBar(title = { Text("Set up JARVIS") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Hint(
                "Everything stays on this phone. Your questions, and data from connectors you use, are sent to " +
                    "Anthropic's API to generate answers. No analytics or telemetry."
            )

            StepHeader(1, OnboardingStep.ANTHROPIC_KEY, steps.getValue(OnboardingStep.ANTHROPIC_KEY))
            ApiKeySection(vm, onKeyOk = { vm.setStep(OnboardingStep.ANTHROPIC_KEY, StepStatus.DONE) })

            StepHeader(4, OnboardingStep.CONNECTORS, steps.getValue(OnboardingStep.CONNECTORS))
            Hint("Sign in, then tap Test. Optional: skip and connect later in Settings.")
            ConnectorsSection(vm, onConnectorOk = { vm.setStep(OnboardingStep.CONNECTORS, StepStatus.DONE) })
            if (steps.getValue(OnboardingStep.CONNECTORS) == StepStatus.TODO) {
                TextButton(onClick = { vm.setStep(OnboardingStep.CONNECTORS, StepStatus.SKIPPED) }) { Text("Skip connectors for now") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onFinished, enabled = s.onboardingComplete) { Text("Start") }
                if (!s.onboardingComplete) {
                    Text(
                        "Finish or skip each step to continue.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepHeader(number: Int, step: OnboardingStep, status: StepStatus) {
    val label = when (status) {
        StepStatus.TODO -> "to do"
        StepStatus.DONE -> "done"
        StepStatus.SKIPPED -> "skipped"
    }
    Text("Step $number · ${step.title} · $label", style = MaterialTheme.typography.titleSmall)
}
