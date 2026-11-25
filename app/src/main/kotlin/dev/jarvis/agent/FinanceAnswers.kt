package dev.jarvis.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Turns INDmoney tool results into short spoken-style answers ON THE PHONE (D29): portfolio data never goes
 * to Claude. INDmoney doesn't document its output format, so fields are found by name patterns (e.g. any
 * "net worth"/"total" number). When nothing is recognised, a plain summary of the top-level fields is shown
 * instead of guessing. Templates get refined once the real shape is seen on the device ([JsonShape]).
 */
object FinanceAnswers {
    private val json = Json { ignoreUnknownKeys = true }

    /** Words that mean the user wants bank/cash money, not the whole net worth. */
    private val BANK_QUESTION = Regex("\\b(bank|savings?|cash|balance|deposits?|fd|fds)\\b")

    fun isBankQuestion(question: String) = BANK_QUESTION.containsMatchIn(question.lowercase(Locale.ENGLISH))

    fun render(tool: String, raw: String, question: String = "", item: String? = null): String {
        val root = parse(raw)
        val answer = when (tool) {
            "networth_snapshot" -> root?.let { if (isBankQuestion(question)) bank(it) else netWorth(it) }
            "networth_holdings" -> root?.let { holdings(it, item) }
            "mf_sips" -> root?.let { sips(it, "mutual fund SIP") }
            "indian_stocks_sips" -> root?.let { sips(it, "stock SIP") }
            "user_watchlist" -> root?.let(::watchlist)
            else -> null
        }
        return answer ?: fallback(root, raw)
    }

    // --- Templates ---------------------------------------------------------------------------------------

    private fun netWorth(root: JsonElement): String? {
        val total = findNumber(root, NET_WORTH_KEYS) ?: return null
        val invested = findNumber(root, INVESTED_KEYS)
        val gain = findNumber(root, GAIN_KEYS) ?: invested?.let { totalCurrent(root, total) - it }
        return buildString {
            append("Net worth is ").append(inr(total)).append('.')
            if (invested != null && invested > 0) {
                append(" Invested ").append(inr(invested))
                if (gain != null) append(", ").append(if (gain >= 0) "up " else "down ").append(inr(abs(gain)))
                    .append(" (").append(pct(gain / invested)).append(")")
                append('.')
            }
        }
    }

    /**
     * Bank/savings money from the net worth summary. Looks for a bank/savings/cash field, or a bank/cash row in an
     * allocation list. If INDmoney doesn't report one, says so plainly and gives the net worth instead of guessing.
     */
    private fun bank(root: JsonElement): String {
        findNumber(root, BANK_KEYS, fuzzy = false)?.let { return "Bank and savings balance is ${inr(it)}." }
        bankRow(root)?.let { (label, value) -> return "$label: ${inr(value)}." }
        findNumber(root, BANK_FUZZY)?.let { return "Bank and savings balance is ${inr(it)}." }
        val total = netWorth(root)
        return "INDmoney doesn't show a separate bank balance." + (total?.let { " $it" } ?: "")
    }

    /** A row like {"asset_type":"Bank Balance","value":52000} in any list. */
    private fun bankRow(root: JsonElement): Pair<String, Double>? {
        var found: Pair<String, Double>? = null
        fun visit(e: JsonElement) {
            if (found != null) return
            when (e) {
                is JsonArray -> e.forEach { item ->
                    val o = item as? JsonObject
                    if (o != null && found == null) {
                        val label = o.values.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                            .firstOrNull { BANK_LABEL.containsMatchIn(it.lowercase(Locale.ENGLISH)) }
                        val value = label?.let { findNumber(o, VALUE_KEYS) }
                        if (label != null && value != null) found = label.trim() to value
                    }
                    visit(item)
                }
                is JsonObject -> e.values.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return found
    }

    /** Current value of investments when present (net worth may include cash/liabilities). */
    private fun totalCurrent(root: JsonElement, total: Double) = findNumber(root, CURRENT_KEYS) ?: total

    private fun holdings(root: JsonElement, item: String? = null): String? {
        val items = largestObjectArray(root) ?: return null
        val rows = items.mapNotNull { o ->
            val name = findString(o, NAME_KEYS) ?: return@mapNotNull null
            Row(name, findNumber(o, VALUE_KEYS), findNumber(o, PNL_PCT_KEYS, fuzzy = false))
        }
        if (rows.isEmpty()) return null
        if (item != null) {
            val words = norm(item)
            val match = rows.filter { norm(it.name).contains(words) || words.contains(norm(it.name)) }
            if (match.isEmpty()) return "I don't see $item in your INDmoney holdings."
            return match.take(TOP_N).joinToString("\n") { r ->
                buildString {
                    append(r.name)
                    r.value?.let { append(": ").append(inr(it)) }
                    r.pnlPct?.let { append(" (").append(signedPct(it)).append(")") }
                    append('.')
                }
            }
        }
        val top = rows.sortedByDescending { it.value ?: 0.0 }.take(TOP_N)
        return buildString {
            append("You hold ").append(rows.size).append(if (rows.size == 1) " position." else " positions.")
            append(if (rows.size > TOP_N) " Largest:" else "")
            top.forEach { r ->
                append("\n• ").append(r.name)
                r.value?.let { append(", ").append(inr(it)) }
                r.pnlPct?.let { append(" (").append(signedPct(it)).append(")") }
            }
        }
    }

    private fun sips(root: JsonElement, label: String): String? {
        val items = largestObjectArray(root) ?: return if (isEmptyList(root)) "No active ${label}s." else null
        val rows = items.mapNotNull { o ->
            val name = findString(o, NAME_KEYS) ?: return@mapNotNull null
            Triple(name, findNumber(o, SIP_AMOUNT_KEYS), findString(o, DATE_KEYS))
        }
        if (rows.isEmpty()) return null
        val monthly = rows.mapNotNull { it.second }.takeIf { it.size == rows.size }?.sum()
        return buildString {
            append(rows.size).append(" active ").append(label).append(if (rows.size == 1) "" else "s")
            monthly?.let { append(", ").append(inr(it)).append(" in total") }
            append('.')
            rows.take(TOP_N).forEach { (name, amount, date) ->
                append("\n• ").append(name)
                amount?.let { append(", ").append(inr(it)) }
                date?.let { append(", next ").append(it) }
            }
        }
    }

    private fun watchlist(root: JsonElement): String? {
        val items = largestObjectArray(root) ?: return if (isEmptyList(root)) "Your watchlist is empty." else null
        val names = items.mapNotNull { findString(it, NAME_KEYS) }
        if (names.isEmpty()) return null
        return "Your watchlist has ${names.size}: " + names.take(8).joinToString(", ") + if (names.size > 8) ", and more." else "."
    }

    /** Readable top-level fields when no template matched. Never guesses meaning. */
    internal fun fallback(root: JsonElement?, raw: String): String {
        val obj = root as? JsonObject
        if (obj != null) {
            val lines = obj.entries.mapNotNull { (k, v) ->
                val p = v as? JsonPrimitive ?: return@mapNotNull null
                if (p is JsonNull) return@mapNotNull null
                val value = p.contentOrNull?.toDoubleOrNull()?.let(::plainNumber) ?: p.contentOrNull ?: return@mapNotNull null
                "• " + label(k) + ": " + value
            }.take(8)
            if (lines.isNotEmpty()) return "Here's what INDmoney returned:\n" + lines.joinToString("\n")
        }
        val text = raw.trim()
        return if (text.isEmpty()) "INDmoney returned nothing." else "Here's what INDmoney returned:\n" + text.take(600)
    }

    // --- JSON helpers --------------------------------------------------------------------------------------

    private data class Row(val name: String, val value: Double?, val pnlPct: Double?)

    private fun parse(raw: String): JsonElement? = runCatching { json.parseToJsonElement(raw.trim()) }.getOrNull()
        ?.let { e -> // a JSON string holding JSON (some servers double-encode)
            if (e is JsonPrimitive && e.isString) runCatching { json.parseToJsonElement(e.content) }.getOrNull() ?: e else e
        }

    internal fun norm(key: String) = key.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]"), "")

    /** First number (breadth-first, so top-level fields win) whose key matches the earliest pattern. */
    internal fun findNumber(root: JsonElement, keys: List<String>, fuzzy: Boolean = true): Double? {
        for (k in keys) {
            breadthFirst(root) { key, v -> if (norm(key) == k) number(v) else null }?.let { return it }
        }
        if (!fuzzy) return null
        for (k in keys) {
            breadthFirst(root) { key, v -> if (norm(key).contains(k)) number(v) else null }?.let { return it }
        }
        return null
    }

    private fun findString(root: JsonElement, keys: List<String>): String? {
        for (k in keys) {
            breadthFirst(root) { key, v ->
                if (norm(key) == k) (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } else null
            }?.let { return it }
        }
        return null
    }

    private fun <T> breadthFirst(root: JsonElement, match: (String, JsonElement) -> T?): T? {
        val queue = ArrayDeque<JsonElement>().apply { add(root) }
        while (queue.isNotEmpty()) {
            when (val e = queue.removeFirst()) {
                is JsonObject -> {
                    for ((k, v) in e) match(k, v)?.let { return it }
                    e.values.forEach { if (it is JsonObject) queue.add(it) }
                }
                is JsonArray -> Unit // lists are handled by the list templates
                else -> Unit
            }
        }
        return null
    }

    private fun number(v: JsonElement): Double? = when (v) {
        is JsonPrimitive -> v.contentOrNull?.replace(Regex("[₹,\\s]|INR|Rs\\.?"), "")?.toDoubleOrNull()
        is JsonObject -> findNumber(v, listOf("value", "amount", "inr"))
        else -> null
    }

    /** The biggest array of objects anywhere in the result (the holdings/SIP list). */
    private fun largestObjectArray(root: JsonElement): List<JsonObject>? {
        var best: List<JsonObject>? = null
        fun visit(e: JsonElement) {
            when (e) {
                is JsonArray -> {
                    val objs = e.filterIsInstance<JsonObject>()
                    if (objs.isNotEmpty() && objs.size > (best?.size ?: 0)) best = objs
                    e.forEach(::visit)
                }
                is JsonObject -> e.values.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return best
    }

    private fun isEmptyList(root: JsonElement): Boolean =
        root is JsonArray && root.isEmpty() || root is JsonObject && root.values.any { it is JsonArray && it.isEmpty() } &&
            root.values.none { it is JsonArray && it.isNotEmpty() }

    // --- Formatting ---------------------------------------------------------------------------------------

    /** ₹ amounts in Indian units: "₹12.4 lakh", "₹1.25 crore", "₹45,300". */
    fun inr(amount: Double): String {
        val sign = if (amount < 0) "-" else ""
        val a = abs(amount)
        return sign + when {
            a >= 1e7 -> "₹" + trim(String.format(Locale.ENGLISH, "%.2f", a / 1e7)) + " crore"
            a >= 1e5 -> "₹" + trim(String.format(Locale.ENGLISH, "%.1f", a / 1e5)) + " lakh"
            else -> "₹" + indianGrouping(a.roundToLong())
        }
    }

    private fun indianGrouping(n: Long): String {
        val s = n.toString()
        if (s.length <= 3) return s
        val last3 = s.takeLast(3)
        val rest = s.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        return "$rest,$last3"
    }

    /** A ratio (0.123) as "12.3%". */
    private fun pct(ratio: Double) = trim(String.format(Locale.ENGLISH, "%.1f", ratio * 100)) + "%"

    /** P&L fields are taken as percentages (12.3 = 12.3%); guessing ratios would turn 0.8% into 80%. */
    private fun signedPct(p: Double): String =
        (if (p >= 0) "+" else "-") + trim(String.format(Locale.ENGLISH, "%.1f", abs(p))) + "%"

    private fun plainNumber(d: Double) =
        if (abs(d) >= 1000) inr(d) else trim(String.format(Locale.ENGLISH, "%.2f", d))

    private fun trim(s: String) = if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s

    private fun label(key: String) = key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ')
        .lowercase(Locale.ENGLISH).replaceFirstChar { it.titlecase(Locale.ENGLISH) }

    // Normalised key patterns, most specific first.
    private val NET_WORTH_KEYS = listOf("networth", "totalnetworth", "networthvalue", "total", "totalvalue")
    private val INVESTED_KEYS = listOf("invested", "investedvalue", "investedamount", "totalinvested", "investment")
    private val CURRENT_KEYS = listOf("currentvalue", "current", "marketvalue")
    private val GAIN_KEYS = listOf("totalreturns", "returns", "gain", "totalgain", "pnl", "profitloss", "unrealisedpnl", "unrealizedpnl")
    private val NAME_KEYS = listOf("name", "schemename", "fundname", "instrumentname", "stockname", "companyname", "title", "symbol", "ticker")
    private val VALUE_KEYS = listOf("currentvalue", "marketvalue", "value", "current", "amount")
    private val PNL_PCT_KEYS = listOf("pnlpercent", "pnlpct", "returnspercent", "returnpercent", "gainpercent", "xirr", "returns")
    private val SIP_AMOUNT_KEYS = listOf("sipamount", "amount", "installmentamount", "monthlyamount", "value")
    private val DATE_KEYS = listOf("nextsipdate", "nextinstallmentdate", "nextdate", "nextduedate", "sipdate", "date")
    private val BANK_KEYS = listOf("bankbalance", "savingsbalance", "savingsaccount", "bankaccounts", "bank", "savings", "cash", "cashbalance")
    private val BANK_FUZZY = listOf("bankbalance", "savings", "bank")
    private val BANK_LABEL = Regex("bank|savings|cash")
    private const val TOP_N = 5
}

/**
 * The SHAPE of a JSON result (field names and types, never values), for the debug log, so templates can be
 * written against the real INDmoney output without logging personal data.
 */
object JsonShape {
    fun describe(raw: String, maxDepth: Int = 4): String {
        val e = runCatching { Json.parseToJsonElement(raw.trim()) }.getOrNull()
        // Plain text (even a single word, which parses as a literal) is reported by length only.
        if (e == null || e is JsonPrimitive) return "text(${raw.length} chars)"
        return describe(e, maxDepth)
    }

    fun describe(e: JsonElement, depth: Int): String = when (e) {
        is JsonObject -> if (depth <= 0) "{…}" else e.entries.joinToString(",", "{", "}") { (k, v) -> k + ":" + describe(v, depth - 1) }
        is JsonArray -> if (e.isEmpty()) "[]" else "[" + describe(e.first(), depth - 1) + "]×" + e.size
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            e.isString -> "str"
            e.contentOrNull == "true" || e.contentOrNull == "false" -> "bool"
            else -> "num"
        }
    }
}
