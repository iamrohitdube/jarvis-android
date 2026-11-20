package dev.jarvis.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jarvis.claude.LlmClient
import dev.jarvis.claude.LlmException
import dev.jarvis.connectors.ConnectorStatus
import dev.jarvis.connectors.ConnectorTestResult
import dev.jarvis.connectors.indmoney.IndMoneyConnector
import dev.jarvis.data.AppSettings
import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditLog
import dev.jarvis.data.SnapshotStore
import dev.jarvis.memory.HistoryStore
import dev.jarvis.ui.chat.CurrentConversation
import dev.jarvis.data.OnboardingStep
import dev.jarvis.data.SettingsRepository
import dev.jarvis.data.StepStatus
import dev.jarvis.security.SecretKey
import dev.jarvis.security.SecretStore
import dev.jarvis.ui.SignInEvents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val secrets: SecretStore,
    private val llm: LlmClient,
    private val indMoney: IndMoneyConnector,
    private val signInEvents: SignInEvents,
    private val snapshots: SnapshotStore,
    private val history: HistoryStore,
    private val current: CurrentConversation,
    private val audit: AuditLog,
) : ViewModel() {

    /** Result line for the Data section. */
    val dataMessage = MutableStateFlow<String?>(null)

    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val hasApiKey: StateFlow<Boolean> = secrets.presence().map { SecretKey.ANTHROPIC_API_KEY in it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val indMoneyStatus: StateFlow<ConnectorStatus> = indMoney.status
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectorStatus.NotConnected)

    /** Result line for the key test ("Key works." / error). */
    val keyMessage = MutableStateFlow<String?>(null)
    /** Result line for connector actions; also receives browser sign-in outcomes. */
    val connectorMessage: StateFlow<String?> = signInEvents.last
    val busy = MutableStateFlow(false)

    fun saveAndTestKey(raw: String, onOk: () -> Unit = {}) {
        val key = raw.trim()
        if (key.isEmpty()) { keyMessage.value = "Paste a key first."; return }
        launchBusy {
            keyMessage.value = "Testing…"
            llm.testKey(key).fold(
                onSuccess = {
                    secrets.put(SecretKey.ANTHROPIC_API_KEY, key)
                    keyMessage.value = "Key works and is saved (encrypted)."
                    onOk()
                },
                onFailure = { e ->
                    keyMessage.value = when (e) {
                        is LlmException.Unauthorized -> "Anthropic rejected this key. Not saved."
                        is LlmException.Network -> "Couldn't reach Anthropic. Check your connection. Not saved."
                        else -> "Test failed: ${e.message}. Not saved."
                    }
                },
            )
        }
    }

    fun testSavedKey() = launchBusy {
        val key = secrets.get(SecretKey.ANTHROPIC_API_KEY)
        if (key == null) { keyMessage.value = "No key saved."; return@launchBusy }
        keyMessage.value = "Testing…"
        keyMessage.value = llm.testKey(key).fold({ "Key works." }, { "Test failed: ${it.message}" })
    }

    fun removeKey() = launchBusy {
        secrets.remove(SecretKey.ANTHROPIC_API_KEY)
        keyMessage.value = "Key removed."
    }

    fun startIndMoneySignIn(openUrl: (String) -> Unit) = launchBusy {
        runCatching { indMoney.startSignIn() }
            .onSuccess(openUrl)
            .onFailure { signInEvents.post("Couldn't start INDmoney sign-in: ${it.message}") }
    }

    fun testIndMoney(onOk: () -> Unit = {}) = launchBusy {
        signInEvents.post("Testing INDmoney…")
        when (val r = indMoney.test()) {
            ConnectorTestResult.Ok -> { signInEvents.post("INDmoney works."); onOk() }
            is ConnectorTestResult.Failed -> signInEvents.post(r.message)
        }
    }

    fun setIndMoneyManualToken(token: String) = launchBusy {
        if (token.isBlank()) return@launchBusy
        indMoney.setManualToken(token)
        signInEvents.post("Token saved (encrypted). Tap Test to check it.")
    }

    fun signOutIndMoney() = launchBusy {
        indMoney.signOut()
        signInEvents.post("INDmoney signed out.")
    }

    fun setChatModel(v: String) = viewModelScope.launch { if (v.isNotBlank()) repo.setChatModel(v) }
    fun setQuickModel(v: String) = viewModelScope.launch { if (v.isNotBlank()) repo.setQuickModel(v) }
    fun setRouteSimple(v: Boolean) = viewModelScope.launch { repo.setRouteSimpleToQuick(v) }
    fun setServerFallback(v: Boolean) = viewModelScope.launch { repo.setServerFallback(v) }
    fun setBudget(text: String) = viewModelScope.launch { text.toDoubleOrNull()?.let { repo.setMonthlyBudget(it) } }
    fun setSnapshotMinutes(text: String) = viewModelScope.launch {
        val v = text.trim().toIntOrNull()
        if (v == null || v < 0) { dataMessage.value = "Enter minutes as a whole number (0 = always live)."; return@launch }
        repo.setSnapshotMaxAgeMinutes(v)
        dataMessage.value = if (v == 0) "Portfolio data will always be fetched live." else "Saved portfolio data will be reused for $v min."
    }

    fun clearSnapshots() = viewModelScope.launch {
        snapshots.clear()
        audit.record(AuditEvent(AuditCategory.DATA, "clear_saved_data", "ok"))
        dataMessage.value = "Saved portfolio data cleared."
    }

    fun deleteAllHistory() = viewModelScope.launch {
        history.deleteAll()
        current.open(null)
        audit.record(AuditEvent(AuditCategory.DATA, "delete_all_history", "ok"))
        dataMessage.value = "All chat history deleted."
    }

    fun setStopAtBudget(v: Boolean) = viewModelScope.launch { repo.setStopAtBudget(v) }
    fun setStep(step: OnboardingStep, status: StepStatus) = viewModelScope.launch { repo.setStep(step, status) }

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        try { block() } finally { busy.value = false }
    }
}
