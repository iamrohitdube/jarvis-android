package dev.jarvis.agent

import dev.jarvis.connectors.google.EmailFilter
import dev.jarvis.connectors.google.EmailHeader
import dev.jarvis.connectors.google.InboxResult
import dev.jarvis.testJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class EmailAnswersTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 25, 18, 0, 0, 0, zone)
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 11, day, hour, 5, 0, 0, zone).toInstant().toEpochMilli()

    private val goa = EmailHeader("Acme Travel <trips@acme.example>", "Your trip to Goa", at(25, 9), unread = true)
    private val lunch = EmailHeader("\"Priya Sharma\" <priya@example.com>", "Lunch on Friday?", at(24, 13), unread = true)
    private val old = EmailHeader("billing@cloud.example", "Invoice", at(20, 10), unread = false)

    private fun raw(r: InboxResult) = testJson.encodeToString(InboxResult.serializer(), r)

    private val bill = EmailHeader("HDFC Bank <s@hdfcbank.net>", "Credit card statement", at(25, 8), unread = true,
        kind = "BILL", amount = "₹12,345", due = "05/12/2026")
    private val digest = EmailHeader("news@digest.example", "Weekly digest", at(25, 7), unread = true, bulk = true, kind = "NOISE")
    private val promo = EmailHeader("Shop <deals@shop.example>", "60% off", at(25, 6), unread = true, bulk = true, kind = "NOISE")

    @Test fun unreadShowsWhatMattersAndCountsTheRest() {
        val text = EmailAnswers.render("gmail_unread", raw(InboxResult(estimate = 9, messages = listOf(digest, goa, lunch, promo, bill), hidden = 1)), null, now)
        assertTrue(text, text.startsWith("You have 9 unread emails. 3 need attention:\n"))
        val lines = text.lines()
        assertEquals("• Bill · HDFC Bank: Credit card statement, ₹12,345 due 05/12/2026 (8:05 AM)", lines[1])
        assertEquals("• Priya Sharma: Lunch on Friday? (yesterday)", lines[2])
        assertEquals("• Travel · Acme Travel: Your trip to Goa (9:05 AM)", lines[3])
        assertTrue(text, text.contains("Also 2 newsletters or social posts skipped."))
        assertFalse("newsletters not listed", text.contains("Weekly digest"))
        assertTrue(text, text.contains("1 hidden by your privacy filter."))
        assertFalse("no email addresses", text.contains("@"))
    }

    @Test fun onlyNoise() {
        val text = EmailAnswers.render("gmail_recent", raw(InboxResult(messages = listOf(digest, promo))), null, now)
        assertEquals("Nothing important in your latest emails, just 2 newsletters or social posts.", text)
    }

    @Test fun noUnread() {
        assertEquals("No unread emails.", EmailAnswers.render("gmail_unread", raw(InboxResult(estimate = 0)), null, now))
    }

    @Test fun searchResultsAndEmptySearch() {
        val hit = EmailAnswers.render("gmail_search", raw(InboxResult(query = "Acme", messages = listOf(goa))), "Acme", now)
        assertTrue(hit, hit.startsWith("1 email for \"Acme\""))
        val miss = EmailAnswers.render("gmail_search", raw(InboxResult(query = "Zeta")), "Zeta", now)
        assertEquals("No recent emails found for \"Zeta\".", miss)
    }

    @Test fun offlineSearchFiltersTheSavedLatestList() {
        val text = EmailAnswers.render("gmail_recent", raw(InboxResult(messages = listOf(goa, lunch, old))), "priya", now)
        assertTrue(text, text.contains("Lunch on Friday?"))
        assertFalse(text, text.contains("Goa"))
    }

    @Test fun senderNamesAndDates() {
        assertEquals("Priya Sharma", EmailAnswers.senderName("\"Priya Sharma\" <priya@example.com>"))
        assertEquals("billing", EmailAnswers.senderName("billing@cloud.example"))
        assertEquals("billing", EmailAnswers.senderName("<billing@cloud.example>"))
        assertEquals("20 Nov", EmailAnswers.whenLabel(old.at, now))
    }

    @Test fun badResultSaysSoInsteadOfGuessing() {
        assertEquals("I couldn't read the email result.", EmailAnswers.render("gmail_recent", "not json", null, now))
    }

    @Test fun filterDropsCodesAndExcludedSenders() {
        val otp = EmailHeader("Shop <a@shop.example>", "Your OTP is 4829", 0, true)
        val code = EmailHeader("x@y.example", "Hello", 0, true, snippet = "Your verification code is 1234")
        val bank = EmailHeader("HDFC Bank <alerts@hdfcbank.net>", "Statement", 0, true)
        val kept = EmailFilter.apply(listOf(goa, otp, code, bank, lunch), listOf("hdfcbank"))
        assertEquals(listOf(goa, lunch), kept)
        // Bank mail is shown by default now (the user wants statements); codes are still dropped.
        assertEquals(listOf(bank), EmailFilter.apply(listOf(otp, bank), EmailFilter.DEFAULT_EXCLUDED_SENDERS))
        // "hotpot" must not count as an OTP.
        assertEquals(1, EmailFilter.apply(listOf(EmailHeader("a@b.example", "Hotpot night", 0, true)), emptyList()).size)
        // An empty exclusion list keeps the bank mail.
        assertTrue(bank in EmailFilter.apply(listOf(bank), emptyList()))
    }

    @Test fun parsesSenderSetting() {
        assertEquals(listOf("hdfcbank", "amex"), EmailFilter.parseSenders(" HDFCbank, amex,\n\n, amex "))
    }
}
