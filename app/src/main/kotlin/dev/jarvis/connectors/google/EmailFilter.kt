package dev.jarvis.connectors.google

/**
 * Privacy filter for email (D29), applied on the phone BEFORE anything is cached or shown: one-time codes and
 * two-factor mails are always dropped, and so are senders on the user's exclusion list (banks by default).
 * Promotions are excluded in the Gmail query itself ([PROMOTIONS_QUERY]).
 */
object EmailFilter {
    const val PROMOTIONS_QUERY = "-category:promotions"

    /** Default excluded senders: Indian and global banks and card issuers. The user can edit this in Settings. */
    val DEFAULT_EXCLUDED_SENDERS = listOf(
        "hdfcbank", "icicibank", "sbi.co.in", "onlinesbi", "axisbank", "kotak", "yesbank", "indusind",
        "idfcfirstbank", "pnbindia", "pnb.co.in", "bankofbaroda", "aubank", "rblbank", "federalbank", "hsbc", "citibank",
        "citi.com", "@sc.com",
        "americanexpress", "amex",
    )

    private val CODE_PATTERNS = listOf(
        Regex("\\botp\\b"), Regex("one[- ]time (password|passcode|code|pin)"), Regex("verification code"),
        Regex("security code"), Regex("login code"), Regex("sign[- ]in code"), Regex("\\b2fa\\b"),
        Regex("two[- ](factor|step)"), Regex("passcode"), Regex("confirmation code"),
    )

    fun isOneTimeCode(e: EmailHeader): Boolean {
        val text = (e.subject + " " + e.snippet).lowercase()
        return CODE_PATTERNS.any { it.containsMatchIn(text) }
    }

    fun isExcludedSender(e: EmailHeader, excluded: Collection<String>): Boolean {
        val from = e.from.lowercase()
        return excluded.any { token -> token.isNotBlank() && from.contains(token.trim().lowercase()) }
    }

    fun apply(emails: List<EmailHeader>, excludedSenders: Collection<String>): List<EmailHeader> =
        emails.filterNot { isOneTimeCode(it) || isExcludedSender(it, excludedSenders) }

    /** Parses the Settings text (comma or newline separated) into sender tokens. */
    fun parseSenders(text: String): List<String> =
        text.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
}
