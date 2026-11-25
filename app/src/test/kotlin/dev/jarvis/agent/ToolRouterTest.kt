package dev.jarvis.agent

import dev.jarvis.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRouterTest {
    private val settings = AppSettings()

    @Test fun netWorthRoutesToSnapshotWithHelperOnChatModel() {
        val r = ToolRouter.route("What's my net worth?", settings)
        assertEquals(Models.DEFAULT_CHAT, r.model)
        assertEquals(setOf(ToolGroup.FINANCE), r.groups)
        assertEquals(listOf("networth_snapshot", "lookup_ind_keys"), r.tools.map { it.name })
        assertEquals(setOf(ToolRegistry.INDMONEY), r.connectorIds)
    }

    @Test fun defaultsAreTheCheapestModel() {
        assertEquals(Models.HAIKU, settings.chatModel)
        assertEquals(Models.HAIKU, settings.quickModel)
    }

    @Test fun repliesAreShortByDefault() {
        assertTrue(Modes.QUICK.maxTokens <= 300)
        assertTrue(PromptBuilder.PERSONA.contains("one short sentence"))
    }

    @Test fun askingForMoreRoutesToDetailMode() {
        listOf("Tell me more", "explain that", "give me the details", "can you elaborate").forEach {
            assertEquals(it, Modes.DETAIL, ToolRouter.route(it, settings).mode)
        }
        assertEquals(Modes.QUICK, ToolRouter.route("what's the capital of France", settings).mode)
    }

    @Test fun detailModeKeepsFinanceTools() {
        val r = ToolRouter.route("explain my net worth in detail", settings)
        assertEquals(Modes.DETAIL, r.mode)
        assertTrue("networth_snapshot" in r.tools.map { it.name })
    }

    @Test fun simpleChatGoesToQuickModelWithNoTools() {
        val r = ToolRouter.route("Tell me a joke", settings)
        assertEquals(Models.HAIKU, r.model)
        assertEquals(Modes.QUICK, r.mode)
        assertTrue(r.tools.isEmpty())
    }

    @Test fun quickRoutingCanBeDisabled() {
        val r = ToolRouter.route("Tell me a joke", settings.copy(routeSimpleToQuick = false))
        assertEquals(Models.DEFAULT_CHAT, r.model)
        assertTrue(r.tools.isEmpty())
    }

    @Test fun longToolFreeQuestionUsesChatModel() {
        val r = ToolRouter.route("Explain ".repeat(30), settings)
        assertEquals(Models.DEFAULT_CHAT, r.model)
    }

    @Test fun specificToolsSelectedByKeyword() {
        val r = ToolRouter.route("show my holdings and SIPs", settings)
        val names = r.tools.map { it.name }.toSet()
        assertTrue("networth_holdings" in names)
        assertTrue("mf_sips" in names && "indian_stocks_sips" in names)
        assertTrue("networth_snapshot" !in names) // specific match replaces the group default
    }

    @Test fun groupKeywordAloneFallsBackToGroupDefault() {
        val r = ToolRouter.route("how are my investments doing", settings)
        assertEquals(listOf("networth_snapshot", "lookup_ind_keys"), r.tools.map { it.name })
    }

    @Test fun customModelsFromSettingsAreUsed() {
        val s = settings.copy(chatModel = "claude-opus-5-5", quickModel = "claude-sonnet-5-5")
        assertEquals("claude-opus-5-5", ToolRouter.route("my portfolio", s).model)
        assertEquals("claude-sonnet-5-5", ToolRouter.route("hi", s).model)
    }

    @Test fun clearBankPhrasesAreAnsweredOnThePhone() {
        listOf("what's my savings account balance", "bank balance", "how much money in my bank").forEach {
            assertTrue(it, ToolRouter.route(it, settings).tools.any { t -> t.name == "networth_snapshot" })
        }
    }

    @Test fun generalFinanceQuestionsAreNotTreatedAsPersonal() {
        listOf("what is a mutual fund", "is it a bank holiday tomorrow", "how much money does a pilot earn", "how do I start investing").forEach {
            assertTrue(it, ToolRouter.route(it, settings).tools.none { t -> t.isPersonal })
        }
    }
}
