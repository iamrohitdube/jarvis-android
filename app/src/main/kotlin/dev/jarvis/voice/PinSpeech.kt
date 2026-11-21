package dev.jarvis.voice

/**
 * Turns a recognised spoken PIN into digits: "two five eight zero", "2580", "two-five eight oh",
 * "double five one nine", "my PIN is 2 5 8 0". Returns null when anything other than digits (and a few
 * filler words) was said, so a misheard sentence is treated as "didn't catch that" (no lockout penalty).
 * The result is never logged or sent anywhere.
 */
object PinSpeech {
    private val words = mapOf(
        "zero" to "0", "oh" to "0", "o" to "0", "nought" to "0", "naught" to "0",
        "one" to "1", "won" to "1",
        "two" to "2", "to" to "2", "too" to "2",
        "three" to "3", "tree" to "3",
        "four" to "4", "for" to "4", "fore" to "4",
        "five" to "5",
        "six" to "6",
        "seven" to "7",
        "eight" to "8", "ate" to "8",
        "nine" to "9", "niner" to "9",
    )
    private val repeats = mapOf("double" to 2, "triple" to 3)
    private val filler = setOf("my", "pin", "is", "it's", "its", "the", "code", "number", "uh", "um", "and", "okay", "ok", "jarvis")

    fun digits(text: String): String? {
        val tokens = text.lowercase()
            .replace(Regex("[^a-z0-9' ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        val out = StringBuilder()
        var repeat = 1
        for (t in tokens) {
            when {
                t.all { it.isDigit() } -> { out.append(if (repeat > 1 && t.length == 1) t.repeat(repeat) else t); repeat = 1 }
                t in words -> { out.append(words.getValue(t).repeat(repeat)); repeat = 1 }
                t in repeats -> repeat = repeats.getValue(t)
                t in filler -> Unit
                else -> return null
            }
        }
        return out.toString().takeIf { it.length in 4..8 }
    }
}
