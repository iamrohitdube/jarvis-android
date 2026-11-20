package dev.jarvis.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.jarvis.agent.Models
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class StepStatus { TODO, DONE, SKIPPED }

/** Onboarding wizard steps. Phase 1 ships steps 1 and 4 (CLAUDE.md section 15). */
enum class OnboardingStep(val title: String) {
    ANTHROPIC_KEY("Anthropic API key"),
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
    val onboarding: Map<OnboardingStep, StepStatus> = OnboardingStep.entries.associateWith { StepStatus.TODO },
) {
    val onboardingComplete: Boolean get() = onboarding.values.none { it == StepStatus.TODO }

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
    suspend fun setStep(step: OnboardingStep, status: StepStatus) = store.edit { it[stepKey(step)] = status.name }

    private companion object {
        val CHAT_MODEL = stringPreferencesKey("chat_model")
        val QUICK_MODEL = stringPreferencesKey("quick_model")
        val ROUTE_SIMPLE = booleanPreferencesKey("route_simple_to_quick")
        val SERVER_FALLBACK = booleanPreferencesKey("server_fallback")
        val BUDGET = doublePreferencesKey("monthly_budget_usd")
        val STOP_AT_BUDGET = booleanPreferencesKey("stop_at_budget")
        val SNAPSHOT_MINUTES = intPreferencesKey("snapshot_max_age_minutes")
        fun stepKey(step: OnboardingStep) = stringPreferencesKey("onboarding_${step.name.lowercase()}")
    }
}
