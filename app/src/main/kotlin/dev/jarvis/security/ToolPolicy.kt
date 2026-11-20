package dev.jarvis.security

import dev.jarvis.agent.AuthTier
import dev.jarvis.agent.RiskLevel
import dev.jarvis.agent.ToolSpec

/** Global policy level (CLAUDE.md section 6). There is deliberately no OFF. */
enum class PolicyLevel { STRICT, SMART, AUTO }

/** Per-tool rule set by the user. DEFAULT follows the global level. */
enum class ToolRule { DEFAULT, ALLOW, ASK, DENY }

enum class PolicyDecision { ALLOW, ASK, DENY }

/**
 * Declarative per-tool policy, evaluated in code (never in the prompt).
 *
 * Defaults by level:
 * - STRICT: ask for everything except open (Tier 0) reads.
 * - SMART (default): ask for writes/actions and for sensitive (Tier 2) reads.
 * - AUTO: like SMART, but tools the user set to ALLOW run without asking, writes included.
 *
 * Overrides: DENY always wins. ASK always asks. ALLOW is honoured for reads at any level, and for
 * writes/actions only in AUTO. Tools that require confirmation (sending messages, creating events)
 * are always ASK unless denied. For sensitive reads, "ask" is satisfied by the PIN/fingerprint check
 * (see [AuthCoordinator]); for lower tiers it is an Allow/Don't allow confirmation.
 */
object ToolPolicy {
    fun decide(spec: ToolSpec, level: PolicyLevel, rules: Map<String, ToolRule>): PolicyDecision {
        val rule = rules[spec.name] ?: ToolRule.DEFAULT
        if (rule == ToolRule.DENY) return PolicyDecision.DENY
        if (spec.confirmationRequired || rule == ToolRule.ASK) return PolicyDecision.ASK
        val isRead = spec.risk == RiskLevel.READ
        if (rule == ToolRule.ALLOW && (isRead || level == PolicyLevel.AUTO)) return PolicyDecision.ALLOW
        return when (level) {
            PolicyLevel.STRICT -> if (isRead && spec.tier == AuthTier.OPEN) PolicyDecision.ALLOW else PolicyDecision.ASK
            PolicyLevel.SMART, PolicyLevel.AUTO ->
                if (!isRead || spec.tier >= AuthTier.SENSITIVE) PolicyDecision.ASK else PolicyDecision.ALLOW
        }
    }

    /** Compact storage format for Settings: "tool=RULE,tool=RULE" (DEFAULT entries omitted). */
    fun encode(rules: Map<String, ToolRule>): String =
        rules.filterValues { it != ToolRule.DEFAULT }.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value.name}" }

    fun decode(raw: String?): Map<String, ToolRule> = raw.orEmpty().split(",").mapNotNull { part ->
        val (k, v) = part.split("=").takeIf { it.size == 2 } ?: return@mapNotNull null
        val rule = runCatching { ToolRule.valueOf(v.trim()) }.getOrNull() ?: return@mapNotNull null
        k.trim().takeIf { it.isNotEmpty() }?.let { it to rule }
    }.toMap()
}
