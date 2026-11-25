package dev.jarvis.agent

import dev.jarvis.connectors.McpToolDef
import dev.jarvis.data.AppSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    private val settings = AppSettings()
    private fun def(name: String) = McpToolDef(name, "desc of $name", JsonObject(mapOf("type" to JsonPrimitive("object"))))
    private val priceTools = listOf(def("get_indian_stocks_details"), def("lookup_ind_keys"))

    @Test fun toolFreeQuickRequestHasNoToolsOrBetas() {
        val route = ToolRouter.route("hi", settings)
        val req = PromptBuilder.build(route, emptyList(), "hi", emptyList(), serverFallback = true)
        assertEquals("claude-haiku-5-5", req.body["model"]!!.jsonPrimitive.content)
        assertNull(req.body["tools"]); assertNull(req.body["mcp_servers"])
        assertNull(req.body["fallbacks"]) // Haiku has no server-side fallback
        assertTrue(req.betas.isEmpty())
        assertEquals("low", req.body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        // API version 2026-10-21 rejects sampling params; we must never send them.
        listOf("temperature", "top_p", "top_k").forEach { assertNull(req.body[it]) }
        // Quick route on Haiku: thinking off and a tight cap to save output tokens.
        assertEquals("disabled", req.body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(Modes.QUICK.maxTokens, req.body["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun thinkingOffIsNeverSentToModelsThatRejectIt() {
        val s = settings.copy(quickModel = Models.SONNET)
        val req = PromptBuilder.build(ToolRouter.route("hi", s), emptyList(), "hi", emptyList(), serverFallback = true)
        assertNull(req.body["thinking"]) // Sonnet 5.5 400s on {type: disabled}
    }

    @Test fun toolTurnsKeepAdaptiveThinkingAndUseCheapDefault() {
        val route = ToolRouter.route("reliance share price", settings)
        val req = PromptBuilder.build(route, emptyList(), "reliance share price", priceTools, serverFallback = true)
        assertEquals(Models.HAIKU, req.body["model"]!!.jsonPrimitive.content)
        assertNull(req.body["thinking"]) // adaptive (default) so tool calls are planned properly
        assertNull(req.body["fallbacks"]) // Haiku has no server-side fallback
        assertFalse(PromptBuilder.FALLBACK_BETA in req.betas)
        assertTrue(req.body["max_tokens"]!!.jsonPrimitive.content.toInt() <= 1500)
    }

    private val sonnet = settings.copy(chatModel = Models.SONNET)

    @Test fun publicToolsAreSentAsClientToolsWithNoMcpServerOrToken() {
        val route = ToolRouter.route("reliance share price", sonnet)
        val req = PromptBuilder.build(route, emptyList(), "reliance share price", priceTools, serverFallback = true)

        assertNull(req.body["mcp_servers"]) // D29: no connector is ever handed to Anthropic
        assertTrue(req.betas.none { it.startsWith("mcp-client") })
        val tools = req.body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("get_indian_stocks_details", "lookup_ind_keys"), tools.map { it["name"]!!.jsonPrimitive.content })
        tools.forEach { t ->
            assertEquals(setOf("name", "description", "input_schema"), t.keys)
        }
        assertTrue(PromptBuilder.FALLBACK_BETA in req.betas)
        assertEquals("default", req.body["fallbacks"]!!.jsonPrimitive.content)
    }

    @Test fun fallbackCanBeTurnedOff() {
        val route = ToolRouter.route("reliance share price", sonnet)
        val req = PromptBuilder.build(route, emptyList(), "reliance share price", priceTools, serverFallback = false)
        assertNull(req.body["fallbacks"])
        assertFalse(PromptBuilder.FALLBACK_BETA in req.betas)
    }

    @Test fun noToolDefinitionsMeansNoTools() {
        val route = ToolRouter.route("reliance share price", settings)
        val req = PromptBuilder.build(route, emptyList(), "reliance share price", emptyList(), serverFallback = false)
        assertNull(req.body["tools"]); assertNull(req.body["mcp_servers"])
    }

    @Test fun historyThenUserThenToolRounds() {
        val route = ToolRouter.route("reliance share price", settings)
        val history = listOf(ChatTurn(ChatTurn.Role.USER, "hello"), ChatTurn(ChatTurn.Role.ASSISTANT, "hi"))
        val use = JsonObject(mapOf("type" to JsonPrimitive("tool_use"), "id" to JsonPrimitive("t1")))
        val result = JsonObject(mapOf("type" to JsonPrimitive("tool_result"), "tool_use_id" to JsonPrimitive("t1")))
        val continuation = listOf(
            JsonObject(mapOf("role" to JsonPrimitive("assistant"), "content" to JsonArray(listOf(use)))),
            JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonArray(listOf(result)))),
        )
        val msgs = PromptBuilder.build(route, history, "reliance share price", priceTools, false, continuation).body["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user", "assistant", "user"), msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("reliance share price", msgs[2].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("t1", msgs[4].jsonObject["content"]!!.jsonArray[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
    }

    @Test fun systemPromptAndToolsAreStableAcrossTurnsForCaching() {
        val route = ToolRouter.route("reliance share price", settings)
        val a = PromptBuilder.build(route, emptyList(), "reliance share price", priceTools, false)
        val b = PromptBuilder.build(route, listOf(ChatTurn(ChatTurn.Role.USER, "x"), ChatTurn(ChatTurn.Role.ASSISTANT, "y")), "nifty", priceTools.reversed(), false)
        assertEquals(a.body["system"], b.body["system"])
        assertEquals(a.body["tools"], b.body["tools"]) // sorted, so order of discovery doesn't matter
        assertEquals("ephemeral", a.body["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun localTimeGoesOnTheQuestionNotTheCachedSystemPrompt() {
        val route = ToolRouter.route("what's the time", settings)
        val a = PromptBuilder.build(route, emptyList(), "what's the time", emptyList(), false, context = "Current local time: A.")
        val b = PromptBuilder.build(route, emptyList(), "what's the time", emptyList(), false, context = "Current local time: B.")
        assertEquals(a.body["system"], b.body["system"]) // cache prefix unchanged by the clock
        val content = a.body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals("Current local time: A.", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("what's the time", content[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun localTimeLineFormat() {
        val t = java.time.ZonedDateTime.of(2026, 11, 21, 1, 25, 0, 0, java.time.ZoneId.of("Asia/Kolkata"))
        assertEquals("Current local time: Sat 21 Nov 2026, 1:25 AM (Asia/Kolkata, UTC+05:30).", AgentLoop.localTimeLine(t))
    }

    @Test fun endpointToStringRedactsToken() {
        val e = dev.jarvis.connectors.McpEndpoint("INDmoney", "https://mcp.indmoney.com/mcp", "tok-secret")
        assertFalse(e.toString().contains("tok-secret"))
    }
}
