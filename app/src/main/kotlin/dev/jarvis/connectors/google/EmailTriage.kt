package dev.jarvis.connectors.google

/**
 * On-phone email triage (no LLM, nothing leaves the phone): sorts each email into a kind using Gmail's own signals
 * (Important/Starred labels, Social/Forums/Updates tabs, the List-Unsubscribe header bulk mail carries) plus
 * keyword and sender rules. Bills and statements also get an amount and due date pulled from the text.
 */
object EmailTriage {
    /** Kinds in display order; [rank] lower = more important. */
    enum class Kind(val rank: Int, val label: String?) {
        SECURITY(0, "Security"),
        BILL(1, "Bill"),
        GOVERNMENT(2, "Govt"),
        PERSON(3, null),
        JOB(4, "Job"),
        BANK(5, "Bank"),
        TRAVEL(6, "Travel"),
        ORDER(7, "Order"),
        OTHER(8, null),
        NOISE(9, null),
        ;
        val important: Boolean get() = this != OTHER && this != NOISE
    }

    /** Whole-word phrases (text is lower-cased before matching). */
    private fun words(vararg w: String) = w.map { Regex("\\b" + Regex.escape(it) + "\\b") }

    private val SECURITY = words(
        "new sign-in", "new sign in", "new login", "security alert", "password changed", "password was changed",
        "unusual activity", "suspicious", "account locked", "recovery email", "new device",
    )
    private val BILL = words(
        "statement", "amount due", "total due", "minimum due", "payment due", "due date", "bill", "e-bill", "invoice",
        "premium due", "renewal due", "emi due", "overdue", "pay by",
    )
    private val BANK = words(
        "debited", "credited", "transaction", "salary", "refund", "payment received", "payment successful",
        "account balance", "emi", "upi", "neft", "imps", "credit card", "debit card", "loan", "fixed deposit",
        "mutual fund", "sip", "dividend", "demat", "contract note",
    )
    private val GOVERNMENT_TEXT = words(
        "income tax", "itr", "aadhaar", "aadhar", "pan card", "epfo", "uan", "passport", "gst", "tds", "form 16",
        "digilocker", "traffic challan", "e-challan", "voter id", "driving licence", "driving license",
    )
    private val GOVERNMENT_DOMAINS = listOf(".gov.in", ".nic.in", "incometax", "uidai", "epfindia", "passportindia", "gst.gov", ".gov>", ".gov.uk", ".gov ")
    private val JOB = words(
        "interview", "your application", "application received", "application status", "shortlisted", "offer letter",
        "job offer", "recruiter", "hiring", "assessment", "candidate", "position", "role at", "onboarding",
        "background verification", "joining date",
    )
    private val JOB_DOMAINS = listOf("greenhouse", "lever.co", "myworkday", "workday", "smartrecruiters", "icims", "naukri", "instahire", "ashbyhq")
    private val TRAVEL = words(
        "pnr", "boarding pass", "flight", "e-ticket", "booking confirmed", "hotel booking", "check-in", "itinerary",
        "train", "irctc", "cab", "trip",
    )
    private val ORDER = words("order", "shipped", "out for delivery", "delivered", "dispatched", "return", "tracking")
    private val NOISE_TEXT = words(
        "newsletter", "digest", "weekly", "webinar", "sale", "discount", "% off", "offer ends", "deal", "coupon",
        "recommended for you", "people you may know", "trending", "new followers", "liked your", "commented on",
        "mentioned you", "reacted", "jobs for you", "job alert", "jobs you may", "unsubscribe",
    )
    private val SOCIAL_DOMAINS = listOf(
        "facebookmail", "instagram", "linkedin.com", "twitter", "x.com", "quora", "medium.com", "pinterest",
        "reddit", "youtube", "snapchat", "tiktok", "substack",
    )
    private val NOREPLY = Regex("no-?reply|donotreply|do-not-reply|notifications?@|alerts?@|mailer|news@|info@|marketing")

    fun classify(e: EmailHeader): Kind {
        val text = (e.subject + " " + e.snippet).lowercase()
        val from = e.from.lowercase()
        fun has(patterns: List<Regex>) = patterns.any { it.containsMatchIn(text) }
        val labels = e.labels.toSet()
        val socialTab = "CATEGORY_SOCIAL" in labels || "CATEGORY_FORUMS" in labels || "CATEGORY_PROMOTIONS" in labels
        val socialSender = SOCIAL_DOMAINS.any { from.contains(it) }
        val noisy = has(NOISE_TEXT)

        return when {
            has(SECURITY) && !socialTab -> Kind.SECURITY
            (GOVERNMENT_DOMAINS.any { from.contains(it) } || has(GOVERNMENT_TEXT)) && !socialSender -> Kind.GOVERNMENT
            has(BILL) && !noisy && !socialSender -> Kind.BILL
            (JOB_DOMAINS.any { from.contains(it) } || has(JOB)) && !noisy && !socialTab -> Kind.JOB
            has(BANK) && !noisy && !socialSender -> Kind.BANK
            socialTab || socialSender || (e.bulk && noisy) -> Kind.NOISE
            has(TRAVEL) && !noisy -> Kind.TRAVEL
            has(ORDER) && !noisy && (!e.bulk || "CATEGORY_UPDATES" in labels) -> Kind.ORDER
            !e.bulk && !NOREPLY.containsMatchIn(from) -> Kind.PERSON
            "IMPORTANT" in labels || "STARRED" in labels -> Kind.OTHER
            e.bulk || noisy -> Kind.NOISE
            else -> Kind.OTHER
        }
    }

    /** Most important first, then newest. Gmail's Important/Starred labels break ties within a kind. */
    fun sort(emails: List<EmailHeader>): List<EmailHeader> = emails.sortedWith(
        compareBy<EmailHeader> { kindOf(it).rank }
            .thenBy { if ("STARRED" in it.labels || "IMPORTANT" in it.labels) 0 else 1 }
            .thenByDescending { it.at },
    )

    fun kindOf(e: EmailHeader): Kind = e.kind?.let { k -> Kind.entries.firstOrNull { it.name == k } } ?: classify(e)

    // ---- Amount and due date (bills and statements) ----

    /** "₹1,234.50", "Rs. 1,234", "INR 1234" (word-bounded, so "hours 5" isn't "rs 5"). */
    private val AMOUNT = Regex("(?:₹|\\brs\\.?|\\binr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
    private val AMOUNT_CUES = Regex(
        "(total amount due|amount due|total due|outstanding|amount payable|bill amount|statement balance|premium)",
        RegexOption.IGNORE_CASE,
    )
    private const val MONTH = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*"
    private val DATE = Regex(
        "(\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}|\\d{1,2}(?:st|nd|rd|th)?[ -]$MONTH[ ,-]*\\d{0,4}|$MONTH \\d{1,2}(?:st|nd|rd|th)?,? ?\\d{0,4})",
        RegexOption.IGNORE_CASE,
    )
    private val DUE_CUES = Regex("(payment due date|due date|due on|due by|pay by|payable by|last date)", RegexOption.IGNORE_CASE)

    /** The amount due: the first amount after a "due/outstanding" cue, else the first amount in the text. */
    fun extractAmount(text: String): String? {
        val cue = AMOUNT_CUES.find(text)
        val m = (cue?.let { AMOUNT.find(text, it.range.last) } ?: AMOUNT.find(text)) ?: return null
        val number = m.groupValues[1].trimEnd(',', '.')
        if (number.replace(",", "").toDoubleOrNull()?.let { it > 0 } != true) return null
        return "₹$number"
    }

    /** The due date as written in the email (within 60 characters after a due-date cue). */
    fun extractDueDate(text: String): String? {
        val cue = DUE_CUES.find(text) ?: return null
        val window = text.substring(cue.range.last + 1, minOf(text.length, cue.range.last + 61))
        return DATE.find(window)?.value?.trim()?.trimEnd(',', '-')
    }
}
