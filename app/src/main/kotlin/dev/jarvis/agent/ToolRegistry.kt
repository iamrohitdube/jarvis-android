package dev.jarvis.agent

/** Tool groups from CLAUDE.md section 4. Only FINANCE has tools in Phase 1. */
enum class ToolGroup { FINANCE, CAREER, CALENDAR, EMAIL, MESSAGING, NAVIGATION, PHONE_CONTROL, MEMORY, WEB, VISION }

enum class RiskLevel { READ, WRITE, ACTION }

/** Access tiers from CLAUDE.md section 6. Enforcement arrives in Phase 3; the catalog carries them now. */
enum class AuthTier { OPEN, PERSONAL, SENSITIVE, ACTIONS }

data class ToolSpec(
    val name: String,
    val description: String,
    val group: ToolGroup,
    val risk: RiskLevel,
    val tier: AuthTier,
    val confirmationRequired: Boolean,
    /** Which connector provides it (MCP server name), or null for local tools. */
    val connectorId: String?,
    /** Lower-case phrases that select this tool when they appear in the request. */
    val keywords: List<String>,
    /** Enabled whenever its group is selected but no specific tool keyword matched. */
    val groupDefault: Boolean = false,
    /** Always enabled alongside any other tool of the same connector (helpers like id lookup). */
    val helper: Boolean = false,
)

/** The skill/tool catalog. The agent only ever sees the tools the router selects. */
object ToolRegistry {
    const val INDMONEY = "indmoney"

    val all: List<ToolSpec> = listOf(
        indmoney("networth_snapshot", "Total net worth, invested vs current, allocation, liabilities",
            AuthTier.SENSITIVE, listOf("net worth", "networth", "portfolio", "wealth", "assets", "liabilit", "how much money", "how am i doing"),
            groupDefault = true),
        indmoney("networth_allocation_breakdown", "Allocation detail for one asset type",
            AuthTier.SENSITIVE, listOf("allocation", "breakdown", "split", "asset class", "exposure")),
        indmoney("networth_holdings", "Positions with units, value, P&L, XIRR",
            AuthTier.SENSITIVE, listOf("holding", "position", "p&l", "pnl", "profit", "loss", "xirr", "my stocks", "my funds", "my shares")),
        indmoney("indian_stocks_sips", "Active stock SIPs", AuthTier.SENSITIVE, listOf("sip", "stock sip")),
        indmoney("mf_sips", "Active mutual fund SIPs", AuthTier.SENSITIVE, listOf("sip", "mutual fund sip", "mf sip")),
        indmoney("user_watchlist", "Instruments on the user's watchlist", AuthTier.PERSONAL, listOf("watchlist")),
        indmoney("lookup_ind_keys", "Resolve a stock/index/fund name to an identifier", AuthTier.OPEN, emptyList(), helper = true),
        indmoney("get_indian_stocks_details", "Live Indian stock price, day stats, 52-week range",
            AuthTier.OPEN, listOf("price", "stock", "share price", "nifty", "sensex", "trading at", "market cap")),
        indmoney("get_indian_stocks_ohlc", "OHLC candles", AuthTier.OPEN, listOf("ohlc", "candle", "chart", "price history")),
        indmoney("get_indian_stocks_option_chain", "Option chain around ATM", AuthTier.OPEN, listOf("option chain", "options", "open interest", " oi ")),
        indmoney("get_indian_stocks_greeks_history", "Greeks and IV history", AuthTier.OPEN, listOf("greeks", "implied volatility", " iv ", "delta", "theta")),
        indmoney("get_mf_by_category", "Mutual fund rankings by category", AuthTier.OPEN, listOf("best fund", "top fund", "fund ranking", "category", "best mutual fund")),
        indmoney("get_mf_funds_details", "Mutual fund profile, returns, holdings", AuthTier.OPEN, listOf("mutual fund", "fund details", "nav", "expense ratio")),
        indmoney("get_us_stocks_details", "Live US stock data", AuthTier.OPEN, listOf("us stock", "nasdaq", "nyse", "s&p", "dow jones")),
    )

    /** Phrases that select a group even when no single tool matched. */
    val groupKeywords: Map<ToolGroup, List<String>> = mapOf(
        ToolGroup.FINANCE to listOf("indmoney", "invest", "finance", "money", "fund", "stock", "market", "sip", "portfolio", "net worth", "networth"),
    )

    fun byName(name: String): ToolSpec? = all.firstOrNull { it.name == name }

    private fun indmoney(
        name: String, description: String, tier: AuthTier, keywords: List<String>,
        groupDefault: Boolean = false, helper: Boolean = false,
    ) = ToolSpec(
        name = name, description = description, group = ToolGroup.FINANCE, risk = RiskLevel.READ, tier = tier,
        confirmationRequired = false, connectorId = INDMONEY, keywords = keywords,
        groupDefault = groupDefault, helper = helper,
    )
}
