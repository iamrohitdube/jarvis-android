package dev.jarvis.ui.common

import dev.jarvis.agent.AgentError

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
    is AgentError.BudgetReached -> UiError(
        "This month's budget ($" + "%.2f".format(budgetUsd) + ") is used up, so I've stopped making requests.",
        FixAction.OPEN_SETTINGS, "Change budget",
    )
    is AgentError.Other -> UiError("Something went wrong: $detail", FixAction.RETRY, "Retry")
}
