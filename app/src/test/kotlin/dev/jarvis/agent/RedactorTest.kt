package dev.jarvis.agent

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    private fun r(s: String, keepAmounts: Boolean = false) = Redactor.text(s, keepAmounts)

    @Test fun identifiersAreRemoved() {
        assertEquals("Mail [email] now", r("Mail priya.sharma+work@example.co.in now"))
        assertEquals("Call [number] or [number]", r("Call +91 55500 01001 or 5550001001"))
        assertEquals("Card [number] used", r("Card 4111 1111 1111 1111 used"))
        assertEquals("Aadhaar [number]", r("Aadhaar 1234 5678 9012"))
        assertEquals("IFSC [ifsc], PAN [id]", r("IFSC HDFC0001234, PAN ABCDE1234F"))
        assertEquals("Reset at [link] today", r("Reset at https://bank.example/reset?token=abc123 today"))
    }

    @Test fun maskedAccountNumbersAreRemoved() {
        listOf(
            "debited from A/c XX1234" to "debited from A/c [account]",
            "Zeta Rewards •• 4321" to "Zeta Rewards [account]",
            "card ending 1234 was used" to "card [account] was used",
            "a/c no. 123456 credited" to "[account] credited",
            "**** 9876 statement" to "[account] statement",
        ).forEach { (input, want) -> assertEquals(input, want, r(input)) }
    }

    @Test fun oneTimeCodesAreRemoved() {
        assertEquals("Your OTP is [code]. Don't share.", r("Your OTP is 482913. Don't share."))
        assertEquals("[code] is your verification code", r("482913 is your verification code"))
        assertEquals("login code: [code]", r("login code: 9921"))
    }

    @Test fun amountsGoUnlessTheFeatureNeedsThem() {
        assertEquals("[amount] credited, balance [amount]", r("Rs. 5,000 credited, balance ₹1,20,000.50"))
        assertEquals("Rs. 5,000 credited", r("Rs. 5,000 credited", keepAmounts = true))
    }

    @Test fun ordinaryTextSurvives() {
        listOf("Lunch at 1 pm on Friday?", "Order 12345 shipped", "Meet in room 402", "Q3 review moved to 28 Nov").forEach {
            assertEquals(it, r(it))
        }
    }

    @Test fun jsonKeepsFieldNamesAndRedactsValues() {
        val data = Json.parseToJsonElement(
            """{"total_due":875.40,"credit_cards":[{"name":"Zeta Rewards •• 4321","due_date":"2026-11-30"}],"from":"a@b.co"}""",
        )
        val out = Redactor.json(data, keepAmounts = false).toString()
        assertTrue(out, out.contains("\"total_due\":\"[amount]\""))
        assertTrue(out, out.contains("Zeta Rewards [account]"))
        assertTrue(out, out.contains("2026-11-30")) // dates stay
        assertFalse(out, out.contains("4321") || out.contains("a@b.co") || out.contains("875"))
        val finance = Redactor.json(data, keepAmounts = true).toString()
        assertTrue(finance, finance.contains("875.40"))
        assertFalse(finance, finance.contains("4321"))
    }
}
