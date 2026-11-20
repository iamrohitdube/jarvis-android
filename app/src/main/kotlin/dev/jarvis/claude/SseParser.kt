package dev.jarvis.claude

/** One Server-Sent Event: the `event:` name (may be null) and the joined `data:` payload. */
data class SseEvent(val event: String?, val data: String)

/**
 * Incremental SSE line parser (WHATWG event-stream format, the subset the Anthropic API uses).
 * Feed it lines without their trailing newline; it returns an event when a blank line ends one.
 */
class SseParser {
    private var eventName: String? = null
    private val data = StringBuilder()
    private var hasData = false

    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null // comment / keep-alive
        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        var value = if (colon == -1) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> eventName = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            else -> Unit // id, retry: unused
        }
        return null
    }

    /** Call at end of stream to emit a final event that was not followed by a blank line. */
    fun finish(): SseEvent? = flush()

    private fun flush(): SseEvent? {
        val event = if (hasData) SseEvent(eventName, data.toString()) else null
        eventName = null
        data.setLength(0)
        hasData = false
        return event
    }
}
