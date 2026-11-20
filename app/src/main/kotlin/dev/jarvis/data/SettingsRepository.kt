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
    val onboarding: Map<OnboardingStep, StepStatus> = OnboardingStep.entries.associateWith { StepStatus.TODO },
) {
    val onboardingComplete: Boolean get() = onboarding.values.none { it == StepStatus.TODO }

    val security: SecuritySettings
        get() = SecuritySettings(
            level = policyLevel,
            rules = toolRules,
            sessions = SessionPolicy(personalSessionMinutes * 60_000L, sensitiveSessionMinutes * 60_000L),
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
            policyLevel = p[POLICY_LEVEL]?.let { runCatching { PolicyLevel.valueOf(it) }.getOrNull() } ?: PolicyLevel.SMART,
            toolRules = ToolPolicy.decode(p[TOOL_RULES]),
            personalSessionMinutes = p[PERSONAL_MINUTES] ?: 5,
            sensitiveSessionMinutes = p[SENSITIVE_MINUTES] ?: 2,
            speakReplies = p[SPEAK_REPLIES] ?: true,
            preferOnDeviceStt = p[ON_DEVICE_STT] ?: true,
            speechRate = p[SPEECH_RATE] ?: 1.0f,
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
    suspend fun setStep(step: OnboardingStep, status: StepStatus) = store.edit { it[stepKey(step)] = status.name }

    private companion object {
        val CHAT_MODEL = stringPreferencesKey("chat_model")
        val QUICK_MODEL = stringPreferencesKey("quick_model")
        val ROUTE_SIMPLE = booleanPreferencesKey("route_simple_to_quick")
        val SERVER_FALLBACK = booleanPreferencesKey("server_fallback")
        val BUDGET = doublePreferencesKey("monthly_budget_usd")
        val STOP_AT_BUDGET = booleanPreferencesKey("stop_at_budget")
        val SNAPSHOT_MINUTES = intPreferencesKey("snapshot_max_age_minutes")
        val POLICY_LEVEL = stringPreferencesKey("policy_level")
        val TOOL_RULES = stringPreferencesKey("tool_rules")
        val PERSONAL_MINUTES = intPreferencesKey("session_personal_minutes")
        val SENSITIVE_MINUTES = intPreferencesKey("session_sensitive_minutes")
        val SPEAK_REPLIES = booleanPreferencesKey("speak_replies")
        val ON_DEVICE_STT = booleanPreferencesKey("prefer_on_device_stt")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        fun stepKey(step: OnboardingStep) = stringPreferencesKey("onboarding_${step.name.lowercase()}")
    }
}
