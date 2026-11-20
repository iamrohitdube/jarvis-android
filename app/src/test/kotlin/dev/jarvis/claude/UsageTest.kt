package dev.jarvis.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class UsageTest {
    @Test fun sonnetCostIncludesCacheRates() {
        val u = Usage(inputTokens = 1_000_000, outputTokens = 100_000, cacheCreationInputTokens = 1_000_000, cacheReadInputTokens = 1_000_000)
        // 2.00 + 1.00 + 2.50 + 0.10
        assertEquals(5.60, Pricing.cost("claude-sonnet-5-5", u)!!, 1e-9)
    }

    @Test fun haikuSwitchesToLongPromptRateAbove100k() {
        val short = Usage(inputTokens = 100_000, outputTokens = 0)
        val long = Usage(inputTokens = 100_001, outputTokens = 0)
        assertEquals(0.01, Pricing.cost("claude-haiku-5-5", short)!!, 1e-9)
        assertEquals(100_001 * 0.50 / 1e6, Pricing.cost("claude-haiku-5-5", long)!!, 1e-9)
    }

    @Test fun unknownModelHasNoCost() {
        assertNull(Pricing.cost("some-other-model", Usage(1, 1)))
    }

    private fun entry(ts: Instant, cost: Double) = UsageEntry(ts.toEpochMilli(), "m", "chat", 0, 0, 0, 0, cost, 0, null, 0)

    @Test fun summaryCountsOnlyCurrentMonthAndFlagsBudget() {
        val now = Instant.parse("2026-11-20T10:00:00Z")
        val entries = listOf(
            entry(Instant.parse("2026-10-31T23:00:00Z"), 50.0), // last month: ignored
            entry(Instant.parse("2026-11-01T00:30:00Z"), 4.0),
            entry(Instant.parse("2026-11-19T12:00:00Z"), 4.5),
        )
        val s = UsageSummary.of(entries, budgetUsd = 10.0, now = now, zone = ZoneOffset.UTC)
        assertEquals(8.5, s.monthCostUsd, 1e-9)
        assertEquals(2, s.monthRequests)
        assertEquals(BudgetLevel.WARNING, s.budgetLevel)
    }

    @Test fun budgetLevels() {
        val now = Instant.parse("2026-11-20T10:00:00Z")
        fun level(cost: Double, budget: Double) =
            UsageSummary.of(listOf(entry(now, cost)), budget, now, ZoneOffset.UTC).budgetLevel
        assertEquals(BudgetLevel.NONE, level(5.0, 0.0))
        assertEquals(BudgetLevel.OK, level(7.99, 10.0))
        assertEquals(BudgetLevel.WARNING, level(8.0, 10.0))
        assertEquals(BudgetLevel.EXCEEDED, level(10.0, 10.0))
    }
}
