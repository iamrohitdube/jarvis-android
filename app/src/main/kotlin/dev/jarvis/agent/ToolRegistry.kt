package dev.jarvis.agent

/** Tool groups from CLAUDE.md section 4. */
enum class ToolGroup { FINANCE, CAREER, CALENDAR, EMAIL, FILES, CALLS, MESSAGING, NAVIGATION, PHONE_CONTROL, MEMORY, WEB, VISION }

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
    /**
     * Whole-account read with no meaningful arguments (e.g. net worth): its result may be cached as a
     * snapshot and reused for a short time. Live market data is never a snapshot tool.
     */
    val snapshot: Boolean = false,
    /**
     * Tool argument filled from the hand-back's `item` (e.g. the Gmail search query). Such a tool only runs when an
     * item is given; without one the connector's default read answers instead.
     */
    val itemArg: String? = null,
) {
    /** The user's own connector data (above Tier 0): answered on the phone, never sent to Claude (D29). */
    val isPersonal: Boolean get() = connectorId != null && tier != AuthTier.OPEN
}

/** The skill/tool catalog. The agent only ever sees the tools the router selects. */
object ToolRegistry {
    const val INDMONEY = "indmoney"
    const val GMAIL = "gmail"
    const val CALENDAR = "calendar"
    const val DRIVE = "drive"
    const val CALLS = "calls"
    const val RECORDINGS = "recordings"
    const val SMS = "sms"
    const val CHATS = "notifications"

    /** Sources read from the phone itself with an Android permission (no account): "allow access", not "sign in". */
    val LOCAL_SOURCES = setOf(CALENDAR, CALLS, RECORDINGS, SMS, CHATS)

    val all: List<ToolSpec> = listOf(
        indmoney("networth_snapshot", "Total net worth, invested vs current, allocation, liabilities",
            AuthTier.SENSITIVE, listOf(
                "net worth", "networth", "portfolio", "wealth", "assets", "liabilit", "how much money do i have", "how much money i have", "how am i doing",
                // Unambiguous bank phrases only; anything vaguer goes to Claude, which can hand it back (PrivateDataTool).
                "bank balance", "savings account", "savings balance", "account balance", "my balance", "cash balance",
                "fixed deposit", "my fd", "money in my bank", "money in the bank",
            ),
            groupDefault = true, snapshot = true),
        indmoney("networth_allocation_breakdown", "Allocation detail for one asset type",
            AuthTier.SENSITIVE, listOf("allocation", "breakdown", "split", "asset class", "exposure")),
        indmoney("networth_holdings", "Positions with units, value, P&L, XIRR",
            AuthTier.SENSITIVE, listOf("holding", "position", "p&l", "pnl", "profit", "loss", "xirr", "my stocks", "my funds", "my shares"), snapshot = true),
        indmoney("indian_stocks_sips", "Active stock SIPs", AuthTier.SENSITIVE, listOf("sip", "stock sip"), snapshot = true),
        indmoney("mf_sips", "Active mutual fund SIPs", AuthTier.SENSITIVE, listOf("sip", "mutual fund sip", "mf sip"), snapshot = true),
        indmoney("user_watchlist", "Instruments on the user's watchlist", AuthTier.PERSONAL, listOf("watchlist"), snapshot = true),
        indmoney("lookup_ind_keys", "Resolve a stock/index/fund name to an identifier", AuthTier.OPEN, emptyList(), helper = true),
        indmoney("get_indian_stocks_details", "Live Indian stock price, day stats, 52-week range",
            AuthTier.OPEN, listOf("price", "stock", "share price", "nifty", "sensex", "trading at", "market cap")),
        indmoney("get_indian_stocks_ohlc", "OHLC candles", AuthTier.OPEN, listOf("ohlc", "candle", "chart", "price history")),
        indmoney("get_indian_stocks_option_chain", "Option chain around ATM", AuthTier.OPEN, listOf("option chain", "options", "open interest", " oi ")),
        indmoney("get_indian_stocks_greeks_history", "Greeks and IV history", AuthTier.OPEN, listOf("greeks", "implied volatility", " iv ", "delta", "theta")),
        indmoney("get_mf_by_category", "Mutual fund rankings by category", AuthTier.OPEN, listOf("best fund", "top fund", "fund ranking", "category", "best mutual fund")),
        indmoney("get_mf_funds_details", "Mutual fund profile, returns, holdings", AuthTier.OPEN, listOf("mutual fund", "fund details", "nav", "expense ratio")),
        indmoney("get_us_stocks_details", "Live US stock data", AuthTier.OPEN, listOf("us stock", "nasdaq", "nyse", "s&p", "dow jones")),
        // Gmail (D29 step 3): read-only headers and snippets, fetched and answered on the phone. Tier 1.
        gmail("gmail_unread", "Unread inbox count and the latest unread emails (sender, subject, time)",
            listOf("unread", "new email", "new emails", "new mail", "any email", "any emails", "any mail"), snapshot = true),
        gmail("gmail_recent", "Latest inbox emails (sender, subject, time)",
            listOf("my email", "my emails", "my inbox", "latest email", "recent email", "last email"), groupDefault = true, snapshot = true),
        gmail("gmail_search", "Emails matching a sender or topic", listOf("email from", "emails from", "mail from", "mails from"), itemArg = "query"),
        // Calendar (D29 step 4): the phone's own calendar provider, read-only. Tier 1.
        local(CALENDAR, ToolGroup.CALENDAR, "calendar_today", "Today's events", listOf("today's meetings", "meetings today", "on my calendar today", "my day today", "schedule today"), snapshot = true),
        local(CALENDAR, ToolGroup.CALENDAR, "calendar_upcoming", "Events in the next 7 days, next event first",
            listOf("next meeting", "my calendar", "my schedule", "upcoming", "this week", "tomorrow's meetings"), groupDefault = true, snapshot = true),
        local(CALENDAR, ToolGroup.CALENDAR, "calendar_search", "Events matching a title or place", listOf("when is my", "when's my"), itemArg = "query"),
        // Google Drive (D29 step 4): file names and dates only. Tier 1.
        local(DRIVE, ToolGroup.FILES, "drive_recent", "Recently edited Drive files", listOf("my drive", "recent files", "my files"), groupDefault = true, snapshot = true),
        local(DRIVE, ToolGroup.FILES, "drive_search", "Drive files by name", listOf("find the file", "find file", "file called", "document called", "sheet called"), itemArg = "query"),
        // Call log (D30): the phone's own call history. Tier 1.
        local(CALLS, ToolGroup.CALLS, "calls_recent", "Recent calls", listOf("recent calls", "call log", "call history", "who called"), groupDefault = true, snapshot = true),
        local(CALLS, ToolGroup.CALLS, "calls_missed", "Missed calls in the last 7 days", listOf("missed call", "missed calls"), snapshot = true),
        local(CALLS, ToolGroup.CALLS, "calls_with", "Calls with one person or number", listOf("last call with", "did i call", "did i talk to", "calls with"), itemArg = "query"),
        // Call recordings (D30): on-phone transcripts of what the dialer recorded. Tier 1.
        local(RECORDINGS, ToolGroup.CALLS, "recordings_recent", "Latest transcribed call recordings", listOf("call recordings", "recorded calls"), snapshot = true),
        local(RECORDINGS, ToolGroup.CALLS, "recordings_search", "Calls whose recording mentions something", listOf("in the call", "on the call", "said on"), itemArg = "query"),
        // Messaging (D29 step 8): SMS inbox and chat notifications, read on the phone. Tier 1.
        local(SMS, ToolGroup.MESSAGING, "sms_recent", "Latest texts (SMS)", listOf("my texts", "my sms", "new sms", "text messages"), groupDefault = true, snapshot = true),
        local(SMS, ToolGroup.MESSAGING, "sms_from", "Texts from one person or number", listOf("sms from", "texts from", "text from"), itemArg = "query"),
        local(CHATS, ToolGroup.MESSAGING, "chats_recent", "Recent WhatsApp/Telegram/chat messages", listOf("whatsapp", "telegram", "chat messages", "new messages"), snapshot = true),
        local(CHATS, ToolGroup.MESSAGING, "chats_from", "Chat messages from one person or group", listOf("messages from", "whatsapp from"), itemArg = "query"),
    )

    /** Phrases that select a group even when no single tool matched. */
    val groupKeywords: Map<ToolGroup, List<String>> = mapOf(
        // Only clearly personal phrases pick the group default (net worth). General finance questions ("what is a
        // mutual fund?") go to Claude, which can hand personal ones back to the phone (PrivateDataTool).
        ToolGroup.FINANCE to listOf(
            "indmoney", "my money", "my investments", "my investment", "my finances", "my portfolio", "sip",
            "portfolio", "net worth", "networth",
        ),
        ToolGroup.EMAIL to listOf("gmail", "my email", "my emails", "my inbox", "my mail"),
        ToolGroup.CALENDAR to listOf("my calendar", "my schedule", "my meetings", "my agenda"),
        ToolGroup.FILES to listOf("google drive", "my drive", "my files", "my documents"),
        ToolGroup.CALLS to listOf("my calls", "call log", "phone calls"),
        ToolGroup.MESSAGING to listOf("my messages", "my texts", "whatsapp", "sms"),
    )

    fun byName(name: String): ToolSpec? = all.firstOrNull { it.name == name }

    /** The connector's default whole-account read (net worth, latest emails): answers when nothing narrower can. */
    fun defaultFor(connectorId: String?): ToolSpec? = all.firstOrNull { it.connectorId == connectorId && it.groupDefault }

    private fun indmoney(
        name: String, description: String, tier: AuthTier, keywords: List<String>,
        groupDefault: Boolean = false, helper: Boolean = false, snapshot: Boolean = false,
    ) = ToolSpec(
        name = name, description = description, group = ToolGroup.FINANCE, risk = RiskLevel.READ, tier = tier,
        confirmationRequired = false, connectorId = INDMONEY, keywords = keywords,
        groupDefault = groupDefault, helper = helper, snapshot = snapshot,
    )

    /** A phone-data tool (calendar, call log, SMS…): read-only, Tier 1. */
    internal fun local(
        connector: String, group: ToolGroup, name: String, description: String, keywords: List<String>,
        groupDefault: Boolean = false, snapshot: Boolean = false, itemArg: String? = null, tier: AuthTier = AuthTier.PERSONAL,
    ) = ToolSpec(
        name = name, description = description, group = group, risk = RiskLevel.READ, tier = tier,
        confirmationRequired = false, connectorId = connector, keywords = keywords,
        groupDefault = groupDefault, snapshot = snapshot, itemArg = itemArg,
    )

    private fun gmail(
        name: String, description: String, keywords: List<String>,
        groupDefault: Boolean = false, snapshot: Boolean = false, itemArg: String? = null,
    ) = ToolSpec(
        name = name, description = description, group = ToolGroup.EMAIL, risk = RiskLevel.READ, tier = AuthTier.PERSONAL,
        confirmationRequired = false, connectorId = GMAIL, keywords = keywords,
        groupDefault = groupDefault, snapshot = snapshot, itemArg = itemArg,
    )
}
