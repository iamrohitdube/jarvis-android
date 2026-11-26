package dev.jarvis.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.jarvis.agent.Models
import dev.jarvis.security.PolicyLevel
import dev.jarvis.voice.MicSource
import dev.jarvis.voice.WakeEngineChoice
import dev.jarvis.voice.WakeSensitivity
import dev.jarvis.security.SecuritySettings
import dev.jarvis.security.SessionPolicy
import dev.jarvis.security.ToolPolicy
import dev.jarvis.security.ToolRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class StepStatus { TODO, DONE, SKIPPED }

/** Onboarding wizard steps. Phase 1 ships steps 1 and 4 (CLAUDE.md section 15). */
enum class OnboardingStep(val title: String) {
    ANTHROPIC_KEY("Anthropic API key"),
    VOICE("Voice"),
    SECURITY("Security"),
    CONNECTORS("Connectors"),
}

/** Non-secret user settings. Secrets live in [dev.jarvis.security.SecretStore]. */
data class AppSettings(
    val chatModel: String = Models.DEFAULT_CHAT,
    val quickModel: String = Models.DEFAULT_QUICK,
    /** Route short, tool-free questions to the quick (cheaper) model. */
    val routeSimpleToQuick: Boolean = true,
    /** Opt in to server-side refusal fallback on models that support it. */
    val serverFallback: Boolean = true,
    /** Monthly spend budget in USD; 0 = no budget. Warning at 80%. */
    val monthlyBudgetUsd: Double = DEFAULT_BUDGET_USD,
    /** Refuse new requests once the local monthly estimate reaches the budget. */
    val stopAtBudget: Boolean = true,
    /** Reuse cached connector snapshots (e.g. net worth) younger than this; 0 = always fetch live. */
    val snapshotMaxAgeMinutes: Int = DEFAULT_SNAPSHOT_MINUTES,
    /** Tool policy (CLAUDE.md section 6). Enforced by AuthCoordinator, never by prompts. */
    val policyLevel: PolicyLevel = PolicyLevel.SMART,
    val toolRules: Map<String, ToolRule> = emptyMap(),
    /** Session lengths: Tier 1 personal ~5 min, Tier 2 sensitive ~2 min. */
    val personalSessionMinutes: Int = 5,
    val sensitiveSessionMinutes: Int = 2,
    /** Speak replies to voice questions (off = quiet mode, screen only). */
    val speakReplies: Boolean = true,
    /** Use on-device speech recognition when available (audio stays on the phone). */
    val preferOnDeviceStt: Boolean = true,
    /** Text-to-speech rate, 1.0 = normal. */
    val speechRate: Float = 1.0f,
    /** No "PIN or fingerprint?" for personal/sensitive reads while the phone is unlocked (D33). */
    val trustUnlockedPhone: Boolean = true,
    /** Messaging "stop everything" switch (CLAUDE.md section 7). */
    val sendingEnabled: Boolean = true,
    /** Trusted contacts, comma separated: only they can be approved by saying "send it". */
    val trustedContacts: String = "",
    val maxMessagesPerHour: Int = 20,
    /** Keep other apps' notifications (D35). Off by default; needs notification access too. */
    val readAppNotifications: Boolean = false,
    /** Apps whose notifications are never kept, comma separated (app names). */
    val ignoredNotificationApps: String = "",
    /** Google granted `gmail.send` (D36): shown on the Gmail card. */
    val emailSendAllowed: Boolean = false,
    /** Blocks every personal-data read (D29 step 6). */
    val privateMode: Boolean = false,
    /** Features whose data may go to Claude, redacted (ToolGroup names). Default: none. */
    val claudeConsent: String = "",
    /** Features whose first redacted send the user has already previewed and approved. */
    val consentPreviewed: String = "",
    /** Always-on "Hey Jarvis" listening (foreground service). Off by default. */
    val wakeWordEnabled: Boolean = false,
    val wakeSensitivity: WakeSensitivity = WakeSensitivity.NORMAL,
    /** Microphone path for the wake word and spoken PIN (picked by "Compare microphones"). */
    val wakeMicSource: MicSource = MicSource.STANDARD,
    val wakeEngine: WakeEngineChoice = WakeEngineChoice.HEY_JARVIS,
    /** Wake phrasings learned from the user's voice ("Teach JARVIS your voice"). */
    val learnedWakePhrases: List<String> = emptyList(),
    /** How JARVIS acknowledges the wake word. */
    val wakeAck: dev.jarvis.voice.WakeAck = dev.jarvis.voice.WakeAck.CHIME,
    val wakeAckPhrase: String = "Yes?",
    val onboarding: Map<OnboardingStep, StepStatus> = OnboardingStep.entries.associateWith { StepStatus.TODO },
    /** Gmail privacy filter (D29): senders never cached or shown, comma separated. Banks by default. */
    val emailExcludedSenders: String = dev.jarvis.connectors.google.EmailFilter.DEFAULT_EXCLUDED_SENDERS.joinToString(", "),
) {
    val onboardingComplete: Boolean get() = onboarding.values.none { it == StepStatus.TODO }

    val security: SecuritySettings
        get() = SecuritySettings(
            level = policyLevel,
            rules = toolRules,
            sessions = SessionPolicy(personalSessionMinutes * 60_000L, sensitiveSessionMinutes * 60_000L),
            trustUnlockedPhone = trustUnlockedPhone,
        )

    companion object {
        const val DEFAULT_BUDGET_USD = 5.0
        const val DEFAULT_SNAPSHOT_MINUTES = 15
    }
}

class SettingsRepository(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            chatModel = p[CHAT_MODEL] ?: Models.DEFAULT_CHAT,
            quickModel = p[QUICK_MODEL] ?: Models.DEFAULT_QUICK,
            routeSimpleToQuick = p[ROUTE_SIMPLE] ?: true,
            serverFallback = p[SERVER_FALLBACK] ?: true,
            monthlyBudgetUsd = p[BUDGET] ?: AppSettings.DEFAULT_BUDGET_USD,
            stopAtBudget = p[STOP_AT_BUDGET] ?: true,
            snapshotMaxAgeMinutes = p[SNAPSHOT_MINUTES] ?: AppSettings.DEFAULT_SNAPSHOT_MINUTES,
            emailExcludedSenders = p[EMAIL_EXCLUDED_SENDERS] ?: AppSettings().emailExcludedSenders,
            policyLevel = p[POLICY_LEVEL]?.let { runCatching { PolicyLevel.valueOf(it) }.getOrNull() } ?: PolicyLevel.SMART,
            toolRules = ToolPolicy.decode(p[TOOL_RULES]),
            personalSessionMinutes = p[PERSONAL_MINUTES] ?: 5,
            sensitiveSessionMinutes = p[SENSITIVE_MINUTES] ?: 2,
            speakReplies = p[SPEAK_REPLIES] ?: true,
            preferOnDeviceStt = p[ON_DEVICE_STT] ?: true,
            speechRate = p[SPEECH_RATE] ?: 1.0f,
            trustUnlockedPhone = p[TRUST_UNLOCKED] ?: true,
            sendingEnabled = p[SENDING_ENABLED] ?: true,
            trustedContacts = p[TRUSTED_CONTACTS] ?: "",
            maxMessagesPerHour = p[MAX_PER_HOUR] ?: 20,
            readAppNotifications = p[READ_APP_NOTIFICATIONS] ?: false,
            ignoredNotificationApps = p[IGNORED_NOTIFICATION_APPS] ?: "",
            emailSendAllowed = p[EMAIL_SEND_ALLOWED] ?: false,
            privateMode = p[PRIVATE_MODE] ?: false,
            claudeConsent = p[CLAUDE_CONSENT] ?: "",
            consentPreviewed = p[CONSENT_PREVIEWED] ?: "",
            wakeWordEnabled = p[WAKE_WORD] ?: false,
            wakeSensitivity = p[WAKE_SENSITIVITY]?.let { runCatching { WakeSensitivity.valueOf(it) }.getOrNull() } ?: WakeSensitivity.NORMAL,
            wakeMicSource = p[WAKE_MIC]?.let { runCatching { MicSource.valueOf(it) }.getOrNull() } ?: MicSource.STANDARD,
            wakeEngine = p[WAKE_ENGINE]?.let { runCatching { WakeEngineChoice.valueOf(it) }.getOrNull() } ?: WakeEngineChoice.HEY_JARVIS,
            learnedWakePhrases = p[WAKE_LEARNED]?.split("|")?.filter { it.isNotBlank() }.orEmpty(),
            wakeAck = p[WAKE_ACK]?.let { runCatching { dev.jarvis.voice.WakeAck.valueOf(it) }.getOrNull() } ?: dev.jarvis.voice.WakeAck.CHIME,
            wakeAckPhrase = p[WAKE_ACK_PHRASE] ?: "Yes?",
            onboarding = OnboardingStep.entries.associateWith { step ->
                p[stepKey(step)]?.let { runCatching { StepStatus.valueOf(it) }.getOrNull() } ?: StepStatus.TODO
            },
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setChatModel(model: String) = store.edit { it[CHAT_MODEL] = model.trim() }
    suspend fun setQuickModel(model: String) = store.edit { it[QUICK_MODEL] = model.trim() }
    suspend fun setRouteSimpleToQuick(v: Boolean) = store.edit { it[ROUTE_SIMPLE] = v }
    suspend fun setServerFallback(v: Boolean) = store.edit { it[SERVER_FALLBACK] = v }
    suspend fun setMonthlyBudget(usd: Double) = store.edit { it[BUDGET] = usd.coerceAtLeast(0.0) }
    suspend fun setStopAtBudget(v: Boolean) = store.edit { it[STOP_AT_BUDGET] = v }
    suspend fun setSnapshotMaxAgeMinutes(v: Int) = store.edit { it[SNAPSHOT_MINUTES] = v.coerceIn(0, 24 * 60) }
    suspend fun setEmailExcludedSenders(v: String) = store.edit { it[EMAIL_EXCLUDED_SENDERS] = v.take(2000) }
    suspend fun setPolicyLevel(v: PolicyLevel) = store.edit { it[POLICY_LEVEL] = v.name }
    suspend fun setToolRule(tool: String, rule: ToolRule) = store.edit { p ->
        val rules = ToolPolicy.decode(p[TOOL_RULES]).toMutableMap().apply { if (rule == ToolRule.DEFAULT) remove(tool) else put(tool, rule) }
        p[TOOL_RULES] = ToolPolicy.encode(rules)
    }
    /** Session lengths are clamped: Tier 2 1-10 min, Tier 1 1-30 min. */
    suspend fun setSessionMinutes(personal: Int, sensitive: Int) = store.edit {
        it[PERSONAL_MINUTES] = personal.coerceIn(1, 30)
        it[SENSITIVE_MINUTES] = sensitive.coerceIn(1, 10)
    }
    suspend fun setSpeakReplies(v: Boolean) = store.edit { it[SPEAK_REPLIES] = v }
    suspend fun setPreferOnDeviceStt(v: Boolean) = store.edit { it[ON_DEVICE_STT] = v }
    suspend fun setSpeechRate(v: Float) = store.edit { it[SPEECH_RATE] = v.coerceIn(0.5f, 2f) }
    suspend fun setTrustUnlockedPhone(v: Boolean) = store.edit { it[TRUST_UNLOCKED] = v }
    suspend fun setSendingEnabled(v: Boolean) = store.edit { it[SENDING_ENABLED] = v }
    suspend fun setTrustedContacts(v: String) = store.edit { it[TRUSTED_CONTACTS] = v.take(1000) }
    suspend fun setReadAppNotifications(v: Boolean) = store.edit { it[READ_APP_NOTIFICATIONS] = v }
    suspend fun setIgnoredNotificationApps(v: String) = store.edit { it[IGNORED_NOTIFICATION_APPS] = v.take(1000) }
    suspend fun setEmailSendAllowed(v: Boolean) = store.edit { it[EMAIL_SEND_ALLOWED] = v }
    suspend fun setPrivateMode(v: Boolean) = store.edit { it[PRIVATE_MODE] = v }
    suspend fun setClaudeConsent(v: String) = store.edit { it[CLAUDE_CONSENT] = v; if (v.isEmpty()) it[CONSENT_PREVIEWED] = "" }
    suspend fun setConsentPreviewed(v: String) = store.edit { it[CONSENT_PREVIEWED] = v }
    suspend fun setWakeWordEnabled(v: Boolean) = store.edit { it[WAKE_WORD] = v }
    suspend fun setWakeSensitivity(v: WakeSensitivity) = store.edit { it[WAKE_SENSITIVITY] = v.name }
    suspend fun setWakeMicSource(v: MicSource) = store.edit { it[WAKE_MIC] = v.name }
    suspend fun setWakeEngine(v: WakeEngineChoice) = store.edit { it[WAKE_ENGINE] = v.name }
    suspend fun setWakeAck(v: dev.jarvis.voice.WakeAck) = store.edit { it[WAKE_ACK] = v.name }
    suspend fun setWakeAckPhrase(v: String) = store.edit { it[WAKE_ACK_PHRASE] = v.trim().take(40) }
    suspend fun setLearnedWakePhrases(v: List<String>) = store.edit { it[WAKE_LEARNED] = v.joinToString("|") { p -> p.replace("|", " ") } }
    suspend fun setStep(step: OnboardingStep, status: StepStatus) = store.edit { it[stepKey(step)] = status.name }

    private companion object {
        val CHAT_MODEL = stringPreferencesKey("chat_model")
        val QUICK_MODEL = stringPreferencesKey("quick_model")
        val ROUTE_SIMPLE = booleanPreferencesKey("route_simple_to_quick")
        val SERVER_FALLBACK = booleanPreferencesKey("server_fallback")
        val BUDGET = doublePreferencesKey("monthly_budget_usd")
        val STOP_AT_BUDGET = booleanPreferencesKey("stop_at_budget")
        val SNAPSHOT_MINUTES = intPreferencesKey("snapshot_max_age_minutes")
        val EMAIL_EXCLUDED_SENDERS = stringPreferencesKey("email_excluded_senders")
        val POLICY_LEVEL = stringPreferencesKey("policy_level")
        val TOOL_RULES = stringPreferencesKey("tool_rules")
        val PERSONAL_MINUTES = intPreferencesKey("session_personal_minutes")
        val SENSITIVE_MINUTES = intPreferencesKey("session_sensitive_minutes")
        val SPEAK_REPLIES = booleanPreferencesKey("speak_replies")
        val ON_DEVICE_STT = booleanPreferencesKey("prefer_on_device_stt")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val TRUST_UNLOCKED = androidx.datastore.preferences.core.booleanPreferencesKey("trust_unlocked_phone")
        val SENDING_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("sending_enabled")
        val TRUSTED_CONTACTS = stringPreferencesKey("trusted_contacts")
        val READ_APP_NOTIFICATIONS = androidx.datastore.preferences.core.booleanPreferencesKey("read_app_notifications")
        val IGNORED_NOTIFICATION_APPS = stringPreferencesKey("ignored_notification_apps")
        val EMAIL_SEND_ALLOWED = androidx.datastore.preferences.core.booleanPreferencesKey("email_send_allowed")
        val MAX_PER_HOUR = intPreferencesKey("max_messages_per_hour")
        val PRIVATE_MODE = androidx.datastore.preferences.core.booleanPreferencesKey("private_mode")
        val CLAUDE_CONSENT = stringPreferencesKey("claude_consent")
        val CONSENT_PREVIEWED = stringPreferencesKey("consent_previewed")
        val WAKE_WORD = booleanPreferencesKey("wake_word_enabled")
        val WAKE_SENSITIVITY = stringPreferencesKey("wake_sensitivity")
        val WAKE_MIC = stringPreferencesKey("wake_mic_source")
        val WAKE_ENGINE = stringPreferencesKey("wake_engine")
        val WAKE_LEARNED = stringPreferencesKey("wake_learned_phrases")
        val WAKE_ACK = stringPreferencesKey("wake_ack")
        val WAKE_ACK_PHRASE = stringPreferencesKey("wake_ack_phrase")
        fun stepKey(step: OnboardingStep) = stringPreferencesKey("onboarding_${step.name.lowercase()}")
    }
}
