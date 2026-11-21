package dev.jarvis.agent

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Facts the phone knows itself. */
data class PhoneInfo(val batteryPercent: Int?, val charging: Boolean?)

/**
 * Answers simple questions on the phone, with no request to Claude (instant, free): the time, the date/day,
 * and the battery. Deliberately narrow: anything else ("time in London", "time until my meeting") goes to
 * Claude, which also receives the local time with every question.
 */
object LocalAnswers {
    private val time = Regex("^(what('s| is) the time|what time is it|tell me the time|current time|the time|time)( now| please| right now)?$")
    private val date = Regex("^(what('s| is) (the |today's )?date|today's date|what('s| is) today|what day is (it|today)|which day is (it|today)|date|the date)( today| please)?$")
    private val battery = Regex("^(what('s| is) (my |the )?battery( level| percentage| status)?|battery( level| percentage| status)?|how much battery( do i have| is left| left)?|is (my phone|the phone|it) charging)( please)?$")
    private val fillers = Regex("^(hey |hi |ok |okay )?(jarvis )?(can you |please |could you )?(tell me )?")

    fun answer(question: String, now: ZonedDateTime, phone: PhoneInfo?): String? {
        val q = question.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
            .replace(fillers, "").trim()
        return when {
            time.matches(q) -> "It's " + DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(now) + "."
            date.matches(q) -> "It's " + DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH).format(now) + "."
            battery.matches(q) -> phone?.batteryPercent?.let { pct ->
                "Battery's at $pct%" + when (phone.charging) { true -> ", charging."; false -> "."; null -> "." }
            }
            else -> null
        }
    }
}
