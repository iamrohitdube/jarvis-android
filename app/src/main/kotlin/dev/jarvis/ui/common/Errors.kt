package dev.jarvis.ui.common

import dev.jarvis.agent.AgentError
import dev.jarvis.security.DenyReason

enum class FixAction { OPEN_SETTINGS, RETRY, NONE }

data class UiError(val message: String, val fix: FixAction, val fixLabel: String? = null)

/** Plain-language error messages with a single fix button (CLAUDE.md section 15). */
fun AgentError.toUi(): UiError = when (this) {
    AgentError.MissingKey -> UiError("No Anthropic API key yet.", FixAction.OPEN_SETTINGS, "Add key")
    AgentError.BadKey -> UiError("Anthropic rejected the API key.", FixAction.OPEN_SETTINGS, "Check key")
    is AgentError.ConnectorSignIn -> UiError("$name isn't signed in, or the sign-in expired.", FixAction.OPEN_SETTINGS, "Sign in again")
    is AgentError.RateLimited -> UiError(
        "Rate limited by Anthropic." + (retryAfterSeconds?.let { " Try again in ${it}s." } ?: " Try again shortly."),
        FixAction.RETRY, "Retry",
    )
    AgentError.Overloaded -> UiError("Claude is overloaded right now.", FixAction.RETRY, "Retry")
    is AgentError.Network -> UiError("Couldn't reach Anthropic. Check your connection.", FixAction.RETRY, "Retry")
    is AgentError.Refused -> UiError("Claude declined that request.", FixAction.NONE)
    is AgentError.AccessDenied -> when (reason) {
        DenyReason.POLICY -> UiError("That's turned off in Security settings.", FixAction.OPEN_SETTINGS, "Security settings")
        DenyReason.CANCELLED -> UiError("Cancelled. Nothing was shown.", FixAction.RETRY, "Try again")
        DenyReason.TIMEOUT -> UiError("No confirmation, so nothing was shown.", FixAction.RETRY, "Try again")
        DenyReason.LOCKED_OUT -> UiError("PIN is locked and there's no fingerprint to unlock it. Set up a fingerprint or screen lock on the phone.", FixAction.OPEN_SETTINGS, "Security settings")
        DenyReason.NO_AUTH_AVAILABLE -> UiError("Sensitive data needs a PIN or fingerprint. Set one up first.", FixAction.OPEN_SETTINGS, "Set up")
    }
    is AgentError.BudgetReached -> UiError(
        "This month's budget ($" + "%.2f".format(budgetUsd) + ") is used up, so I've stopped making requests.",
        FixAction.OPEN_SETTINGS, "Change budget",
    )
    is AgentError.Other -> UiError("Something went wrong: $detail", FixAction.RETRY, "Retry")
}
