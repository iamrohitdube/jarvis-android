package dev.jarvis.security

import dev.jarvis.agent.AuthTier
import dev.jarvis.agent.ToolSpec

enum class DenyReason {
    /** Every tool the request needs is set to DENY. */
    POLICY,
    CANCELLED,
    TIMEOUT,
    /** PIN locked and no fingerprint/device credential available. */
    LOCKED_OUT,
    /** No PIN set and no fingerprint/device credential on this phone. */
    NO_AUTH_AVAILABLE,
    /** Hands-free with no screen (pocket or locked): private data needs the phone in hand. */
    NEEDS_SCREEN,
}

sealed interface AccessResult {
    data class Granted(
        /** Tools allowed to run (denied ones removed). */
        val tools: List<ToolSpec>,
        /** How the user authenticated for this request (or the session it reused); null if no auth was needed. */
        val method: AuthMethod?,
        /** Fingerprint/device-credential path: show the result, don't speak it (CLAUDE.md section 6). */
        val screenOnly: Boolean,
        /** The reply will contain Tier 2 data: hide it at rest until the user unlocks. */
        val sensitive: Boolean,
    ) : AccessResult

    data class Denied(val reason: DenyReason, val toolNames: List<String> = emptyList()) : AccessResult
}

/**
 * The security gate the agent must pass before it uses any tool or saved tool data. Implemented by
 * [AuthCoordinator]; policy and auth decisions live here, never in prompts.
 */
fun interface AccessController {
    suspend fun authorize(tools: List<ToolSpec>, preferred: AuthMethod?): AccessResult
}

/** Highest tier among the non-helper tools (helpers like id lookup don't raise the bar). */
fun List<ToolSpec>.requiredTier(): AuthTier =
    filterNot { it.helper }.maxOfOrNull { it.tier } ?: AuthTier.OPEN

/**
 * Lets the user pre-empt the PIN-or-fingerprint question ("net worth, fingerprint"). The method word is
 * removed before the text reaches the model. Only a leading or trailing word counts, so "pin code of
 * my area" isn't mistaken for a choice.
 */
object AuthMethodParser {
    private val words = mapOf(
        "fingerprint" to AuthMethod.BIOMETRIC, "finger print" to AuthMethod.BIOMETRIC,
        "biometric" to AuthMethod.BIOMETRIC, "pin" to AuthMethod.PIN,
    )
    private const val SEP = "[\\s,.;:!?-]"

    fun extract(text: String): Pair<String, AuthMethod?> {
        for ((word, method) in words) {
            val trailing = Regex("(?i)$SEP+(?:use\\s+|with\\s+|by\\s+)?$word$SEP*$")
            trailing.find(text)?.let { m ->
                val rest = text.substring(0, m.range.first).trim()
                if (rest.isNotEmpty()) return rest to method
            }
            val leading = Regex("(?i)^\\s*(?:use\\s+|with\\s+|by\\s+)?$word$SEP+")
            leading.find(text)?.let { m ->
                val rest = text.substring(m.range.last + 1).trim()
                if (rest.isNotEmpty()) return rest to method
            }
        }
        return text to null
    }
}
