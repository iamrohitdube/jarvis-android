package dev.jarvis.voice

/** Cleans the transcript of what was said right after "Hey Jarvis". */
object FollowUp {
    private val leftovers = setOf("hey", "hi", "ok", "okay", "jarvis", "vis", "is", "this", "service", "travis", "harvest")

    /** Null when nothing usable is left (then JARVIS chimes and listens instead). */
    fun clean(text: String?): String? {
        val words = text?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: return null
        var i = 0
        // Only strip the first two words, and only if they look like the end of the wake phrase.
        while (i < 2 && i < words.size && words[i].lowercase().trim(',', '.', '!', '?') in leftovers) i++
        val rest = words.drop(i).joinToString(" ").trim(',', '.', ' ', '?', '!')
        return rest.takeIf { r -> r.count { it.isLetterOrDigit() } >= 3 && r.contains(' ') || r.length >= 4 }
    }
}
