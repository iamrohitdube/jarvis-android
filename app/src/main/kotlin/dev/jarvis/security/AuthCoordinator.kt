package dev.jarvis.security

import dev.jarvis.agent.AuthTier
import dev.jarvis.agent.ToolSpec
import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditSink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What the phone can offer for the fingerprint path. */
enum class BiometricStatus {
    /** Class 3 biometric enrolled (fingerprint/face); device credential fallback on Android 11+. */
    AVAILABLE,
    /** No strong biometric, but the phone's PIN/pattern/password can be used (Android 11+). */
    CREDENTIAL_ONLY,
    NONE,
}

fun interface BiometricAvailability {
    fun status(): BiometricStatus
}

/** Outcome reported by the UI after showing the system biometric prompt. */
sealed interface BiometricOutcome {
    data class Success(val method: AuthMethod) : BiometricOutcome
    /** The user tapped "Use PIN" (Android 10 fallback button). */
    data object UsePin : BiometricOutcome
    data object Cancelled : BiometricOutcome
    data class Error(val code: Int) : BiometricOutcome
}

/** Security settings the coordinator reads on each request. */
data class SecuritySettings(
    val level: PolicyLevel = PolicyLevel.SMART,
    val rules: Map<String, ToolRule> = emptyMap(),
    val sessions: SessionPolicy = SessionPolicy(),
    /**
     * The phone being unlocked (screen lock passed) counts as the check for personal and sensitive READS: no
     * "PIN or fingerprint?" while the user holds an unlocked phone (D33, user request 2026-11-25). A locked phone
     * still needs PIN or fingerprint. Actions (Tier 3) are never covered.
     */
    val trustUnlockedPhone: Boolean = true,
)

/** What the auth UI should show. The prompt itself never contains the protected data. */
data class AuthPrompt(
    val id: Long,
    val stage: Stage,
    val tier: AuthTier,
    val pinAvailable: Boolean,
    val pinLocked: Boolean,
    val biometricAvailable: Boolean,
    /** Status line, e.g. "Wrong PIN. 2 tries left." */
    val message: String? = null,
    /** CHOOSE stage: seconds until fingerprint is picked automatically. */
    val defaultInSeconds: Int? = null,
    /** CONFIRM stage: what is about to be used. */
    val confirmLabels: List<String> = emptyList(),
) {
    enum class Stage { CHOOSE, PIN, BIOMETRIC, CONFIRM }
}

fun interface LockoutNotifier {
    fun pinLocked()
}

/**
 * Runs the PIN-or-fingerprint flow (CLAUDE.md section 6) and implements [AccessController].
 * One prompt at a time. The UI observes [prompt] and reports back via [choose], [submitPin],
 * [onBiometricResult], [confirm] and [cancel].
 */
class AuthCoordinator(
    private val settings: suspend () -> SecuritySettings,
    private val sessions: SessionManager,
    private val pin: PinAuthenticator,
    private val biometric: BiometricAvailability,
    private val audit: AuditSink,
    private val notifier: LockoutNotifier = LockoutNotifier { },
    private val choiceTimeoutMs: Long = CHOICE_TIMEOUT_MS,
    private val promptTimeoutMs: Long = PROMPT_TIMEOUT_MS,
    /** True while the phone is unlocked (keyguard passed). Default false: tests and unknown states always ask. */
    private val deviceUnlocked: () -> Boolean = { false },
) : AccessController {

    private val gate = Mutex()
    private val actions = Channel<Action>(Channel.UNLIMITED)
    private val _prompt = MutableStateFlow<AuthPrompt?>(null)
    val prompt: StateFlow<AuthPrompt?> = _prompt
    private var nextId = 0L

    private sealed interface Action {
        data class Choose(val method: AuthMethod) : Action
        /** [spoken] = said out loud (then [voiceMatched] must be true for the PIN to be checked at all). */
        data class Pin(val raw: String, val spoken: Boolean = false, val voiceMatched: Boolean = false, val similarity: Float? = null) : Action
        data class Bio(val outcome: BiometricOutcome) : Action
        data class Confirm(val allow: Boolean) : Action
        data object Cancel : Action
    }

    // ---- UI callbacks ----
    fun choose(method: AuthMethod) { actions.trySend(Action.Choose(method)) }
    /** The PIN goes straight to verification; it's never stored, logged or shown. */
    fun submitPin(raw: String) { actions.trySend(Action.Pin(raw)) }
    /**
     * A PIN said out loud. Voice match was removed (D33), so [SpokenPin.voiceMatched] is always true now; the check
     * below stays as a guard. If the voice didn't match, the PIN is not
     * checked at all (no oracle for a stranger) and the attempt doesn't count toward the lockout.
     */
    fun submitSpokenPin(spoken: SpokenPin) {
        actions.trySend(Action.Pin(spoken.digits.orEmpty(), spoken = true, voiceMatched = spoken.voiceMatched, similarity = spoken.similarity))
    }
    fun onBiometricResult(outcome: BiometricOutcome) { actions.trySend(Action.Bio(outcome)) }
    fun confirm(allow: Boolean) { actions.trySend(Action.Confirm(allow)) }
    fun cancel() { actions.trySend(Action.Cancel) }

    // ---- AccessController ----
    override suspend fun authorize(tools: List<ToolSpec>, preferred: AuthMethod?): AccessResult = gate.withLock {
        val s = settings()
        val decisions = tools.associateWith { ToolPolicy.decide(it, s.level, s.rules) }
        val allowed = tools.filter { decisions[it] != PolicyDecision.DENY }
        if (allowed.none { !it.helper }) {
            val names = tools.filterNot { it.helper }.map { it.name }
            names.forEach { audit(AuditCategory.TOOL, "policy", "denied", tool = it) }
            return@withLock AccessResult.Denied(DenyReason.POLICY, names)
        }
        val tier = allowed.requiredTier()
        var method: AuthMethod? = null
        if (tier >= AuthTier.PERSONAL) {
            val existing = sessions.current(tier, s.sessions)
            if (existing != null) {
                method = existing.method
            } else if (trustsUnlockedPhone(tier, s)) {
                method = AuthMethod.DEVICE_UNLOCK
            } else {
                when (val r = authenticate(tier, preferred, strongOnly = false)) {
                    is AuthOutcome.Ok -> method = r.method
                    is AuthOutcome.Failed -> return@withLock AccessResult.Denied(r.reason, allowed.map { it.name })
                }
            }
        }
        // Lower-tier tools the policy says to ask about get an explicit Allow / Don't allow.
        // (For Tier 2 tools the PIN/fingerprint check above is the confirmation.)
        val toConfirm = allowed.filter { !it.helper && decisions[it] == PolicyDecision.ASK && it.tier < AuthTier.SENSITIVE }
        if (toConfirm.isNotEmpty() && !confirmTools(tier, toConfirm)) {
            return@withLock AccessResult.Denied(DenyReason.CANCELLED, toConfirm.map { it.name })
        }
        AccessResult.Granted(
            tools = allowed,
            method = method,
            screenOnly = method != null && method != AuthMethod.PIN && tier >= AuthTier.SENSITIVE,
            sensitive = tier >= AuthTier.SENSITIVE,
        )
    }

    /** Unlock for reading saved sensitive messages (no tools). Returns true if a session now covers [tier]. */
    suspend fun unlock(tier: AuthTier = AuthTier.SENSITIVE): Boolean = gate.withLock {
        val s = settings()
        if (sessions.isValid(tier, s.sessions)) return@withLock true
        if (trustsUnlockedPhone(tier, s)) return@withLock true
        authenticate(tier, preferred = null, strongOnly = false) is AuthOutcome.Ok
    }

    /** Unlocked phone + setting on + a read tier: grant a session without a prompt (D33). */
    private suspend fun trustsUnlockedPhone(tier: AuthTier, s: SecuritySettings): Boolean {
        if (!s.trustUnlockedPhone || tier > AuthTier.SENSITIVE || !deviceUnlocked()) return false
        sessions.grant(tier, AuthMethod.DEVICE_UNLOCK)
        audit(AuditCategory.AUTH, "device_unlock", "ok", tier = tier, method = AuthMethod.DEVICE_UNLOCK)
        return true
    }

    /** Fingerprint/device credential only: needed to set, change or remove the PIN. */
    suspend fun strongAuth(): StrongAuthProof? = gate.withLock {
        when (val r = authenticate(AuthTier.SENSITIVE, AuthMethod.BIOMETRIC, strongOnly = true)) {
            is AuthOutcome.Ok -> r.proof
            is AuthOutcome.Failed -> null
        }
    }

    /**
     * Ends all sessions and records why. An explicit lock ("lock", Lock now) also re-hides sensitive
     * messages the user had already revealed; screen-off doesn't.
     */
    suspend fun lock(reason: String) {
        sessions.lock()
        if (reason != SCREEN_OFF) sessions.forgetReveals()
        audit(AuditCategory.AUTH, "session_lock", "ok", detail = reason)
    }

    // ---- flow ----
    private sealed interface AuthOutcome {
        data class Ok(val method: AuthMethod, val proof: StrongAuthProof?) : AuthOutcome
        data class Failed(val reason: DenyReason) : AuthOutcome
    }

    private suspend fun authenticate(tier: AuthTier, preferred: AuthMethod?, strongOnly: Boolean): AuthOutcome {
        drainActions()
        val result = withTimeoutOrNull(promptTimeoutMs) { runFlow(tier, preferred, strongOnly) }
            ?: AuthOutcome.Failed(DenyReason.TIMEOUT).also { audit(AuditCategory.AUTH, "prompt", "timeout", tier = tier) }
        _prompt.value = null
        return result
    }

    private suspend fun runFlow(tier: AuthTier, preferred: AuthMethod?, strongOnly: Boolean): AuthOutcome {
        val bioOk = biometric.status() != BiometricStatus.NONE
        val pinSet = !strongOnly && pin.hasPin()
        var pinLocked = pinSet && pin.isLocked()
        val pinUsable = { pinSet && !pinLocked }

        if (!bioOk && !pinUsable()) {
            val reason = if (pinSet) DenyReason.LOCKED_OUT else DenyReason.NO_AUTH_AVAILABLE
            audit(AuditCategory.AUTH, "prompt", reason.name.lowercase(), tier = tier)
            return AuthOutcome.Failed(reason)
        }
        audit(AuditCategory.AUTH, "prompt", "shown", tier = tier)

        var method: AuthMethod? = when {
            preferred == AuthMethod.PIN && pinUsable() -> AuthMethod.PIN
            preferred == AuthMethod.BIOMETRIC && bioOk -> AuthMethod.BIOMETRIC
            !pinUsable() -> AuthMethod.BIOMETRIC
            !bioOk -> AuthMethod.PIN
            else -> null
        }
        var message: String? = if (pinLocked) PIN_LOCKED_MESSAGE else null

        if (method == null) {
            show(AuthPrompt.Stage.CHOOSE, tier, pinSet, pinLocked, bioOk, message, defaultInSeconds = (choiceTimeoutMs / 1000).toInt())
            // No answer within ~6 s (null from the timeout): fingerprint, since people may be nearby.
            val choice = withTimeoutOrNull(choiceTimeoutMs) { awaitChoice(pinUsable) }
            method = when (choice) {
                null -> AuthMethod.BIOMETRIC
                is Choice.Picked -> choice.method
                Choice.Cancelled -> {
                    audit(AuditCategory.AUTH, "prompt", "cancelled", tier = tier)
                    return AuthOutcome.Failed(DenyReason.CANCELLED)
                }
            }
        }

        while (true) {
            when (method) {
                AuthMethod.PIN -> {
                    show(AuthPrompt.Stage.PIN, tier, pinSet, pinLocked, bioOk, message)
                    when (val a = actions.receive()) {
                        is Action.Pin -> if (a.spoken && !a.voiceMatched) {
                            audit(AuditCategory.AUTH, "voice_pin", "voice_mismatch", tier = tier, method = AuthMethod.PIN,
                                detail = a.similarity?.let { "score %.2f".format(java.util.Locale.ROOT, it) })
                            message = VOICE_MISMATCH_MESSAGE
                            if (bioOk) method = AuthMethod.BIOMETRIC
                        } else when (val r = pin.verify(a.raw)) {
                            PinResult.Ok -> {
                                audit(AuditCategory.AUTH, if (a.spoken) "voice_pin" else "pin", "ok", tier = tier, method = AuthMethod.PIN)
                                sessions.grant(tier, AuthMethod.PIN)
                                return AuthOutcome.Ok(AuthMethod.PIN, null)
                            }
                            is PinResult.Wrong -> {
                                audit(AuditCategory.AUTH, if (a.spoken) "voice_pin" else "pin", "wrong", tier = tier, method = AuthMethod.PIN)
                                message = "Wrong PIN. ${r.attemptsLeft} ${if (r.attemptsLeft == 1) "try" else "tries"} left."
                            }
                            PinResult.Malformed -> {
                                audit(AuditCategory.AUTH, if (a.spoken) "voice_pin" else "pin", "malformed", tier = tier, method = AuthMethod.PIN)
                                message = if (a.spoken) "Didn't catch the PIN. Say it again, or type it." else "That isn't a PIN (4-8 digits). Try again."
                            }
                            PinResult.Locked -> {
                                audit(AuditCategory.AUTH, "pin", "locked", tier = tier, method = AuthMethod.PIN)
                                if (!pinLocked) {
                                    audit(AuditCategory.AUTH, "lockout", "locked", method = AuthMethod.PIN)
                                    notifier.pinLocked()
                                }
                                pinLocked = true
                                message = PIN_LOCKED_MESSAGE
                                if (!bioOk) return AuthOutcome.Failed(DenyReason.LOCKED_OUT)
                                method = AuthMethod.BIOMETRIC
                            }
                            PinResult.NotSet -> {
                                if (!bioOk) return AuthOutcome.Failed(DenyReason.NO_AUTH_AVAILABLE)
                                method = AuthMethod.BIOMETRIC
                            }
                        }
                        is Action.Choose -> if (a.method != AuthMethod.PIN && bioOk) method = AuthMethod.BIOMETRIC
                        Action.Cancel -> {
                            audit(AuditCategory.AUTH, "pin", "cancelled", tier = tier, method = AuthMethod.PIN)
                            return AuthOutcome.Failed(DenyReason.CANCELLED)
                        }
                        else -> Unit
                    }
                }
                else -> {
                    show(AuthPrompt.Stage.BIOMETRIC, tier, pinSet, pinLocked, bioOk, message)
                    when (val a = actions.receive()) {
                        is Action.Bio -> when (val o = a.outcome) {
                            is BiometricOutcome.Success -> {
                                val proof = StrongAuthProof(o.method)
                                audit(AuditCategory.AUTH, "biometric", "ok", tier = tier, method = o.method)
                                if (pinSet && pin.failedAttempts() > 0) {
                                    pin.resetLockout(proof)
                                    audit(AuditCategory.AUTH, "lockout_reset", "ok", method = o.method)
                                }
                                if (!strongOnly) sessions.grant(tier, o.method)
                                return AuthOutcome.Ok(o.method, proof)
                            }
                            BiometricOutcome.UsePin -> if (pinUsable()) { method = AuthMethod.PIN; message = null } else {
                                audit(AuditCategory.AUTH, "biometric", "cancelled", tier = tier)
                                return AuthOutcome.Failed(if (pinLocked) DenyReason.LOCKED_OUT else DenyReason.CANCELLED)
                            }
                            BiometricOutcome.Cancelled -> {
                                audit(AuditCategory.AUTH, "biometric", "cancelled", tier = tier)
                                return AuthOutcome.Failed(DenyReason.CANCELLED)
                            }
                            is BiometricOutcome.Error -> {
                                audit(AuditCategory.AUTH, "biometric", "error", tier = tier, detail = "code ${o.code}")
                                return AuthOutcome.Failed(DenyReason.CANCELLED)
                            }
                        }
                        is Action.Choose -> if (a.method == AuthMethod.PIN && pinUsable()) { method = AuthMethod.PIN; message = null }
                        Action.Cancel -> {
                            audit(AuditCategory.AUTH, "biometric", "cancelled", tier = tier)
                            return AuthOutcome.Failed(DenyReason.CANCELLED)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private sealed interface Choice {
        data class Picked(val method: AuthMethod) : Choice
        data object Cancelled : Choice
    }

    private suspend fun awaitChoice(pinUsable: () -> Boolean): Choice {
        while (true) {
            when (val a = actions.receive()) {
                is Action.Choose -> if (a.method != AuthMethod.PIN || pinUsable()) return Choice.Picked(a.method)
                Action.Cancel -> return Choice.Cancelled
                else -> Unit // stale input from an earlier prompt
            }
        }
    }

    private suspend fun awaitConfirm(): Boolean {
        while (true) {
            when (val a = actions.receive()) {
                is Action.Confirm -> return a.allow
                Action.Cancel -> return false
                else -> Unit
            }
        }
    }

    private suspend fun confirmTools(tier: AuthTier, tools: List<ToolSpec>): Boolean {
        drainActions()
        show(AuthPrompt.Stage.CONFIRM, tier, pinAvailable = false, pinLocked = false, biometricAvailable = false, message = null,
            confirmLabels = tools.map { it.description })
        val allowed = withTimeoutOrNull(promptTimeoutMs) { awaitConfirm() } ?: false
        _prompt.value = null
        tools.forEach { audit(AuditCategory.TOOL, "confirm", if (allowed) "allowed" else "declined", tool = it.name, tier = it.tier) }
        return allowed
    }

    private fun show(
        stage: AuthPrompt.Stage, tier: AuthTier, pinAvailable: Boolean, pinLocked: Boolean, biometricAvailable: Boolean,
        message: String?, defaultInSeconds: Int? = null, confirmLabels: List<String> = emptyList(),
    ) {
        _prompt.value = AuthPrompt(++nextId, stage, tier, pinAvailable, pinLocked, biometricAvailable, message, defaultInSeconds, confirmLabels)
    }

    private fun drainActions() {
        while (actions.tryReceive().isSuccess) Unit
    }

    private suspend fun audit(
        category: AuditCategory, action: String, outcome: String,
        tool: String? = null, tier: AuthTier? = null, method: AuthMethod? = null, detail: String? = null,
    ) = audit.record(AuditEvent(category, action, outcome, tool = tool, tier = tier?.name, method = method?.name?.lowercase(), detail = detail))

    companion object {
        /** Time to answer "PIN or fingerprint?" before fingerprint is chosen (6 s in the spec; +5 s at the user's request, 2026-11-21). */
        const val CHOICE_TIMEOUT_MS = 11_000L
        const val PROMPT_TIMEOUT_MS = 120_000L
        const val PIN_LOCKED_MESSAGE = "PIN is locked, use fingerprint."
        const val SCREEN_OFF = "screen_off"
        const val VOICE_MISMATCH_MESSAGE = "That didn't sound like you. Use fingerprint."
    }
}
