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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.style.TextAlign
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
import dev.jarvis.service.WakeBus
import dev.jarvis.ui.components.ChipTone
import dev.jarvis.voice.ModelDownloader
import dev.jarvis.voice.VoiceModels
import dev.jarvis.voice.VoicePinEngine
import dev.jarvis.ui.components.Hint
import dev.jarvis.ui.components.IconTile
import dev.jarvis.ui.components.SaveButton
import dev.jarvis.ui.components.SettingsCard
import dev.jarvis.ui.components.StatusChip
import dev.jarvis.ui.components.StatusMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import dev.jarvis.ui.icons.AppIcons

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val pin: PinAuthenticator,
    private val auth: AuthCoordinator,
    private val bio: AndroidBiometricAvailability,
    private val repo: SettingsRepository,
    private val audit: AuditLog,
    private val models: ModelDownloader,
    private val voicePin: VoicePinEngine,
    private val wakeBus: WakeBus,
) : ViewModel() {
    // ---- spoken PIN (models only; voice match removed, D33) ----
    val modelsInstalled = MutableStateFlow(models.installed())
    /** 0..1 while downloading, null otherwise. */
    val downloadProgress = MutableStateFlow<Float?>(null)
    val voiceMessage = MutableStateFlow<String?>(null)

    fun downloadVoiceModels() = viewModelScope.launch {
        voiceMessage.value = null
        runCatching {
            models.download().collect { p -> downloadProgress.value = p.done.toFloat() / p.total }
        }.onFailure { voiceMessage.value = "Download failed: ${it.message}. Check your connection and try again." }
        downloadProgress.value = null
        modelsInstalled.value = models.installed()
        if (modelsInstalled.value) audit.record(AuditEvent(AuditCategory.DATA, "voice_models_download", "ok"))
    }

    fun deleteVoiceModels() = viewModelScope.launch {
        models.delete()
        modelsInstalled.value = false
        voiceMessage.value = "Voice models removed (spoken PIN off)."
    }

    /** "Unlocked phone needs no PIN" (D33). Turning it off is always allowed; turning it on needs fingerprint or screen lock. */
    fun setTrustUnlocked(on: Boolean) = viewModelScope.launch {
        if (on && bio.deviceSecure() && auth.strongAuth() == null) { message.value = "Your fingerprint or screen lock is needed to turn this on."; return@launch }
        repo.setTrustUnlockedPhone(on)
        audit.record(AuditEvent(AuditCategory.AUTH, "trust_unlocked_setting", if (on) "on" else "off"))
    }

    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pinSet: StateFlow<Boolean> = pin.isSet.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val pinLocked = MutableStateFlow(false)
    val bioStatus = MutableStateFlow(BiometricStatus.NONE)
    val deviceSecure = MutableStateFlow(false)
    /** Status line under the PIN card. */
    val message = MutableStateFlow<String?>(null)
    val pinDialogOpen = MutableStateFlow(false)
    /** Error shown inside the PIN dialog (the dialog stays open so the user can fix it). */
    val pinDialogError = MutableStateFlow<String?>(null)
    /** True while the (deliberately slow) PIN hash is computed; the dialog shows a spinner. */
    val savingPin = MutableStateFlow(false)

    /** Held only while the PIN dialog is open; dropped as soon as it closes. */
    private var proof: StrongAuthProof? = null

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        // Voice match is gone (D33): drop any old voiceprint rather than keep biometric data nobody uses.
        if (voicePin.isEnrolled()) voicePin.forgetVoiceprint()
        modelsInstalled.value = models.installed()
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
        pinDialogError.value = null
        pinDialogOpen.value = true
    }

    /** Saves the PIN; on success the dialog closes by itself. */
    fun savePin(newPin: String, confirm: String, onSaved: () -> Unit) = viewModelScope.launch {
        if (savingPin.value) return@launch
        savingPin.value = true
        try {
            val wasSet = pin.hasPin()
            when (pin.setPin(newPin, confirm, proof, bio.deviceSecure())) {
                PinChangeResult.Ok -> {
                    onSaved()
                    closePinDialog()
                    audit.record(AuditEvent(AuditCategory.AUTH, if (wasSet) "pin_changed" else "pin_set", "ok", method = "pin"))
                    repo.setStep(OnboardingStep.SECURITY, StepStatus.DONE)
                    message.value = if (wasSet) "PIN changed." else "PIN set. Only a salted hash is stored."
                    refresh()
                }
                PinChangeResult.Malformed -> pinDialogError.value = "A PIN is 4 to 8 digits."
                PinChangeResult.TooWeak -> pinDialogError.value = "Too easy to guess (like 1111 or 1234). Pick another."
                PinChangeResult.Mismatch -> pinDialogError.value = "The two PINs don't match."
                PinChangeResult.NeedsStrongAuth -> { closePinDialog(); message.value = "Confirm with fingerprint first." }
            }
        } finally {
            savingPin.value = false
        }
    }

    fun closePinDialog() {
        proof = null
        pinDialogError.value = null
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
    }

    fun lockNow() = viewModelScope.launch {
        auth.lock("manual")
        message.value = "Locked. Sensitive data needs PIN or fingerprint again."
    }

    fun skipOnboardingStep() = viewModelScope.launch { repo.setStep(OnboardingStep.SECURITY, StepStatus.SKIPPED) }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SecurityScreen(onBack: () -> Unit, vm: SecurityViewModel = hiltViewModel()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PinSection(vm)
            VoicePinSection(vm)
            val s by vm.settings.collectAsStateWithLifecycle()
            s?.let {
                SessionsSection(vm, it)
                PolicySection(vm, it)
            }
            Spacer(Modifier.height(24.dp))
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

    SettingsCard(
        "PIN and fingerprint", AppIcons.Lock,
        subtitle = "When JARVIS asks before showing private data",
        tint = MaterialTheme.colorScheme.tertiary,
    ) {
        StatusRow(AppIcons.Password, "JARVIS PIN") {
            when {
                !pinSet -> StatusChip("Not set", ChipTone.WARN)
                locked -> StatusChip("Locked", ChipTone.BAD)
                else -> StatusChip("Set", ChipTone.GOOD)
            }
        }
        StatusRow(AppIcons.Fingerprint, "Fingerprint") {
            when (bio) {
                BiometricStatus.AVAILABLE -> StatusChip("Ready", ChipTone.GOOD)
                BiometricStatus.CREDENTIAL_ONLY -> StatusChip("Screen lock", ChipTone.BRAND)
                BiometricStatus.NONE -> StatusChip("Unavailable", ChipTone.BAD)
            }
        }
        val trust = vm.settings.collectAsStateWithLifecycle().value?.trustUnlockedPhone ?: true
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("No PIN while the phone is unlocked", style = MaterialTheme.typography.bodyLarge)
                Hint(
                    if (trust) "Unlocking the phone is enough. PIN or fingerprint only when it's locked or in your pocket."
                    else "Sensitive answers always ask \"PIN or fingerprint?\""
                )
            }
            androidx.compose.material3.Switch(checked = trust, onCheckedChange = vm::setTrustUnlocked)
        }
        if (locked) Hint("3 wrong PINs. Unlock it with your fingerprint the next time JARVIS asks.")
        if (!secure) Hint("This phone has no screen lock, so anyone holding it could change your JARVIS PIN. Set a screen lock in Android settings.")
        Hint("Fingerprint answers are shown on screen only, never spoken.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                vm.beginSetPin()
            }) { Text(if (pinSet) "Change PIN" else "Set PIN") }
            if (pinSet) OutlinedButton(onClick = vm::removePin) { Text("Remove") }
            FilledTonalButton(onClick = vm::lockNow) { Text("Lock now") }
        }
        if (bio != BiometricStatus.AVAILABLE) {
            TextButton(onClick = {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                else Intent(Settings.ACTION_SECURITY_SETTINGS)
                runCatching { context.startActivity(intent) }
            }) { Text("Set up fingerprint") }
        }
        StatusMessage(message)
    }
    if (dialog) NewPinDialog(vm)
}

@Composable
fun VoicePinSection(vm: SecurityViewModel) {
    val installed by vm.modelsInstalled.collectAsStateWithLifecycle()
    val progress by vm.downloadProgress.collectAsStateWithLifecycle()
    val message by vm.voiceMessage.collectAsStateWithLifecycle()
    val pinSet by vm.pinSet.collectAsStateWithLifecycle()
    SettingsCard("Spoken PIN", AppIcons.Mic, subtitle = "Say your PIN when the phone is locked or in your pocket", tint = MaterialTheme.colorScheme.secondary) {
        Hint("The digits are recognised on the phone; your voice and PIN never leave it. Anyone who hears your PIN could say it, so say it only when you're alone.")
        StatusRow(AppIcons.Storage, "Voice models (" + (VoiceModels.totalBytes / 1_000_000) + " MB)") {
            if (installed) StatusChip("Downloaded", ChipTone.GOOD) else StatusChip("Not downloaded")
        }
        progress?.let { p ->
            androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            Hint("Downloading… " + (p * 100).toInt() + "%")
        }
        if (!pinSet) Hint("Set a JARVIS PIN first.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!installed) Button(onClick = vm::downloadVoiceModels, enabled = progress == null) { Text("Download") }
            else TextButton(onClick = vm::deleteVoiceModels) { Text("Remove models") }
        }
        StatusMessage(message)
    }
}

@Composable
private fun StatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, chip: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        chip()
    }
}

@Composable
private fun NewPinDialog(vm: SecurityViewModel) {
    val first = rememberTextFieldState()
    val second = rememberTextFieldState()
    val error by vm.pinDialogError.collectAsStateWithLifecycle()
    val saving by vm.savingPin.collectAsStateWithLifecycle()
    val close = { first.clearText(); second.clearText(); vm.closePinDialog() }
    AlertDialog(
        onDismissRequest = { if (!saving) close() },
        icon = { IconTile(AppIcons.Password, MaterialTheme.colorScheme.tertiary, size = 48) },
        title = { Text("New JARVIS PIN", textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Hint("4 to 8 digits. Ideally not your phone's screen-lock PIN.")
                OutlinedSecureTextField(
                    first, label = { Text("PIN") }, enabled = !saving, shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedSecureTextField(
                    second, label = { Text("Confirm PIN") }, enabled = !saving, shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
                )
                StatusMessage(error, error = true)
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.savePin(first.text.toString(), second.text.toString()) { first.clearText(); second.clearText() } },
                enabled = !saving,
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Saving…")
                } else Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = close, enabled = !saving) { Text("Cancel") } },
    )
}

@Composable
private fun SessionsSection(vm: SecurityViewModel, s: AppSettings) {
    var personal by remember(s.personalSessionMinutes) { mutableStateOf(s.personalSessionMinutes.toString()) }
    var sensitive by remember(s.sensitiveSessionMinutes) { mutableStateOf(s.sensitiveSessionMinutes.toString()) }
    SettingsCard("Sessions", AppIcons.Timer, subtitle = "How long JARVIS waits before asking again") {
        Hint("Sessions also end when the screen turns off or you say \"lock\".")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(sensitive, { sensitive = it }, label = { Text("Sensitive (1-10 min)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f))
            OutlinedTextField(personal, { personal = it }, label = { Text("Personal (1-30 min)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f))
        }
        SaveButton { vm.setSessions(personal, sensitive) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PolicySection(vm: SecurityViewModel, s: AppSettings) {
    SettingsCard("Tool permissions", AppIcons.Tune, subtitle = "When JARVIS must ask before using a tool") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PolicyLevel.entries.forEachIndexed { i, level ->
                SegmentedButton(
                    selected = s.policyLevel == level,
                    onClick = { vm.setLevel(level) },
                    shape = SegmentedButtonDefaults.itemShape(i, PolicyLevel.entries.size),
                ) { Text(level.name.lowercase().replaceFirstChar { it.uppercase() }) }
            }
        }
        Hint(
            when (s.policyLevel) {
                PolicyLevel.STRICT -> "Asks before anything except public info."
                PolicyLevel.SMART -> "Recommended. Asks for sensitive data and any action."
                PolicyLevel.AUTO -> "Tools you set to Allow run without asking."
            }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
        androidx.compose.foundation.layout.Box {
            TextButton(onClick = { open = true }) {
                Text(
                    rule.label(),
                    color = when (rule) {
                        ToolRule.DENY -> MaterialTheme.colorScheme.error
                        ToolRule.ALLOW -> dev.jarvis.ui.theme.Brand.Success
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ToolRule.entries.forEach { r ->
                    DropdownMenuItem(text = { Text(r.label()) }, onClick = { open = false; onChange(r) })
                }
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
