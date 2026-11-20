package dev.jarvis.security

import dev.jarvis.agent.AuthTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the user proved it's them. Audited; never the secret itself. */
enum class AuthMethod { PIN, BIOMETRIC, DEVICE_CREDENTIAL, DEVICE_UNLOCK }

/** Session lengths (CLAUDE.md section 6: Tier 1 ~5 min, Tier 2 ~2 min). Editable in Settings. */
data class SessionPolicy(val personalMs: Long = 5 * 60_000L, val sensitiveMs: Long = 2 * 60_000L)

/** A granted session for one tier. */
data class Session(val tier: AuthTier, val method: AuthMethod, val grantedAt: Long)

/**
 * In-memory auth sessions (never persisted: a restart always needs fresh auth). A Tier 2 grant also
 * covers Tier 1. Sessions end on expiry, on screen-off, and on "lock".
 */
class SessionManager(private val clock: () -> Long = System::currentTimeMillis) {
    private val _sessions = MutableStateFlow<Map<AuthTier, Session>>(emptyMap())
    /** Changes on grant/lock; expiry is time-based, so callers re-check with [isValid]. */
    val sessions: StateFlow<Map<AuthTier, Session>> = _sessions

    fun grant(tier: AuthTier, method: AuthMethod) {
        if (tier == AuthTier.OPEN) return
        val now = clock()
        val granted = buildMap {
            putAll(_sessions.value)
            put(tier, Session(tier, method, now))
            if (tier >= AuthTier.SENSITIVE) put(AuthTier.PERSONAL, Session(AuthTier.PERSONAL, method, now))
        }
        _sessions.value = granted
    }

    /** Valid session covering [tier]; Tier 3 actions use the Tier 2 session (plus their own confirmation). */
    fun current(tier: AuthTier, policy: SessionPolicy): Session? {
        if (tier == AuthTier.OPEN) return null
        val key = if (tier >= AuthTier.SENSITIVE) AuthTier.SENSITIVE else AuthTier.PERSONAL
        val s = _sessions.value[key] ?: return null
        val ttl = if (key == AuthTier.SENSITIVE) policy.sensitiveMs else policy.personalMs
        val age = clock() - s.grantedAt
        return s.takeIf { age in 0..ttl }
    }

    fun isValid(tier: AuthTier, policy: SessionPolicy): Boolean = tier == AuthTier.OPEN || current(tier, policy) != null

    /** Ends every session (screen off, "lock", or Settings → Lock now). */
    fun lock() { _sessions.value = emptyMap() }
}
