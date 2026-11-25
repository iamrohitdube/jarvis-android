package dev.jarvis.connectors

import dev.jarvis.connectors.google.EmailHeader
import dev.jarvis.connectors.google.EmailTriage
import dev.jarvis.connectors.google.EmailTriage.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmailTriageTest {
    private fun mail(
        from: String, subject: String, snippet: String = "", labels: List<String> = emptyList(), bulk: Boolean = false, at: Long = 0,
    ) = EmailHeader(from, subject, at, unread = true, snippet = snippet, labels = labels, bulk = bulk)

    private fun kind(e: EmailHeader) = EmailTriage.classify(e)

    @Test fun billsAndStatements() {
        assertEquals(Kind.BILL, kind(mail("HDFC Bank <emailstatements.cards@hdfcbank.net>", "Your HDFC Bank Credit Card Statement for November", bulk = true)))
        assertEquals(Kind.BILL, kind(mail("Airtel <ebill@airtel.com>", "Your Airtel bill is ready", "Amount due Rs. 1,178.82", bulk = true)))
        assertEquals(Kind.BILL, kind(mail("LIC <noreply@licindia.in>", "Premium due reminder", bulk = true)))
    }

    @Test fun bankAlertsAreImportantButBelowBills() {
        assertEquals(Kind.BANK, kind(mail("ICICI Bank <alerts@icicibank.com>", "Transaction alert", "INR 2,500.00 debited from A/c XX1234")))
        assertEquals(Kind.BANK, kind(mail("Payroll <payroll@acme.example>", "Salary credited for November")))
    }

    @Test fun jobs() {
        assertEquals(Kind.JOB, kind(mail("Acme Careers <no-reply@greenhouse.io>", "Thanks for applying to Acme", bulk = true)))
        assertEquals(Kind.JOB, kind(mail("Riya (Recruiter) <riya@talent.example>", "Interview schedule for Data Engineer")))
        // A job-alert newsletter is not a job email.
        assertEquals(Kind.NOISE, kind(mail("LinkedIn Job Alerts <jobalerts-noreply@linkedin.com>", "30 new jobs for you", labels = listOf("CATEGORY_UPDATES"), bulk = true)))
    }

    @Test fun government() {
        assertEquals(Kind.GOVERNMENT, kind(mail("Income Tax Department <donotreply@incometax.gov.in>", "ITR-1 filed successfully", bulk = true)))
        assertEquals(Kind.GOVERNMENT, kind(mail("EPFO <noreply@epfindia.gov.in>", "Contribution credited to your UAN")))
        assertEquals(Kind.GOVERNMENT, kind(mail("UIDAI <info@uidai.gov.in>", "Aadhaar update status")))
    }

    @Test fun securityAlerts() {
        assertEquals(Kind.SECURITY, kind(mail("Google <no-reply@accounts.google.com>", "Security alert", "New sign-in on Pixel")))
    }

    @Test fun peopleAreImportant() {
        assertEquals(Kind.PERSON, kind(mail("\"Priya Sharma\" <priya@example.com>", "Lunch on Friday?", labels = listOf("CATEGORY_PERSONAL"))))
    }

    @Test fun socialNewslettersAndPromosAreNoise() {
        assertEquals(Kind.NOISE, kind(mail("Facebook <notification@facebookmail.com>", "Rahul commented on your post", labels = listOf("CATEGORY_SOCIAL"), bulk = true)))
        assertEquals(Kind.NOISE, kind(mail("Instagram <no-reply@mail.instagram.com>", "You have 3 new followers", bulk = true)))
        assertEquals(Kind.NOISE, kind(mail("The Hustle <news@thehustle.co>", "Weekly digest: AI everywhere", bulk = true)))
        assertEquals(Kind.NOISE, kind(mail("Myntra <promo@myntra.com>", "Flat 60% off ends tonight", bulk = true)))
        assertEquals(Kind.NOISE, kind(mail("Quora Digest <digest-noreply@quora.com>", "Top stories for you", bulk = true)))
        // A bank's marketing mail is not a bank alert.
        assertEquals(Kind.NOISE, kind(mail("HDFC Bank <offers@hdfcbank.net>", "Exclusive deal: 10% cashback on credit card", bulk = true)))
    }

    @Test fun travelAndOrders() {
        assertEquals(Kind.TRAVEL, kind(mail("IndiGo <reservations@goindigo.in>", "Your flight booking confirmed, PNR AB12CD", bulk = true)))
        assertEquals(Kind.ORDER, kind(mail("Amazon.in <shipment-tracking@amazon.in>", "Your order has been shipped", labels = listOf("CATEGORY_UPDATES"), bulk = true)))
    }

    @Test fun sortPutsImportantFirstThenNewest() {
        val news = mail("news@x.example", "Weekly digest", bulk = true, at = 30)
        val bill = mail("HDFC Bank <s@hdfcbank.net>", "Credit card statement", bulk = true, at = 10)
        val person = mail("Priya <priya@example.com>", "Hi", at = 20)
        val security = mail("Google <no-reply@accounts.google.com>", "Security alert", at = 5)
        assertEquals(listOf(security, bill, person, news), EmailTriage.sort(listOf(news, bill, person, security)))
    }

    @Test fun amounts() {
        assertEquals("₹12,345.67", EmailTriage.extractAmount("Your statement: Total Amount Due Rs. 12,345.67 Minimum due Rs. 620"))
        assertEquals("₹1,178.82", EmailTriage.extractAmount("Bill amount: INR 1,178.82"))
        assertEquals("₹499", EmailTriage.extractAmount("Pay ₹499 by 5 Dec"))
        // Prefers the amount after the cue over an earlier one.
        assertEquals("₹8,000", EmailTriage.extractAmount("Credit limit Rs 2,00,000. Total amount due: Rs 8,000"))
        assertNull(EmailTriage.extractAmount("Your statement is ready"))
        assertNull(EmailTriage.extractAmount("Rs. 0.00 due"))
        assertNull(EmailTriage.extractAmount("Delivered within 24 hours 5 days a week"))
    }

    @Test fun dueDates() {
        assertEquals("05/12/2026", EmailTriage.extractDueDate("Payment Due Date: 05/12/2026 Total due Rs 100"))
        assertEquals("5 Dec 2026", EmailTriage.extractDueDate("Please pay by 5 Dec 2026 to avoid charges"))
        assertEquals("December 5, 2026", EmailTriage.extractDueDate("due on December 5, 2026."))
        assertNull(EmailTriage.extractDueDate("Statement dated 20/11/2026"))
    }
}
