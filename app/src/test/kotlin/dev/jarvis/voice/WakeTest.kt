package dev.jarvis.voice

import dev.jarvis.agent.AgentLoop
import dev.jarvis.agent.AuthTier
import dev.jarvis.agent.ToolRegistry
import dev.jarvis.claude.AssembledMessage
import dev.jarvis.claude.LlmClient
import dev.jarvis.claude.LlmEvent
import dev.jarvis.claude.LlmRequest
import dev.jarvis.claude.StreamUpdate
import dev.jarvis.claude.Usage
import dev.jarvis.connectors.Connector
import dev.jarvis.connectors.ConnectorStatus
import dev.jarvis.connectors.ConnectorTestResult
import dev.jarvis.data.SettingsRepository
import dev.jarvis.memory.HistoryStore
import dev.jarvis.security.AccessController
import dev.jarvis.security.AccessResult
import dev.jarvis.security.AuthMethod
import dev.jarvis.security.DenyReason
import dev.jarvis.security.SessionManager
import dev.jarvis.security.SessionPolicy
import dev.jarvis.tempDataStore
import dev.jarvis.tempUsageMeter
import dev.jarvis.testDb
import dev.jarvis.ui.wake.WakePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityTest {
    @Test fun nearMeansCoveredForBinaryAndCentimetreSensors() {
        assertTrue(ProximityPocketDetector.isNear(0f, 5f))      // binary sensor: near
        assertFalse(ProximityPocketDetector.isNear(5f, 5f))     // binary sensor: far = max range
        assertTrue(ProximityPocketDetector.isNear(1.5f, 10f))   // cm sensor: 1.5 cm
        assertFalse(ProximityPocketDetector.isNear(8f, 10f))    // cm sensor: 8 cm
    }
}

class WakePhraseTest {
    @Test fun findsJarvisAndReturnsTheQuestion() {
        assertEquals("what's the time", WakePhrase.match("Hey Jarvis, what's the time?", WakeSensitivity.NORMAL))
        assertEquals("", WakePhrase.match("hey jarvis", WakeSensitivity.NORMAL))
        assertEquals("call mom", WakePhrase.match("Hi Jervis call mom", WakeSensitivity.NORMAL)) // common mishearing
        assertNull(WakePhrase.match("what's the weather", WakeSensitivity.NORMAL))
    }

    @Test fun strictNeedsAGreeting() {
        assertEquals("", WakePhrase.match("ok Jarvis", WakeSensitivity.STRICT))
        assertNull(WakePhrase.match("Jarvis what time", WakeSensitivity.STRICT))
        assertNull(WakePhrase.match("I told Jarvis about it", WakeSensitivity.STRICT))
    }

    @Test fun nameMidConversationNeverWakes() {
        for (s in listOf(WakeSensitivity.STRICT, WakeSensitivity.NORMAL)) {
            assertNull(WakePhrase.match("I told Jarvis about it", s))
            assertNull(WakePhrase.match("yeah hey jarvis is a cool name", s))
        }
        for (s in WakeSensitivity.entries) assertNull(WakePhrase.match("we watched iron man and jarvis was so cool", s))
    }

    @Test fun startOfUtteranceWakes() {
        assertEquals("what's the time", WakePhrase.match("Jarvis what's the time", WakeSensitivity.NORMAL))
        assertEquals("lights", WakePhrase.match("um hey Jarvis lights", WakeSensitivity.NORMAL))
        assertEquals("about it", WakePhrase.match("I told Jarvis about it", WakeSensitivity.SENSITIVE))
    }

    @Test fun alternativesOnlyCountForTheExactName() {
        // A lower guess with a near spelling or mid-sentence name doesn't wake.
        assertNull(WakePhrase.matchAny(listOf("hey service", "hey jarviz", "hey travis"), WakeSensitivity.NORMAL))
        assertNull(WakePhrase.matchAny(listOf("hey service", "a b c", "d e f", "hey jarvis"), WakeSensitivity.NORMAL)) // only next 2
        assertEquals("go", WakePhrase.matchAny(listOf("hey service go", "hey jarvis go"), WakeSensitivity.NORMAL)?.question)
    }
}

class SystemSpeechWakeEngineTest {
    private class SessionStt(private val sessions: ArrayDeque<List<SttEvent>>) : Stt {
        var listens = 0
        override fun isAvailable() = true
        override fun listen(preferOnDevice: Boolean): Flow<SttEvent> {
            listens++
            return flowOf(*(sessions.removeFirstOrNull() ?: emptyList()).toTypedArray())
        }
        override fun stopListening() = Unit
    }

    @Test fun keepsListeningUntilItHearsJarvisThenHandsOverTheQuestion() = runBlocking {
        val stt = SessionStt(ArrayDeque(listOf(
            listOf(SttEvent.Failed(SttError.NO_SPEECH)),
            listOf(SttEvent.Final("what a nice day")),
            listOf(SttEvent.Partial("hey jar"), SttEvent.Final("hey Jarvis what's my net worth")),
        )))
        val engine = SystemSpeechWakeEngine(stt, { true })
        val d = engine.detections(WakeSensitivity.NORMAL).first()
        assertEquals("what's my net worth", d.followUp)
        assertEquals(3, stt.listens)
    }
}

class EnergyGateTest {
    private fun chunk(level: Float) = FloatArray(1600) { if (it % 2 == 0) level else -level }

    @Test fun quietRoomRunsNothingAndSpeechReplaysThePreRoll() {
        val g = EnergyGate(minLevel = 0.01f, hangoverChunks = 3, preRollChunks = 2)
        repeat(5) { assertTrue(g.process(chunk(0.001f)).feed.isEmpty()) }
        val start = g.process(chunk(0.2f))
        assertEquals(3, start.feed.size) // 2 chunks of pre-roll + the loud one
        assertTrue(g.open)
    }

    @Test fun closesAfterTheHangoverAndAsksForAReset() {
        val g = EnergyGate(minLevel = 0.01f, hangoverChunks = 3, preRollChunks = 0)
        g.process(chunk(0.2f))
        assertFalse(g.process(chunk(0.001f)).closed)
        assertFalse(g.process(chunk(0.001f)).closed)
        assertTrue(g.process(chunk(0.001f)).closed)
        assertFalse(g.open)
    }

    @Test fun steadyBackgroundNoiseRaisesTheThreshold() {
        val g = EnergyGate(minLevel = 0.004f, hangoverChunks = 2, preRollChunks = 0)
        // A fan just under the trigger level for a while: the learned floor rises toward it.
        repeat(200) { g.process(chunk(0.006f)) }
        assertTrue(g.noiseFloor > 0.004f)
        assertTrue(g.process(chunk(0.009f)).feed.isEmpty()) // now below 3x the floor: still quiet
    }
}

class HeadlessAccessTest {
    private var now = 0L
    private val sessions = SessionManager { now }
    private var locked = false
    private var delegateCalls = 0
    private val delegate = AccessController { tools, _ -> delegateCalls++; AccessResult.Granted(tools, null, false, false) }
    private val access = HeadlessAccess(delegate, sessions, { SessionPolicy() }, { locked })
    private val netWorth = ToolRegistry.byName("networth_snapshot")!!
    private val watchlist = ToolRegistry.byName("user_watchlist")!!
    private val price = ToolRegistry.byName("get_indian_stocks_details")!!

    @Test fun openToolsWorkHandsFree() = runBlocking {
        locked = true
        assertTrue(access.authorize(listOf(price), null) is AccessResult.Granted)
    }

    @Test fun privateToolsNeedTheScreenWhenLockedOrWithoutASession() = runBlocking {
        val d = access.authorize(listOf(netWorth), null) as AccessResult.Denied
        assertEquals(DenyReason.NEEDS_SCREEN, d.reason)
        sessions.grant(AuthTier.SENSITIVE, AuthMethod.BIOMETRIC)
        locked = true
        assertEquals(DenyReason.NEEDS_SCREEN, (access.authorize(listOf(watchlist), null) as AccessResult.Denied).reason)
        assertEquals(0, delegateCalls) // never reaches the prompt-showing gate
    }

    @Test fun handsFreeSpokenPinUnlocksPrivateQuestionsWhenSetUp() = runBlocking {
        locked = true
        var asked: AuthMethod? = null
        val withPin = HeadlessAccess(
            AccessController { t, m -> asked = m; AccessResult.Granted(t, m, false, true) },
            sessions, { SessionPolicy() }, { locked }, spokenPinReady = { true },
        )
        val r = withPin.authorize(listOf(netWorth), null)
        assertTrue(r is AccessResult.Granted)
        assertEquals(AuthMethod.PIN, asked) // straight to the PIN: no screen for a fingerprint
    }

    @Test fun withoutSpokenPinPrivateQuestionsStillNeedTheScreen() = runBlocking {
        locked = true
        val noPin = HeadlessAccess(delegate, sessions, { SessionPolicy() }, { locked }, spokenPinReady = { false })
        assertEquals(DenyReason.NEEDS_SCREEN, (noPin.authorize(listOf(netWorth), null) as AccessResult.Denied).reason)
    }

    @Test fun aValidSessionOnAnUnlockedPhoneIsHonoured() = runBlocking {
        sessions.grant(AuthTier.SENSITIVE, AuthMethod.PIN)
        assertTrue(access.authorize(listOf(netWorth), null) is AccessResult.Granted)
        assertEquals(1, delegateCalls)
    }
}

/** Each listen plays the next scripted session; after the script, the user says nothing. */
private class ScriptedStt(vararg sessions: List<SttEvent>) : Stt {
    constructor(vararg events: SttEvent) : this(events.toList())
    private val queue = ArrayDeque(sessions.toList())
    var listens = 0
    override fun isAvailable() = true
    override fun listen(preferOnDevice: Boolean): Flow<SttEvent> {
        listens++
        return flowOf(*(queue.removeFirstOrNull() ?: listOf(SttEvent.Failed(SttError.NO_SPEECH))).toTypedArray())
    }
    override fun stopListening() = Unit
}

private class RecordingTts : Tts {
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking
    val spoken = mutableListOf<String>()
    override fun speak(text: String) { spoken += text } // finishes instantly
    override fun stop() = Unit
    override fun setRate(rate: Float) = Unit
}

private class OneReplyLlm(private val text: String) : LlmClient {
    val requests = mutableListOf<LlmRequest>()
    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        requests += request
        emit(LlmEvent.Update(StreamUpdate.TextDelta(text)))
        emit(LlmEvent.Completed(AssembledMessage("claude-haiku-5-5", emptyList(), "end_turn", null, Usage(10, 5)), 20))
    }
    override suspend fun testKey(apiKey: String) = Result.success(Unit)
}

/** Claude's routing: the first [handBacks] questions are handed back to the phone (private_data), then plain text. */
private class RoutingLlm(private val handBacks: Int, private val text: String) : LlmClient {
    val requests = mutableListOf<LlmRequest>()
    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        requests += request
        if (requests.size <= handBacks) {
            val use = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"type":"tool_use","id":"p${requests.size}","name":"private_data","input":{"kinds":["net_worth"]}}""",
            ) as kotlinx.serialization.json.JsonObject
            emit(LlmEvent.Completed(AssembledMessage("claude-haiku-5-5", listOf(use), "tool_use", null, Usage(10, 5)), 20))
        } else {
            emit(LlmEvent.Update(StreamUpdate.TextDelta(text)))
            emit(LlmEvent.Completed(AssembledMessage("claude-haiku-5-5", emptyList(), "end_turn", null, Usage(10, 5)), 20))
        }
    }
    override suspend fun testKey(apiKey: String) = Result.success(Unit)
}

private object SignedInIndMoney : Connector {
    override val id = ToolRegistry.INDMONEY
    override val displayName = "INDmoney"
    override val status = flowOf(ConnectorStatus.Connected)
    override suspend fun callTool(name: String, arguments: kotlinx.serialization.json.JsonObject) =
        dev.jarvis.connectors.ConnectorCall.Ok("""{"networth":1240000}""", toolError = false)
    override suspend fun toolDefinitions(names: Collection<String>) = emptyList<dev.jarvis.connectors.McpToolDef>()
    override suspend fun test() = ConnectorTestResult.Ok
    override suspend fun signOut() = Unit
}

class VoiceConversationTest {
    private val db = testDb()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val history = HistoryStore(db.history())
    private val settings = SettingsRepository(tempDataStore(scope))

    @After fun tearDown() { scope.cancel(); db.close() }

    @Test fun handsFreeQuestionIsAnsweredOutLoudAndSaved() = runBlocking {
        val tts = RecordingTts()
        val llm = OneReplyLlm("Paris is the capital of France. ")
        val agent = AgentLoop(llm, emptyMap(), tempUsageMeter(db), AccessController { t, _ -> AccessResult.Granted(t, null, false, false) })
        val conversation = VoiceConversation(
            ScriptedStt(SttEvent.Partial("capital of"), SttEvent.Final("what's the capital of France")),
            tts, agent, history, settings,
        ) { false }

        val question = conversation.runOnce(this)

        assertEquals("what's the capital of France", question)
        assertEquals(listOf("Paris is the capital of France."), tts.spoken)
        assertEquals(WakePhase.DONE, conversation.phase.value)
        val saved = history.conversations().first().single()
        assertEquals(2, saved.messageCount)
    }

    @Test fun conversationContinuesWithContextUntilTheUserSaysStop() = runBlocking {
        val tts = RecordingTts()
        val llm = OneReplyLlm("Did you mean your net worth? ")
        val agent = AgentLoop(llm, emptyMap(), tempUsageMeter(db), AccessController { t, _ -> AccessResult.Granted(t, null, false, false) })
        val stt = ScriptedStt(
            listOf(SttEvent.Final("what's my worth")),
            listOf(SttEvent.Final("yes that one")),
            listOf(SttEvent.Final("thanks Jarvis")),
        )
        val conversation = VoiceConversation(stt, tts, agent, history, settings) { false }

        conversation.runOnce(this)

        assertEquals(2, llm.requests.size) // "thanks" ends it without another request
        // The follow-up carries the first exchange as context.
        val second = llm.requests[1].toString()
        assertTrue(second.contains("what's my worth") && second.contains("yes that one"))
        assertEquals(1, history.conversations().first().size) // one conversation, not two
        assertEquals(4, history.conversations().first().single().messageCount)
        assertEquals(WakePhase.DONE, conversation.phase.value)
    }

    @Test fun conversationSleepsAfter15SecondsOfSilence() = runBlocking {
        val tts = RecordingTts()
        val llm = OneReplyLlm("It's sunny. ")
        val agent = AgentLoop(llm, emptyMap(), tempUsageMeter(db), AccessController { t, _ -> AccessResult.Granted(t, null, false, false) })
        val stt = ScriptedStt(listOf(SttEvent.Final("how's the weather")))
        val conversation = VoiceConversation(stt, tts, agent, history, settings) { false }

        conversation.runOnce(this)

        assertEquals(1, llm.requests.size)
        // 1 question + 3 silent attempts of ~5 s = 15 s, then sleep.
        assertEquals(4, stt.listens)
        assertEquals(WakePhase.DONE, conversation.phase.value)
    }

    @Test fun goodbyePhrases() {
        listOf("stop", "That's all.", "thanks Jarvis", "okay bye", "go to sleep", "never mind", "Thank you so much").forEach {
            assertTrue(it, Conversation.isGoodbye(it))
        }
        listOf("stop the music", "thanks, what's the time", "what's my net worth", "nothing much happened today").forEach {
            assertFalse(it, Conversation.isGoodbye(it))
        }
    }

    @Test fun privateQuestionInAPocketIsRefusedOnThePhone() = runBlocking {
        val tts = RecordingTts()
        val llm = RoutingLlm(handBacks = 1, text = "should not happen")
        val headless = HeadlessAccess(
            AccessController { t, _ -> AccessResult.Granted(t, null, false, true) },
            SessionManager(), { SessionPolicy() }, { true },
        )
        val agent = AgentLoop(llm, mapOf(ToolRegistry.INDMONEY to SignedInIndMoney), tempUsageMeter(db), headless)
        val conversation = VoiceConversation(ScriptedStt(SttEvent.Final("what's my net worth")), tts, agent, history, settings) { true }

        conversation.runOnce(this)

        assertEquals(1, llm.requests.size) // Claude only routed it
        assertEquals(listOf(VoiceConversation.NEEDS_SCREEN_LINE), tts.spoken)
    }

    @Test fun portfolioAnswerIsNeverSentAsContextToClaude() = runBlocking {
        val tts = RecordingTts()
        val llm = RoutingLlm(handBacks = 1, text = "Paris. ")
        val agent = AgentLoop(
            llm, mapOf(ToolRegistry.INDMONEY to SignedInIndMoney), tempUsageMeter(db),
            AccessController { t, _ -> AccessResult.Granted(t, dev.jarvis.security.AuthMethod.PIN, false, true) },
        )
        val stt = ScriptedStt(
            listOf(SttEvent.Final("what's my net worth")),
            listOf(SttEvent.Final("what's the capital of France")),
            listOf(SttEvent.Final("thanks")),
        )
        VoiceConversation(stt, tts, agent, history, settings) { false }.runOnce(this)

        assertEquals(2, llm.requests.size) // a routing call per question; the net worth itself was made on the phone
        assertTrue(tts.spoken.first().contains("12.4 lakh"))
        val sent = llm.requests.joinToString { it.body["messages"].toString() }
        assertFalse(sent, sent.contains("lakh") || sent.contains("1240000"))
        assertTrue(llm.requests[1].body["messages"].toString().contains(dev.jarvis.agent.AgentLoop.PRIVATE_ANSWER))
    }

    @Test fun silenceEndsQuietly() = runBlocking {
        val tts = RecordingTts()
        val llm = OneReplyLlm("x")
        val agent = AgentLoop(llm, emptyMap(), tempUsageMeter(db), AccessController { t, _ -> AccessResult.Granted(t, null, false, false) })
        val conversation = VoiceConversation(ScriptedStt(SttEvent.Failed(SttError.NO_SPEECH)), tts, agent, history, settings) { false }

        assertNull(conversation.runOnce(this))
        assertTrue(llm.requests.isEmpty() && tts.spoken.isEmpty())
        assertEquals(WakePhase.DONE, conversation.phase.value)
    }
}

class WakeAckTest {
    @org.junit.Test fun chimeOnly() {
        org.junit.Assert.assertEquals(AckPlan(CueSound.CHIME, null), AckPlan.of(WakeAck.CHIME, "Yes?", preheard = false, speakReplies = true))
    }
    @org.junit.Test fun silentPlaysNothing() {
        org.junit.Assert.assertEquals(AckPlan(null, null), AckPlan.of(WakeAck.OFF, "Yes?", false, true))
    }
    @org.junit.Test fun phraseSpokenWhenWaitingForQuestion() {
        org.junit.Assert.assertEquals(AckPlan(null, "Hi"), AckPlan.of(WakeAck.VOICE, " Hi ", false, true))
        org.junit.Assert.assertEquals(AckPlan(CueSound.CHIME, "Yes?"), AckPlan.of(WakeAck.CHIME_VOICE, "Yes?", false, true))
    }
    @org.junit.Test fun noPhraseWhenQuestionAlreadyHeardOrQuiet() {
        org.junit.Assert.assertEquals(AckPlan(CueSound.CHIME, null), AckPlan.of(WakeAck.VOICE, "Yes?", preheard = true, speakReplies = true))
        org.junit.Assert.assertEquals(AckPlan(CueSound.CHIME, null), AckPlan.of(WakeAck.VOICE, "Yes?", preheard = false, speakReplies = false))
        org.junit.Assert.assertEquals(AckPlan(CueSound.CHIME, null), AckPlan.of(WakeAck.CHIME_VOICE, "Yes?", preheard = true, speakReplies = true))
        org.junit.Assert.assertEquals(AckPlan(CueSound.CHIME, null), AckPlan.of(WakeAck.VOICE, "  ", false, true))
    }
    @org.junit.Test fun soundsAreShortAndNotClipped() {
        for (pcm in listOf(CueSynth.chime(), CueSynth.hum())) {
            org.junit.Assert.assertTrue(pcm.size in CueSynth.RATE / 5..CueSynth.RATE)
            org.junit.Assert.assertTrue(pcm.maxOf { kotlin.math.abs(it.toInt()) } < Short.MAX_VALUE)
            org.junit.Assert.assertTrue(kotlin.math.abs(pcm.last().toInt()) < 2000)
        }
    }
}

class WakeTrainingTest {
    @org.junit.Test fun nBestAlternativeWakes() {
        org.junit.Assert.assertEquals("what time is it",
            WakePhrase.matchAny(listOf("hey service what time is it", "hey jarvis what time is it"), WakeSensitivity.NORMAL)?.question)
    }
    @org.junit.Test fun nearSpellingsWake() {
        org.junit.Assert.assertEquals("", WakePhrase.match("hey jarviz", WakeSensitivity.STRICT))
        org.junit.Assert.assertEquals("", WakePhrase.match("hi jarvi", WakeSensitivity.NORMAL))
        org.junit.Assert.assertNull(WakePhrase.match("hey java", WakeSensitivity.NORMAL))
        org.junit.Assert.assertNull(WakePhrase.match("hey travis", WakeSensitivity.NORMAL))
    }
    @org.junit.Test fun learnsMisheardPhrasesButNotNoise() {
        val learned = WakeTraining.learn(listOf(
            listOf("hey service", "hey jarvis", "hey Travis"),
            listOf("hey", "a service"),
            listOf("this is a much longer sentence"),
        ))
        org.junit.Assert.assertEquals(listOf("hey service", "hey travis", "a service"), learned)
    }
    @org.junit.Test fun learnedPhraseWakesAsWholePhraseOnly() {
        val learned = listOf("hey service", "travis")
        org.junit.Assert.assertEquals("lights on", WakePhrase.match("Hey service, lights on", WakeSensitivity.STRICT, learned))
        org.junit.Assert.assertNull(WakePhrase.match("customer service", WakeSensitivity.STRICT, learned))
        org.junit.Assert.assertNull(WakePhrase.match("call hey service now", WakeSensitivity.NORMAL, learned)) // not at the start
        // Single-word learned phrases only on Sensitive.
        org.junit.Assert.assertNull(WakePhrase.match("travis", WakeSensitivity.NORMAL, learned))
        org.junit.Assert.assertEquals("", WakePhrase.match("travis", WakeSensitivity.SENSITIVE, learned))
    }
    @org.junit.Test fun understoodCountsTopGuessOnly() {
        org.junit.Assert.assertEquals(1, WakeTraining.understood(listOf(listOf("hey jarvis"), listOf("hey service", "hey jarvis"))))
    }
}
