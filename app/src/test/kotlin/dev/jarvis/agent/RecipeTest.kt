package dev.jarvis.agent

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** INDmoney's real structure (seen on the device 2026-11-25) with made-up numbers. */
internal val PORTFOLIO = """{
  "total_invested": 500000.0, "total_current_value": 520000.0, "total_networth": 519000.0,
  "investments": [
    {"asset_type":"EPF","invested_value":170000.0,"current_value":170000.0,"return":0.0,"return_percentage":0.0,"progress_value_percentage":32.7},
    {"asset_type":"US_STOCK","invested_value":100000.0,"current_value":120000.0,"return":20000.0,"return_percentage":20.0,"progress_value_percentage":23.1},
    {"asset_type":"MF","invested_value":100000.0,"current_value":98000.0,"return":-2000.0,"return_percentage":-2.0,"progress_value_percentage":18.8},
    {"asset_type":"SA","invested_value":80000.0,"current_value":80000.0,"return":0.0,"return_percentage":0.0,"progress_value_percentage":15.4},
    {"asset_type":"STOCK","invested_value":50000.0,"current_value":52000.0,"return":2000.0,"return_percentage":4.0,"progress_value_percentage":10.0},
    {"asset_type":"CRYPTO","invested_value":0.0,"current_value":0.0,"return":0.0,"return_percentage":0,"progress_value_percentage":0}
  ],
  "assets": [
    {"assetclass_l2":"Retirement","invested_value":170000.0,"current_value":170000.0,"return":0.0,"return_percentage":0.0,"progress_value_percentage":32.7},
    {"assetclass_l2":"Global Equity","invested_value":100000.0,"current_value":120000.0,"return":20000.0,"return_percentage":20.0,"progress_value_percentage":23.1},
    {"assetclass_l2":"Debt","invested_value":95000.0,"current_value":96000.0,"return":1000.0,"return_percentage":1.05,"progress_value_percentage":18.5},
    {"assetclass_l2":"Liquid","invested_value":80000.0,"current_value":80000.0,"return":0.0,"return_percentage":0.0,"progress_value_percentage":15.4},
    {"assetclass_l2":"Gold","invested_value":30000.0,"current_value":27000.0,"return":-3000.0,"return_percentage":-10.0,"progress_value_percentage":5.2},
    {"assetclass_l2":"Silver","invested_value":16000.0,"current_value":16000.0,"return":0.0,"return_percentage":0.0,"progress_value_percentage":3.1},
    {"assetclass_l2":"Equity","invested_value":7000.0,"current_value":7000.0,"return":0.0,"return_percentage":0.0,"progress_value_percentage":1.3},
    {"assetclass_l2":"Alternate","invested_value":0.0,"current_value":0.0,"return":0.0,"return_percentage":0,"progress_value_percentage":0}
  ],
  "sector": [],
  "market_cap": [
    {"market_cap":"Large Cap","invested_value":100000.0,"current_value":120000.0,"return":20000.0,"return_percentage":20.0,"progress_value_percentage":70.0},
    {"market_cap":"Small Cap","invested_value":40000.0,"current_value":40000.0,"return":0.0,"return_percentage":0.0,"progress_value_percentage":23.0},
    {"market_cap":"Others","invested_value":14000.0,"current_value":10000.0,"return":-4000.0,"return_percentage":-28.0,"progress_value_percentage":7.0}
  ],
  "liabilities": {"total_loan_balance":0.0,"total_credit_card_due":1000.0,"total":1000.0,"loans":[],
    "credit_cards":[
      {"name":"Zeta Rewards •• 4321","card_variant":"Zeta Rewards","total_due":1000.0,"due_date":"2026-11-30"},
      {"name":"Omega Card •• 8765","card_variant":"Omega Card","total_due":0}
    ]},
  "data_notes": {
    "sector": "Sector allocation is not available: the portfolio tracking service has no sector analytics. Sector rows are always empty.",
    "market_cap": "market_cap covers directly held Indian and US stocks only; mutual fund underlying exposure is not included."
  }
}"""

class RecipeTest {
    private val data = Json.parseToJsonElement(PORTFOLIO)
    private fun fill(t: String, item: String? = null) = Recipe.fill(t, data, item)

    @Test fun jargonQuestionsAnswered() {
        // "What's my net worth?"
        assertEquals("Your net worth is ₹5.2 lakh.", fill("Your net worth is {{val total_networth}}."))
        // "How much dry powder do I have?" (cash in the bank)
        assertEquals("You have ₹80,000 in liquid money.", fill("You have {{val assets[assetclass_l2=Liquid].current_value}} in liquid money."))
        // "Where am I bleeding money?"
        assertEquals(
            "Your weakest is Gold at -10%.",
            fill("Your weakest is {{bottom assets by return_percentage show assetclass_l2}} at {{min assets.return_percentage}}."),
        )
        // "What's my best performer?"
        assertEquals("Global Equity is up +20%.", fill("{{top assets by return_percentage show assetclass_l2}} is up {{max assets.return_percentage}}."))
        // "How much gold exposure?"
        assertEquals(
            "Gold is 5.2% of your portfolio, worth ₹27,000.",
            fill("Gold is {{pct assets[assetclass_l2=Gold].current_value of total_current_value}} of your portfolio, worth {{val assets[assetclass_l2=Gold].current_value}}."),
        )
        // "Large cap vs small cap?" (labels with spaces inside filters)
        assertEquals(
            "Large caps are ₹1.2 lakh and small caps ₹40,000.",
            fill("Large caps are {{val market_cap[market_cap=Large Cap].current_value}} and small caps {{val market_cap[market_cap=Small Cap].current_value}}."),
        )
        // "Any card bills due?"
        assertEquals(
            "You owe ₹1,000 on cards: Zeta Rewards ₹1,000, 30 Nov 2026.",
            fill("You owe {{sum liabilities.credit_cards.total_due}} on cards: {{list liabilities.credit_cards[total_due>0] show card_variant,total_due,due_date}}."),
        )
        // "How many cards have dues?"
        assertEquals("1 card has a due.", fill("{{count liabilities.credit_cards[total_due>0]}} card has a due."))
        // "How are my US stocks doing?" via the asset type code and the handed-back item.
        assertEquals("US stocks: +20%.", fill("{{val investments[asset_type=US_STOCK].asset_type}}: {{val investments[asset_type=US_STOCK].return_percentage}}."))
        assertEquals("₹1.2 lakh", fill("{{val investments[asset_type~\$item].current_value}}", item = "us stock"))
        // "Show my allocation" (sorted list)
        assertEquals(
            "Retirement ₹1.7 lakh, 32.7%; Global Equity ₹1.2 lakh, 23.1%",
            fill("{{list assets show assetclass_l2,current_value,progress_value_percentage by current_value desc limit 2}}"),
        )
    }

    @Test fun inventedFiguresRejectTheWholeRecipe() {
        listOf(
            "Your net worth is about 5 lakh.",              // a figure Claude made up
            "You have ₹{{val total_networth}}.",            // currency outside a placeholder
            "Gold is 5% of {{val total_networth}}.",        // percentage outside
            "Net worth {{val total_networth}} as of 2026.", // a year (digits)
            "Up 1.5 times since {{val total_invested}}.",
        ).forEach { t ->
            try { fill(t); fail("should reject: $t") } catch (_: Recipe.RecipeException) { }
        }
    }

    @Test fun badPlaceholdersRejectTheWholeRecipe() {
        listOf(
            "{{val nope}}", "{{val assets[assetclass_l2=Platinum].current_value}}", "{{frobnicate total}}",
            "{{pct total_networth}}", "{{val total_networth", "{{top assets show assetclass_l2}}", "{{pct total_networth of liabilities.loans.total}}",
        ).forEach { t ->
            try { fill(t); fail("should reject: $t") } catch (_: Recipe.RecipeException) { }
        }
    }

    @Test fun plainSentenceWithoutPlaceholdersIsAllowed() {
        assertEquals(
            "INDmoney doesn't track sector allocation.",
            fill("INDmoney doesn't track sector allocation."),
        )
    }

    @Test fun formatsCanBeForced() {
        assertEquals("519000", fill("{{val total_networth|num}}"))
        assertEquals("+5.2%", fill("{{val assets[assetclass_l2=Gold].progress_value_percentage|spct}}"))
    }

    @Test fun outlineHasStructureAndLabelsButNoValues() {
        val outline = DataOutline.of(data)
        // Structure and category labels Claude needs.
        listOf("total_networth: number", "assets: list of 8 rows", "assetclass_l2 values:", "Gold", "Global Equity", "Liquid",
            "market_cap values: Large Cap, Small Cap, Others", "asset_type values: EPF, US_STOCK, MF, SA, STOCK, CRYPTO",
            "credit_cards: list of 2 rows", "due_date: date", "Sector allocation is not available",
        ).forEach { assertTrue("missing '$it' in:\n$outline", outline.contains(it)) }
        // Never a value: amounts, percentages, dates, card names or digits.
        listOf("519000", "170000", "1000", "20.0", "2026-11-30", "Zeta", "Omega", "4321", "8765", "•").forEach {
            assertFalse("outline leaks '$it':\n$outline", outline.contains(it))
        }
        assertFalse("no digits at all:\n$outline", Regex("[0-9]{2,}").containsMatchIn(outline.replace(Regex("list of \\d+ rows|list of \\d+"), "")))
    }

    @Test fun tokenizerKeepsFiltersWhole() {
        assertEquals(listOf("val", "market_cap[market_cap=Large Cap].current_value"), Recipe.tokenize("val market_cap[market_cap=Large Cap].current_value"))
    }
}
