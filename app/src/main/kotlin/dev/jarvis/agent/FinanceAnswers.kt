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

    /**
     * "Net worth is ₹12.3 lakh. Invested ₹10 lakh, up ₹2.3 lakh (23%). Biggest: Equity ₹6 lakh, Liquid ₹2 lakh."
     * The total is an explicit net worth/total field, else the sum of the asset-class rows.
     */
    private fun netWorth(root: JsonElement): String? {
        val classes = assetClasses(root)
        val total = findNumber(root, NET_WORTH_KEYS, fuzzy = false)
            ?: classes.takeIf { it.isNotEmpty() }?.sumOf { it.value }
            ?: findNumber(root, NET_WORTH_KEYS)
            ?: return null
        val invested = findNumber(root, INVESTED_KEYS)
            ?: classes.mapNotNull { it.invested }.takeIf { it.isNotEmpty() && it.size == classes.size }?.sum()
        val gain = findNumber(root, GAIN_KEYS, fuzzy = false) ?: invested?.let { totalCurrent(root, total) - it }
        return buildString {
            append("Net worth is ").append(inr(total)).append('.')
            if (invested != null && invested > 0) {
                append(" Invested ").append(inr(invested))
                if (gain != null) append(", ").append(if (gain >= 0) "up " else "down ").append(inr(abs(gain)))
                    .append(" (").append(pct(gain / invested)).append(")")
                append('.')
            }
            val biggest = classes.filter { it.value > 0 }.sortedByDescending { it.value }.take(3)
            if (biggest.size > 1) append(" Biggest: ").append(biggest.joinToString(", ") { "${it.label} ${inr(it.value)}" }).append('.')
        }
    }

    /**
     * Bank/savings money from the net worth summary: a bank/savings/cash field, or the bank-like asset classes
     * (INDmoney's "Liquid" class covers bank, savings, cash and deposits). If none is reported, says so plainly and
     * gives the net worth instead of guessing.
     */
    private fun bank(root: JsonElement): String {
        findNumber(root, BANK_KEYS, fuzzy = false)?.let { return "Bank and savings balance is ${inr(it)}." }
        val rows = assetClasses(root).filter { BANK_LABEL.containsMatchIn(it.label.lowercase(Locale.ENGLISH)) }
        if (rows.isNotEmpty()) {
            val parts = rows.joinToString(", ") { "${it.label} ${inr(it.value)}" }
            val note = if (rows.any { "liquid" in it.label.lowercase(Locale.ENGLISH) }) " (INDmoney's Liquid covers bank, savings, cash and deposits)" else ""
            return if (rows.size == 1) "${rows[0].label}: ${inr(rows[0].value)}$note."
            else "Bank and cash: ${inr(rows.sumOf { it.value })} ($parts)$note."
        }
        bankRow(root)?.let { (label, value) -> return "$label: ${inr(value)}." }
        findNumber(root, BANK_FUZZY)?.let { return "Bank and savings balance is ${inr(it)}." }
        val total = netWorth(root)
        return "INDmoney doesn't show bank or cash money separately." + (total?.let { " $it" } ?: "")
    }

    private data class AssetClass(val label: String, val value: Double, val invested: Double?)

    /** Asset-class rows (label + current value) from the summary's allocation list, if it has one. */
    private fun assetClasses(root: JsonElement): List<AssetClass> {
        val rows = largestObjectArray(root) ?: return emptyList()
        return rows.mapNotNull { o ->
            val label = findString(o, CLASS_KEYS) ?: return@mapNotNull null
            val value = findNumber(o, VALUE_KEYS, fuzzy = false) ?: findNumber(o, CLASS_VALUE_FUZZY) ?: return@mapNotNull null
            AssetClass(assetLabel(label), value, findNumber(o, INVESTED_KEYS, fuzzy = false))
        }
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
        return when {
            text.isEmpty() -> "INDmoney returned nothing."
            // A plain sentence from INDmoney is readable as it is.
            root == null || root is JsonPrimitive -> "Here's what INDmoney returned:\n" + text.take(600)
            // Never dump raw JSON at the user.
            else -> "I got your INDmoney data but couldn't read its format yet. Settings → Data on this phone → " +
                "Copy data format shows its structure (no amounts) so the reader can be fixed."
        }
    }

    // --- JSON helpers --------------------------------------------------------------------------------------

    private data class Row(val name: String, val value: Double?, val pnlPct: Double?)

    /**
     * The result as JSON: plain JSON, a JSON string holding JSON (double-encoded), or JSON wrapped in text
     * ("Here is your portfolio: {...}" or a ```json fence), in which case the outermost {...} or [...] is used.
     */
    internal fun parse(raw: String): JsonElement? {
        val text = raw.trim()
        val direct = runCatching { json.parseToJsonElement(text) }.getOrNull()?.let { e ->
            if (e is JsonPrimitive && e.isString) runCatching { json.parseToJsonElement(e.content) }.getOrNull() ?: e else e
        }?.let(::unwrap)
        if (direct != null && direct !is JsonPrimitive) return direct
        val start = text.indexOfFirst { it == '{' || it == '[' }
        val end = text.indexOfLast { it == '}' || it == ']' }
        if (start >= 0 && end > start) runCatching { json.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull()?.let { return it }
        return direct
    }

    /**
     * INDmoney wraps its data as text inside an envelope: {"result": "{\"total_networth\": ...}"}. When an object holds
     * only plain values and one of them is a JSON object/array written as text, that inner JSON is the real data.
     */
    private fun unwrap(e: JsonElement): JsonElement {
        if (e !is JsonObject || e.values.any { it !is JsonPrimitive }) return e
        val inner = e.values.firstNotNullOfOrNull { v ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.startsWith("{") || it.startsWith("[") }
                ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        }
        return if (inner != null && inner !is JsonPrimitive) inner else e
    }

    /** "US_STOCK" -> "US stocks", "EPF" -> "EPF", "mutual funds" -> "Mutual funds". */
    internal fun assetLabel(raw: String): String {
        val key = raw.trim().uppercase(Locale.ENGLISH).replace(Regex("[^A-Z0-9]+"), "_").trim('_')
        ASSET_NAMES[key]?.let { return it }
        // Already a readable name ("Global Equity"): keep it as written.
        if (raw.any { it.isLowerCase() } && '_' !in raw) return raw.trim()
        val words = raw.trim().replace('_', ' ').replace(Regex("\\s+"), " ")
        // Short all-caps codes (EPF, PPF, NPS, ETF) stay as they are.
        if (words.length <= 4 && words.all { it.isUpperCase() || it.isDigit() }) return words
        return words.lowercase(Locale.ENGLISH).replaceFirstChar { it.titlecase(Locale.ENGLISH) }
    }

    private val ASSET_NAMES = mapOf(
        "US_STOCK" to "US stocks", "US_STOCKS" to "US stocks", "IND_STOCK" to "Indian stocks", "IND_STOCKS" to "Indian stocks",
        "INDIAN_STOCK" to "Indian stocks", "INDIAN_STOCKS" to "Indian stocks", "STOCK" to "Stocks", "STOCKS" to "Stocks",
        "MF" to "Mutual funds", "MUTUAL_FUND" to "Mutual funds", "MUTUAL_FUNDS" to "Mutual funds", "FD" to "Fixed deposits",
        "FIXED_DEPOSIT" to "Fixed deposits", "LIQUID" to "Liquid", "BANK" to "Bank", "BANK_BALANCE" to "Bank balance",
        "GOLD" to "Gold", "DIGITAL_GOLD" to "Digital gold", "CRYPTO" to "Crypto", "REAL_ESTATE" to "Real estate",
        "BONDS" to "Bonds", "BOND" to "Bonds", "INSURANCE" to "Insurance", "OTHERS" to "Others", "OTHER" to "Others",
    )

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
    private val BANK_KEYS = listOf(
        "bankbalance", "savingsbalance", "savingsaccount", "bankaccounts", "bank", "savings", "cash", "cashbalance",
        "liquid", "liquidassets",
    )
    private val BANK_FUZZY = listOf("bankbalance", "savings", "bank", "liquid")
    private val BANK_LABEL = Regex("bank|savings|cash|liquid|deposit|\\bfds?\\b")
    /** The label of an asset-class row. */
    private val CLASS_KEYS = listOf(
        "assetclass", "assetclassl2", "assetclassl1", "assettype", "assetcategory", "asset", "category", "type", "class", "name", "label",
        "title", "displayname",
    )
    private val CLASS_VALUE_FUZZY = listOf("currentvalue", "value", "amount", "networth", "total")
    private const val TOP_N = 5
}

/**
 * The SHAPE of a JSON result (field names and types, never values), for the debug log, so templates can be
 * written against the real INDmoney output without logging personal data.
 */
object JsonShape {
    /**
     * [labels]: also show short string values of category-like keys (type, class, category, label, name), e.g. asset
     * class names like "Liquid". Never numbers. Used only for the net worth summary, whose rows are asset classes;
     * the user sees the text before sharing it.
     */
    fun describe(raw: String, maxDepth: Int = 4, labels: Boolean = false): String {
        val e = FinanceAnswers.parse(raw)
        // Plain text (even a single word, which parses as a literal) is reported by length only.
        if (e == null || e is JsonPrimitive) return "text(${raw.length} chars)"
        val wrapped = if (runCatching { Json.parseToJsonElement(raw.trim()) }.isFailure) "text-wrapped " else ""
        return wrapped + describe(e, maxDepth, labels)
    }

    private val LABEL_KEY = Regex("type|class|category|label|bucket|segment|name|title")

    fun describe(e: JsonElement, depth: Int, labels: Boolean = false, key: String = ""): String = when (e) {
        is JsonObject -> if (depth <= 0) "{…}" else e.entries.joinToString(",", "{", "}") { (k, v) -> k + ":" + describe(v, depth - 1, labels, k) }
        is JsonArray -> when {
            e.isEmpty() -> "[]"
            // Category lists (asset classes) show every row's label; anything else shows the first row's shape.
            labels && e.all { it is JsonObject } -> "[" + e.joinToString(" | ") { describe(it, depth - 1, labels) } + "]×" + e.size
            else -> "[" + describe(e.first(), depth - 1, labels) + "]×" + e.size
        }
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            e.isString && labels && LABEL_KEY.containsMatchIn(key.lowercase(Locale.ENGLISH)) && e.content.length <= 32 ->
                "str(\"" + e.content + "\")"
            e.isString -> "str"
            e.contentOrNull == "true" || e.contentOrNull == "false" -> "bool"
            else -> "num"
        }
    }
}
