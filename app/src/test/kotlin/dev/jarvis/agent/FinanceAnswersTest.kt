package dev.jarvis.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceAnswersTest {
    @Test fun indianUnits() {
        assertEquals("₹45,300", FinanceAnswers.inr(45_300.0))
        assertEquals("₹99,999", FinanceAnswers.inr(99_999.0))
        assertEquals("₹12.4 lakh", FinanceAnswers.inr(1_240_000.0))
        assertEquals("₹1 lakh", FinanceAnswers.inr(100_000.0))
        assertEquals("₹1.25 crore", FinanceAnswers.inr(12_500_000.0))
        assertEquals("-₹5,000", FinanceAnswers.inr(-5_000.0))
    }

    @Test fun netWorthWithInvestedAndGain() {
        val raw = """{"data":{"net_worth":{"value":1240000},"invested_value":1000000,"total_returns":240000}}"""
        assertEquals("Net worth is ₹12.4 lakh. Invested ₹10 lakh, up ₹2.4 lakh (24%).", FinanceAnswers.render("networth_snapshot", raw))
    }

    @Test fun netWorthNumbersAsFormattedStrings() {
        assertEquals("Net worth is ₹12.4 lakh.", FinanceAnswers.render("networth_snapshot", """{"netWorth":"₹12,40,000"}"""))
    }

    @Test fun doubleEncodedJsonIsUnwrapped() {
        assertEquals("Net worth is ₹1 lakh.", FinanceAnswers.render("networth_snapshot", "\"{\\\"networth\\\":100000}\""))
    }

    @Test fun bankBalanceFromAFieldOrAnAllocationRow() {
        assertEquals("Bank and savings balance is ₹52,000.", FinanceAnswers.render("networth_snapshot", """{"networth":900000,"bank_balance":52000}""", "savings account balance"))
        val rows = """{"allocation":[{"asset_type":"Mutual Funds","value":800000},{"asset_type":"Bank Balance","value":152000}]}"""
        assertEquals("Bank Balance: ₹1.5 lakh.", FinanceAnswers.render("networth_snapshot", rows, "what's in my bank"))
    }

    @Test fun noBankFigureSaysSoInsteadOfGuessing() {
        assertEquals(
            "INDmoney doesn't show a separate bank balance. Net worth is ₹9 lakh.",
            FinanceAnswers.render("networth_snapshot", """{"networth":900000}""", "my bank balance"),
        )
        assertTrue(FinanceAnswers.isBankQuestion("what's my savings account balance"))
        assertFalse(FinanceAnswers.isBankQuestion("what's my net worth"))
    }

    @Test fun holdingsListsTheLargestFirst() {
        val raw = """{"holdings":[
            {"name":"Small Co","current_value":5000,"pnl_percent":-2.5},
            {"name":"Big Fund","current_value":900000,"pnl_percent":12.34},
            {"name":"Mid Corp","current_value":120000}
        ]}"""
        val text = FinanceAnswers.render("networth_holdings", raw)
        assertEquals(
            "You hold 3 positions.\n• Big Fund, ₹9 lakh (+12.3%)\n• Mid Corp, ₹1.2 lakh\n• Small Co, ₹5,000 (-2.5%)",
            text,
        )
    }

    @Test fun sipsWithTotal() {
        val raw = """{"sips":[{"scheme_name":"Index Fund","sip_amount":5000,"next_sip_date":"5 Dec"},{"scheme_name":"Gold","sip_amount":2000}]}"""
        assertEquals(
            "2 active mutual fund SIPs, ₹7,000 in total.\n• Index Fund, ₹5,000, next 5 Dec\n• Gold, ₹2,000",
            FinanceAnswers.render("mf_sips", raw),
        )
        assertEquals("No active stock SIPs.", FinanceAnswers.render("indian_stocks_sips", """{"sips":[]}"""))
    }

    @Test fun watchlist() {
        assertEquals("Your watchlist has 2: TCS, Infosys.", FinanceAnswers.render("user_watchlist", """[{"symbol":"TCS"},{"symbol":"Infosys"}]"""))
    }

    @Test fun unknownShapeFallsBackToReadableFieldsWithoutGuessing() {
        val text = FinanceAnswers.render("networth_snapshot", """{"foo_bar":12,"label":"x","nested":{"a":1}}""")
        assertTrue(text, text.startsWith("Here's what INDmoney returned:"))
        assertTrue(text.contains("• Foo bar: 12"))
        assertFalse(text.contains("Net worth"))
    }

    @Test fun plainTextIsShownAsIs() {
        assertEquals("Here's what INDmoney returned:\nYour net worth is 5 lakh", FinanceAnswers.render("networth_snapshot", "Your net worth is 5 lakh"))
    }

    @Test fun shapeHasNoValues() {
        val shape = JsonShape.describe("""{"name":"Asha","pan":"ABCDE1234F","holdings":[{"v":1},{"v":2}],"ok":true,"x":null}""")
        assertEquals("{name:str,pan:str,holdings:[{v:num}]×2,ok:bool,x:null}", shape)
        assertFalse(shape.contains("Asha") || shape.contains("ABCDE"))
        assertEquals("text(5 chars)", JsonShape.describe("hello"))
    }
}
