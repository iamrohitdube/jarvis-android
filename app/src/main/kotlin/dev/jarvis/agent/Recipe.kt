package dev.jarvis.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Recipe answers (D32). Claude never sees the user's values: it gets the question and an OUTLINE of the data
 * ([DataOutline]) and writes the reply with `{{...}}` placeholders. The phone fills them in here ([Recipe.fill]).
 * Any number or currency Claude writes itself, or any placeholder that doesn't resolve, rejects the whole recipe,
 * and the caller falls back to the fixed templates. An invented figure can never reach the user.
 *
 * Placeholders:
 *  - `{{val PATH}}` one value; `{{sum PATH}}`, `{{count PATH}}`, `{{min PATH}}`, `{{max PATH}}`, `{{avg PATH}}`
 *  - `{{top LIST by FIELD show FIELD}}`, `{{bottom LIST by FIELD show FIELD}}`: label of the highest/lowest row
 *  - `{{pct PATH of PATH}}`: the first as a percentage of the second
 *  - `{{list LIST show F1,F2 [by FIELD] [asc|desc] [limit N]}}`: a short list of rows
 *  - optional format at the end: `|inr`, `|pct`, `|spct` (signed %), `|num`, `|date`, `|text`
 * PATH: dotted field names; rows are filtered with `[field=value]` (also `!=`, `>`, `<`, `>=`, `<=`, `~` for
 * contains, `&` to combine). Lists are walked automatically. `$item` stands for the item Claude handed back.
 */
object Recipe {
    class RecipeException(message: String) : Exception(message)

    private val PLACEHOLDER = Regex("\\{\\{(.*?)\\}\\}", RegexOption.DOT_MATCHES_ALL)
    /** Figures Claude must never write itself: it doesn't know them. */
    private val INVENTED = Regex("[0-9]{2,}|[0-9][.,][0-9]|₹|\\brs\\.?\\s*[0-9]|\\binr\\b|\\blakhs?\\b|\\bcrores?\\b|%", RegexOption.IGNORE_CASE)
    const val MAX_CHARS = 700

    fun fill(template: String, data: JsonElement, item: String? = null): String {
        val t = template.trim()
        if (t.isEmpty() || t.length > MAX_CHARS) throw RecipeException("empty or too long")
        val outside = PLACEHOLDER.replace(t, " ")
        if (INVENTED.containsMatchIn(outside)) throw RecipeException("figure outside a placeholder")
        if ('{' in outside || '}' in outside) throw RecipeException("broken placeholder")
        return PLACEHOLDER.replace(t) { m -> evaluate(m.groupValues[1].trim(), data, item) }
            .replace(Regex("[ \\t]+"), " ").trim()
    }

    // ---- Expressions --------------------------------------------------------------------------------------

    internal fun evaluate(expr: String, root: JsonElement, item: String?): String {
        val (body, fmt) = expr.substringBeforeLast('|', expr).trim() to
            expr.substringAfterLast('|', "").trim().lowercase(Locale.ENGLISH).ifEmpty { null }
        val tokens = tokenize(body)
        if (tokens.isEmpty()) throw RecipeException("empty placeholder")
        val args = tokens.drop(1)
        return when (val fn = tokens[0].lowercase(Locale.ENGLISH)) {
            "val" -> {
                val path = args.one()
                val v = resolve(path, root, item).firstOrNull { it !is JsonNull } ?: throw RecipeException("no value at $path")
                format(v, fmt ?: inferFormat(lastField(path), v))
            }
            "sum", "min", "max", "avg" -> {
                val path = args.one()
                val nums = resolve(path, root, item).mapNotNull(::number)
                val v = when (fn) {
                    "sum" -> nums.sum()
                    "min" -> nums.minOrNull()
                    "max" -> nums.maxOrNull()
                    else -> nums.takeIf { it.isNotEmpty() }?.average()
                } ?: throw RecipeException("no numbers at $path")
                formatNumber(v, fmt ?: inferFormat(lastField(path), JsonPrimitive(v)))
            }
            "count" -> resolve(args.one(), root, item).count { it !is JsonNull }.toString()
            "pct" -> {
                ensure(args.size == 3 && args[1].equals("of", true), "pct A of B")
                val a = resolve(args[0], root, item).mapNotNull(::number).sum()
                val b = resolve(args[2], root, item).mapNotNull(::number).sum()
                if (b == 0.0) throw RecipeException("division by zero")
                formatNumber(a / b * 100, fmt ?: "pct")
            }
            "top", "bottom" -> {
                val list = args.getOrNull(0) ?: throw RecipeException("$fn needs a list")
                val by = keyword(args, "by") ?: throw RecipeException("$fn needs 'by'")
                val show = keyword(args, "show") ?: throw RecipeException("$fn needs 'show'")
                val rows = rows(list, root, item).filter { r -> number(r[by] ?: JsonNull) != null }
                val row = (if (fn == "top") rows.maxByOrNull { number(it[by]!!)!! } else rows.minByOrNull { number(it[by]!!)!! })
                    ?: throw RecipeException("no rows in $list")
                val v = row[show] ?: throw RecipeException("no $show")
                format(v, fmt ?: inferFormat(show, v))
            }
            "list" -> {
                val list = args.getOrNull(0) ?: throw RecipeException("list needs a list")
                val show = keyword(args, "show")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: throw RecipeException("list needs 'show'")
                val by = keyword(args, "by")
                val limit = keyword(args, "limit")?.toIntOrNull()?.coerceIn(1, 10) ?: 5
                var rows = rows(list, root, item)
                if (by != null) {
                    rows = rows.sortedBy { number(it[by] ?: JsonNull) ?: Double.NEGATIVE_INFINITY }
                    if (!args.any { it.equals("asc", true) }) rows = rows.reversed()
                }
                if (rows.isEmpty()) return "none"
                rows.take(limit).joinToString("; ") { r ->
                    val cells = show.mapNotNull { f -> r[f]?.takeIf { it !is JsonNull }?.let { format(it, inferFormat(f, it)) } }
                    if (cells.size <= 1) cells.joinToString() else cells.first() + " " + cells.drop(1).joinToString(", ")
                }
            }
            else -> throw RecipeException("unknown function $fn")
        }
    }

    private fun <T> List<T>.one(): T = if (size == 1) first() else throw RecipeException("expected one argument")
    private fun ensure(ok: Boolean, msg: String) { if (!ok) throw RecipeException(msg) }

    /** The token after [word] ("by", "show", "limit"). */
    private fun keyword(args: List<String>, word: String): String? =
        args.indexOfFirst { it.equals(word, true) }.takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    /** Splits on spaces outside [...] so filters like [market_cap=Large Cap] stay whole. */
    internal fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        for (c in s) {
            when {
                c == '[' -> { depth++; cur.append(c) }
                c == ']' -> { depth--; cur.append(c) }
                c.isWhitespace() && depth == 0 -> { if (cur.isNotEmpty()) out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    // ---- Paths --------------------------------------------------------------------------------------------

    private data class Segment(val name: String, val filters: List<Filter>)
    private data class Filter(val field: String, val op: String, val value: String)

    private val FILTER = Regex("^\\s*([A-Za-z0-9_]+)\\s*(>=|<=|!=|=|>|<|~)\\s*(.*?)\\s*$")

    private fun segments(path: String): List<Segment> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        for (c in path) {
            when {
                c == '[' -> { depth++; cur.append(c) }
                c == ']' -> { depth--; cur.append(c) }
                c == '.' && depth == 0 -> { parts += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        parts += cur.toString()
        return parts.map { p ->
            val name = p.substringBefore('[').trim()
            val filters = Regex("\\[(.*?)]").findAll(p).flatMap { m ->
                m.groupValues[1].split('&').map { f ->
                    val fm = FILTER.find(f) ?: throw RecipeException("bad filter [$f]")
                    Filter(fm.groupValues[1], fm.groupValues[2], fm.groupValues[3])
                }
            }.toList()
            if (name.isEmpty() && filters.isEmpty()) throw RecipeException("empty path segment in $path")
            Segment(name, filters)
        }
    }

    /** Every element the path reaches; lists along the way are walked. */
    internal fun resolve(path: String, root: JsonElement, item: String?): List<JsonElement> {
        var current: List<JsonElement> = listOf(root)
        for (seg in segments(path)) {
            val next = mutableListOf<JsonElement>()
            for (e in current) {
                val v = if (seg.name.isEmpty()) e else (e as? JsonObject)?.get(seg.name) ?: continue
                if (v is JsonArray) next.addAll(v) else next.add(v)
            }
            current = if (seg.filters.isEmpty()) next else next.filter { e -> seg.filters.all { matches(e, it, item) } }
        }
        return current
    }

    private fun rows(path: String, root: JsonElement, item: String?): List<JsonObject> =
        resolve(path, root, item).filterIsInstance<JsonObject>()

    private fun matches(e: JsonElement, f: Filter, item: String?): Boolean {
        val obj = e as? JsonObject ?: return false
        val field = obj[f.field] ?: return false
        val want = if (f.value == "\$item") item ?: return false else f.value.trim('"', '\'')
        val n = number(field)
        val w = want.toDoubleOrNull()
        if (n != null && w != null) return when (f.op) {
            "=" -> n == w; "!=" -> n != w; ">" -> n > w; "<" -> n < w; ">=" -> n >= w; "<=" -> n <= w
            else -> false
        }
        val have = (field as? JsonPrimitive)?.contentOrNull ?: return false
        return when (f.op) {
            "=" -> norm(have) == norm(want) || norm(FinanceAnswers.assetLabel(have)) == norm(want)
            "!=" -> norm(have) != norm(want)
            "~" -> norm(have).contains(norm(want)) || norm(want).contains(norm(have))
            else -> false
        }
    }

    private fun norm(s: String) = s.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]"), "")

    private fun lastField(path: String) = segments(path).last().name

    // ---- Formatting ---------------------------------------------------------------------------------------

    private fun number(e: JsonElement): Double? =
        (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.replace(Regex("[₹,\\s]"), "")?.toDoubleOrNull()

    private val MONEY = Regex("value|invested|networth|net_worth|total|due|balance|amount|return|price|liabilit|loan|cost|pnl|gain|nav|outstanding|limit|premium|salary")
    private val CATEGORY = Regex("asset_type|assetclass|asset_class|category|type|class|segment")

    internal fun inferFormat(field: String, v: JsonElement): String {
        val f = field.lowercase(Locale.ENGLISH)
        if (v is JsonPrimitive && v.isString && number(v) == null) {
            return when {
                "date" in f || Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(v.content) -> "date"
                CATEGORY.containsMatchIn(f) -> "label"
                else -> "text"
            }
        }
        return when {
            "percent" in f || "pct" in f -> if (Regex("return|gain|pnl|change|xirr").containsMatchIn(f)) "spct" else "pct"
            "date" in f -> "date"
            "count" in f || "units" in f || "quantity" in f -> "num"
            MONEY.containsMatchIn(f) -> "inr"
            else -> "num"
        }
    }

    private fun format(v: JsonElement, fmt: String): String {
        val p = v as? JsonPrimitive ?: throw RecipeException("not a plain value")
        return when (fmt) {
            "inr", "pct", "spct", "num" -> formatNumber(number(p) ?: throw RecipeException("not a number"), fmt)
            "date" -> date(p.content)
            "label" -> FinanceAnswers.assetLabel(p.content)
            else -> p.content
        }
    }

    private fun formatNumber(v: Double, fmt: String): String = when (fmt) {
        "inr" -> FinanceAnswers.inr(v)
        "pct" -> trim(String.format(Locale.ENGLISH, "%.1f", v)) + "%"
        "spct" -> (if (v > 0) "+" else if (v < 0) "-" else "") + trim(String.format(Locale.ENGLISH, "%.1f", abs(v))) + "%"
        "date" -> trim(v.toString())
        else -> if (v == Math.floor(v) && abs(v) < 1e15) v.toLong().toString() else trim(String.format(Locale.ENGLISH, "%.2f", v))
    }

    private fun date(s: String): String = runCatching {
        LocalDate.parse(s.take(10)).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    }.getOrDefault(s)

    private fun trim(s: String) = if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

/**
 * The structure of the user's data for Claude (D32): field names, types and row counts, plus labels of category
 * fields (asset types, asset classes, market caps) and the service's own notes. Never a number, a date, a name or
 * any other free text: those are shown only as their type.
 */
object DataOutline {
    /** Fields whose short text values are generic categories, safe to show (e.g. "Gold", "Large Cap", "US_STOCK"). */
    private val SAFE_LABEL_KEYS = Regex("^(asset_type|assettype|assetclass(_l\\d)?|asset_class|category|type|class|segment|market_cap|sector|bucket)$")
    private val SAFE_LABEL_VALUE = Regex("^[A-Za-z][A-Za-z &/_-]{0,31}$")
    private val NOTE_KEYS = Regex("note|disclaimer|info", RegexOption.IGNORE_CASE)
    private val DATE_VALUE = Regex("^\\d{4}-\\d{2}-\\d{2}")

    fun of(root: JsonElement, maxDepth: Int = 5): String = buildString { describe(root, "", 0, maxDepth, inNotes = false) }

    private fun StringBuilder.describe(e: JsonElement, indent: String, depth: Int, maxDepth: Int, inNotes: Boolean) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                val notes = inNotes || NOTE_KEYS.containsMatchIn(k)
                when (v) {
                    is JsonObject -> {
                        append(indent).append(k).append(":\n")
                        if (depth < maxDepth) describe(v, "$indent  ", depth + 1, maxDepth, notes)
                    }
                    is JsonArray -> describeList(k, v, indent, depth, maxDepth)
                    is JsonPrimitive -> append(indent).append(k).append(": ").append(primitive(k, v, notes)).append('\n')
                }
            }
            is JsonArray -> describeList("(list)", e, indent, depth, maxDepth)
            is JsonPrimitive -> append(indent).append(primitive("", e, inNotes)).append('\n')
        }
    }

    private fun StringBuilder.describeList(k: String, list: JsonArray, indent: String, depth: Int, maxDepth: Int) {
        val rows = list.filterIsInstance<JsonObject>()
        if (rows.isEmpty()) {
            append(indent).append(k).append(": list of ").append(list.size).append(if (list.isEmpty()) " (empty)\n" else " values\n")
            return
        }
        val fields = LinkedHashMap<String, String>()
        rows.take(20).forEach { r -> r.forEach { (f, v) -> if (f !in fields) fields[f] = typeOf(f, v) } }
        append(indent).append(k).append(": list of ").append(rows.size).append(" rows {")
            .append(fields.entries.joinToString(", ") { "${it.key}: ${it.value}" }).append("}\n")
        // Category labels, so Claude can filter by them (e.g. [assetclass_l2=Gold]).
        for (f in fields.keys) {
            if (!SAFE_LABEL_KEYS.matches(f.lowercase(Locale.ENGLISH))) continue
            val labels = rows.mapNotNull { (it[f] as? JsonPrimitive)?.contentOrNull?.takeIf(SAFE_LABEL_VALUE::matches) }.distinct()
            if (labels.isNotEmpty()) append(indent).append("  ").append(f).append(" values: ").append(labels.joinToString(", ")).append('\n')
        }
        if (depth < maxDepth) rows.firstOrNull { r -> r.values.any { it is JsonObject || it is JsonArray } }?.let { r ->
            r.filterValues { it is JsonObject || it is JsonArray }.forEach { (f, v) ->
                append(indent).append("  each row's ").append(f).append(":\n")
                describe(JsonObject(mapOf(f to v)), "$indent    ", depth + 1, maxDepth, inNotes = false)
            }
        }
    }

    private fun typeOf(field: String, v: JsonElement): String = when (v) {
        is JsonObject -> "object"
        is JsonArray -> "list"
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            !v.isString -> if (v.contentOrNull == "true" || v.contentOrNull == "false") "yes/no" else "number"
            DATE_VALUE.containsMatchIn(v.content) -> "date"
            else -> "text"
        }
    }

    private fun primitive(key: String, v: JsonPrimitive, inNotes: Boolean): String = when {
        v is JsonNull -> "null"
        !v.isString -> if (v.contentOrNull == "true" || v.contentOrNull == "false") "yes/no" else "number"
        DATE_VALUE.containsMatchIn(v.content) -> "date"
        // The service's own explanatory notes (no digits, so no amounts or account numbers).
        inNotes -> "\"" + v.content.replace(Regex("[0-9]"), "").replace(Regex("\\s+"), " ").trim().take(300) + "\""
        SAFE_LABEL_KEYS.matches(key.lowercase(Locale.ENGLISH)) && SAFE_LABEL_VALUE.matches(v.content) -> "\"" + v.content + "\""
        else -> "text"
    }
}
