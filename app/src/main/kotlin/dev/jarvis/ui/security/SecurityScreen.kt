package dev.jarvis.ui.security

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jarvis.agent.ToolRegistry
import dev.jarvis.data.AppSettings
import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditLog
import dev.jarvis.data.OnboardingStep
import dev.jarvis.data.SettingsRepository
import dev.jarvis.data.StepStatus
import dev.jarvis.security.AndroidBiometricAvailability
import dev.jarvis.security.AuthCoordinator
import dev.jarvis.security.BiometricStatus
import dev.jarvis.security.PinAuthenticator
import dev.jarvis.security.PinChangeResult
import dev.jarvis.security.PolicyLevel
import dev.jarvis.security.StrongAuthProof
import dev.jarvis.security.ToolRule
import dev.jarvis.ui.settings.Hint
import dev.jarvis.ui.settings.SectionCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val pin: PinAuthenticator,
    private val auth: AuthCoordinator,
    private val bio: AndroidBiometricAvailability,
    private val repo: SettingsRepository,
    private val audit: AuditLog,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pinSet: StateFlow<Boolean> = pin.isSet.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val pinLocked = MutableStateFlow(false)
    val bioStatus = MutableStateFlow(BiometricStatus.NONE)
    val deviceSecure = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val pinDialogOpen = MutableStateFlow(false)

    /** Held only while the PIN dialog is open; dropped as soon as it closes. */
    private var proof: StrongAuthProof? = null

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        pinLocked.value = pin.isLocked()
        bioStatus.value = bio.status()
        deviceSecure.value = bio.deviceSecure()
    }

    fun beginSetPin() = viewModelScope.launch {
        if (bio.deviceSecure()) {
            proof = auth.strongAuth() ?: run {
                message.value = "Your fingerprint or screen lock is needed to change the PIN."
                return@launch
            }
        }
        message.value = null
        pinDialogOpen.value = true
    }

    fun savePin(newPin: String, confirm: String) = viewModelScope.launch {
        val wasSet = pin.hasPin()
        when (pin.setPin(newPin, confirm, proof, bio.deviceSecure())) {
            PinChangeResult.Ok -> {
                closePinDialog()
                audit.record(AuditEvent(AuditCategory.AUTH, if (wasSet) "pin_changed" else "pin_set", "ok", method = "pin"))
                repo.setStep(OnboardingStep.SECURITY, StepStatus.DONE)
                message.value = "PIN saved. Only a salted hash is stored."
                refresh()
            }
            PinChangeResult.Malformed -> message.value = "A PIN is 4 to 8 digits."
            PinChangeResult.TooWeak -> message.value = "Too easy to guess (like 1111 or 1234). Pick another."
            PinChangeResult.Mismatch -> message.value = "The two PINs don't match."
            PinChangeResult.NeedsStrongAuth -> { closePinDialog(); message.value = "Confirm with fingerprint first." }
        }
    }

    fun closePinDialog() {
        proof = null
        pinDialogOpen.value = false
    }

    fun removePin() = viewModelScope.launch {
        val p = if (bio.deviceSecure()) auth.strongAuth() ?: run {
            message.value = "Your fingerprint or screen lock is needed to remove the PIN."
            return@launch
        } else null
        if (pin.removePin(p, bio.deviceSecure()) == PinChangeResult.Ok) {
            audit.record(AuditEvent(AuditCategory.AUTH, "pin_removed", "ok"))
            message.value = "PIN removed. Sensitive data now needs your fingerprint."
            refresh()
        }
    }

    fun setLevel(level: PolicyLevel) = viewModelScope.launch {
        repo.setPolicyLevel(level)
        audit.record(AuditEvent(AuditCategory.AUTH, "policy_level", "ok", detail = level.name.lowercase()))
    }

    fun setRule(tool: String, rule: ToolRule) = viewModelScope.launch {
        repo.setToolRule(tool, rule)
        audit.record(AuditEvent(AuditCategory.AUTH, "tool_rule", "ok", tool = tool, detail = rule.name.lowercase()))
    }

    fun setSessions(personal: String, sensitive: String) = viewModelScope.launch {
        val p = personal.trim().toIntOrNull(); val s = sensitive.trim().toIntOrNull()
        if (p == null || s == null) { message.value = "Enter minutes as whole numbers."; return@launch }
        repo.setSessionMinutes(p, s)
        message.value = "Session lengths saved."
    }

    fun lockNow() = viewModelScope.launch {
        auth.lock("manual")
        message.value = "Locked. Sensitive data needs PIN or fingerprint again."
    }

    fun skipOnboardingStep() = viewModelScope.launch { repo.setStep(OnboardingStep.SECURITY, StepStatus.SKIPPED) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(onBack: () -> Unit, vm: SecurityViewModel = hiltViewModel()) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Security") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PinSection(vm)
            val s by vm.settings.collectAsStateWithLifecycle()
            s?.let {
                SessionsSection(vm, it)
                PolicySection(vm, it)
            }
        }
    }
}

/** PIN + fingerprint status. Also used by onboarding step 3. */
@Composable
fun PinSection(vm: SecurityViewModel) {
    val pinSet by vm.pinSet.collectAsStateWithLifecycle()
    val locked by vm.pinLocked.collectAsStateWithLifecycle()
    val bio by vm.bioStatus.collectAsStateWithLifecycle()
    val secure by vm.deviceSecure.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val dialog by vm.pinDialogOpen.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) { vm.refresh() }

    SectionCard("PIN and fingerprint") {
        Hint("Sensitive answers (like your net worth) ask \"PIN or fingerprint?\" each time. Fingerprint shows the answer on screen only.")
        Text(
            "JARVIS PIN: " + when {
                !pinSet -> "not set"
                locked -> "LOCKED after 3 wrong tries. Unlock it with your fingerprint."
                else -> "set"
            },
            color = if (locked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Fingerprint: " + when (bio) {
                BiometricStatus.AVAILABLE -> "ready"
                BiometricStatus.CREDENTIAL_ONLY -> "not enrolled; your phone's screen lock will be used"
                BiometricStatus.NONE -> "not available"
            }
        )
        if (!secure) Hint("This phone has no screen lock, so anyone holding it could change your JARVIS PIN. Set a screen lock in Android settings.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                vm.beginSetPin()
            }) { Text(if (pinSet) "Change PIN" else "Set PIN") }
            if (pinSet) OutlinedButton(onClick = vm::removePin) { Text("Remove") }
        }
        if (bio != BiometricStatus.AVAILABLE) {
            OutlinedButton(onClick = {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                else Intent(Settings.ACTION_SECURITY_SETTINGS)
                runCatching { context.startActivity(intent) }
            }) { Text("Set up fingerprint") }
        }
        OutlinedButton(onClick = vm::lockNow) { Text("Lock now") }
        message?.let { Hint(it) }
    }
    if (dialog) NewPinDialog(vm)
}

@Composable
private fun NewPinDialog(vm: SecurityViewModel) {
    val first = rememberTextFieldState()
    val second = rememberTextFieldState()
    val message by vm.message.collectAsStateWithLifecycle()
    val close = { first.clearText(); second.clearText(); vm.closePinDialog() }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("New JARVIS PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("4 to 8 digits. Not the same as your phone's screen-lock PIN, ideally.")
                OutlinedSecureTextField(first, label = { Text("PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                OutlinedSecureTextField(second, label = { Text("Confirm PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.savePin(first.text.toString(), second.text.toString())
                first.clearText(); second.clearText()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun SessionsSection(vm: SecurityViewModel, s: AppSettings) {
    var personal by remember(s.personalSessionMinutes) { mutableStateOf(s.personalSessionMinutes.toString()) }
    var sensitive by remember(s.sensitiveSessionMinutes) { mutableStateOf(s.sensitiveSessionMinutes.toString()) }
    SectionCard("Sessions") {
        Hint("After you confirm, JARVIS won't ask again for this long. Sessions also end when the screen turns off or you say \"lock\".")
        OutlinedTextField(sensitive, { sensitive = it }, label = { Text("Sensitive (finance), minutes, 1-10") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(personal, { personal = it }, label = { Text("Personal, minutes, 1-30") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { vm.setSessions(personal, sensitive) }) { Text("Save") }
    }
}

@Composable
private fun PolicySection(vm: SecurityViewModel, s: AppSettings) {
    SectionCard("Tool permissions") {
        Hint("Strict: ask before anything except public info. Smart (recommended): ask for sensitive data and any action. Auto: tools you set to Allow run without asking.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PolicyLevel.entries.forEach { level ->
                FilterChip(selected = s.policyLevel == level, onClick = { vm.setLevel(level) },
                    label = { Text(level.name.lowercase().replaceFirstChar { it.uppercase() }) })
            }
        }
        ToolRegistry.all.filterNot { it.helper }.forEach { tool ->
            ToolRuleRow(tool.description, tool.tier.name.lowercase(), s.toolRules[tool.name] ?: ToolRule.DEFAULT) { vm.setRule(tool.name, it) }
        }
    }
}

@Composable
private fun ToolRuleRow(label: String, tier: String, rule: ToolRule, onChange: (ToolRule) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(tier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { open = true }) { Text(rule.label()) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ToolRule.entries.forEach { r ->
                DropdownMenuItem(text = { Text(r.label()) }, onClick = { open = false; onChange(r) })
            }
        }
    }
}

private fun ToolRule.label() = when (this) {
    ToolRule.DEFAULT -> "Default"
    ToolRule.ALLOW -> "Allow"
    ToolRule.ASK -> "Ask"
    ToolRule.DENY -> "Deny"
}
