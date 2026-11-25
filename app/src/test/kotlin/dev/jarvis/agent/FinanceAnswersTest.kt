package dev.jarvis.agent

import dev.jarvis.testJson

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
        assertEquals("Bank balance: ₹1.5 lakh.", FinanceAnswers.render("networth_snapshot", rows, "what's in my bank"))
    }

    @Test fun noBankFigureSaysSoInsteadOfGuessing() {
        assertEquals(
            "INDmoney doesn't show bank or cash money separately. Net worth is ₹9 lakh.",
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

    private val assetClasses = """{"status":"success","data":{"asset_classes":[
        {"asset_class":"Equity","current_value":600000,"invested_value":500000},
        {"asset_class":"Liquid","current_value":200000,"invested_value":200000},
        {"asset_class":"Mutual Funds","current_value":400000,"invested_value":300000}
    ]}}"""

    @Test fun netWorthFromAssetClassesWhenThereIsNoTotal() {
        assertEquals(
            "Net worth is ₹12 lakh. Invested ₹10 lakh, up ₹2 lakh (20%). Biggest: Equity ₹6 lakh, Mutual funds ₹4 lakh, Liquid ₹2 lakh.",
            FinanceAnswers.render("networth_snapshot", assetClasses, "what's my net worth"),
        )
    }

    @Test fun bankQuestionUsesTheLiquidClass() {
        assertEquals(
            "Liquid: ₹2 lakh (INDmoney's Liquid covers bank, savings, cash and deposits).",
            FinanceAnswers.render("networth_snapshot", assetClasses, "how much is in my bank account"),
        )
        val two = """{"allocation":[{"type":"Savings","value":50000},{"type":"Fixed Deposit","value":100000},{"type":"Equity","value":900000}]}"""
        assertEquals("Bank and cash: ₹1.5 lakh (Savings ₹50,000, Fixed deposits ₹1 lakh).", FinanceAnswers.render("networth_snapshot", two, "my bank balance"))
    }

    @Test fun jsonWrappedInTextIsStillRead() {
        assertEquals("Net worth is ₹1 lakh.", FinanceAnswers.render("networth_snapshot", "Here is your data:\n```json\n{\"networth\":100000}\n```"))
    }

    @Test fun unreadableJsonIsNeverDumped() {
        val text = FinanceAnswers.render("networth_snapshot", """{"data":{"x":{"y":[1,2,3]}}}""")
        assertTrue(text, text.startsWith("I got your INDmoney data but couldn't read its format yet."))
        assertFalse(text.contains("{"))
    }

    @Test fun shapeShowsAssetClassNamesButNoAmounts() {
        val shape = JsonShape.describe(assetClasses, maxDepth = 6, labels = true)
        assertTrue(shape, shape.contains("asset_class:str(\"Liquid\")"))
        assertTrue(shape, shape.contains("current_value:num"))
        assertFalse(shape, shape.contains("600000") || shape.contains("200000"))
        assertEquals("text-wrapped {networth:num}", JsonShape.describe("Result: {\"networth\":5}"))
    }

    /** INDmoney's real envelope (2026-11-25): the data is JSON text inside "result". Numbers here are made up. */
    private val indmoneyEnvelope = run {
        val inner = """{
  "total_invested": 400000.0,
  "total_current_value": 450000.0,
  "total_networth": 440000.0,
  "investments": [
    {"asset_type": "EPF", "invested_value": 150000.0, "current_value": 150000.0, "return": 0.0, "return_percentage": 0.0, "progress_value_percentage": 33.3},
    {"asset_type": "US_STOCK", "invested_value": 100000.0, "current_value": 130000.0, "return": 30000.0, "return_percentage": 30.0, "progress_value_percentage": 28.9},
    {"asset_type": "MF", "invested_value": 100000.0, "current_value": 120000.0, "return": 20000.0, "return_percentage": 20.0, "progress_value_percentage": 26.7},
    {"asset_type": "LIQUID", "invested_value": 50000.0, "current_value": 50000.0, "return": 0.0, "return_percentage": 0.0, "progress_value_percentage": 11.1}
  ]
}"""
        """{"result":${testJson.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(inner))}}"""
    }

    @Test fun indmoneyEnvelopeNetWorth() {
        assertEquals(
            "Net worth is ₹4.4 lakh. Invested ₹4 lakh, up ₹50,000 (12.5%). Biggest: EPF ₹1.5 lakh, US stocks ₹1.3 lakh, Mutual funds ₹1.2 lakh.",
            FinanceAnswers.render("networth_snapshot", indmoneyEnvelope, "what's my net worth"),
        )
    }

    @Test fun indmoneyEnvelopeBankUsesLiquid() {
        assertEquals(
            "Liquid: ₹50,000 (INDmoney's Liquid covers bank, savings, cash and deposits).",
            FinanceAnswers.render("networth_snapshot", indmoneyEnvelope, "how much is in my bank account"),
        )
    }

    @Test fun envelopeShapeIsDescribed() {
        val shape = JsonShape.describe(indmoneyEnvelope, maxDepth = 6, labels = true)
        assertTrue(shape, shape.startsWith("{total_invested:num,"))
        assertTrue(shape, shape.contains("asset_type:str(\"US_STOCK\")"))
    }

    @Test fun shapeHasNoValues() {
        val shape = JsonShape.describe("""{"name":"Asha","pan":"ABCDE1234F","holdings":[{"v":1},{"v":2}],"ok":true,"x":null}""")
        assertEquals("{name:str,pan:str,holdings:[{v:num}]×2,ok:bool,x:null}", shape)
        assertFalse(shape.contains("Asha") || shape.contains("ABCDE"))
        assertEquals("text(5 chars)", JsonShape.describe("hello"))
    }
}
