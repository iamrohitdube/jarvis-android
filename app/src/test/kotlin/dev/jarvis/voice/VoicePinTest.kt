package dev.jarvis.voice

import dev.jarvis.FakeSecretStore
import dev.jarvis.agent.ToolRegistry
import dev.jarvis.data.AuditSink
import dev.jarvis.data.SettingsRepository
import dev.jarvis.security.AccessResult
import dev.jarvis.security.AuthCoordinator
import dev.jarvis.security.AuthMethod
import dev.jarvis.security.AuthPrompt
import dev.jarvis.security.BiometricStatus
import dev.jarvis.security.PinAuthenticator
import dev.jarvis.security.PinHasher
import dev.jarvis.security.SecuritySettings
import dev.jarvis.security.SessionManager
import dev.jarvis.security.SpokenPin
import dev.jarvis.security.StrongAuthProof
import dev.jarvis.tempDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class PinSpeechTest {
    @Test fun spokenDigitsInManyForms() {
        assertEquals("2580", PinSpeech.digits("two five eight zero"))
        assertEquals("2580", PinSpeech.digits("2580"))
        assertEquals("2580", PinSpeech.digits("2 5 8 0."))
        assertEquals("2580", PinSpeech.digits("Two-five, eight, oh"))
        assertEquals("5519", PinSpeech.digits("double five one nine"))
        assertEquals("2580", PinSpeech.digits("My PIN is two five eight zero"))
        assertEquals("1234", PinSpeech.digits("one to three for")) // common mishearings
    }

    @Test fun leadInAndPoliteWordsAreIgnored() {
        assertEquals("2580", PinSpeech.digits("I said two five eight zero"))
        assertEquals("2580", PinSpeech.digits("so 2580 please"))
        assertEquals("2580", PinSpeech.digits("Oh, my PIN is 2 5 8 0, thanks"))
        assertEquals("0258", PinSpeech.digits("oh two five eight"))   // "oh" before a digit is zero
        assertEquals("2580", PinSpeech.digits("okay it's 2580 that's it"))
    }

    @Test fun messyAnswersAreUnclearNotWrong() {
        assertNull(PinSpeech.digits("two five banana eight zero")) // junk inside the digits
        assertNull(PinSpeech.digits("show my last 4 transactions"))
        assertNull(PinSpeech.digits("2580 and then some"))
    }

    @Test fun anythingElseIsUnclear() {
        assertNull(PinSpeech.digits("what's my net worth"))
        assertNull(PinSpeech.digits("two five"))          // too short
        assertNull(PinSpeech.digits("1 2 3 4 5 6 7 8 9")) // too long
        assertNull(PinSpeech.digits(""))
    }
}

class VoicePrintMathTest {
    @Test fun cosineAndAveraging() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0.9f, 0.1f, 0f)
        val c = floatArrayOf(0f, 1f, 0f)
        assertTrue(VoicePinEngine.cosine(a, b) > 0.98f)
        assertEquals(0f, VoicePinEngine.cosine(a, c), 1e-6f)
        val n = VoicePinEngine.normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, n[0], 1e-6f); assertEquals(0.8f, n[1], 1e-6f)
        val avg = VoicePinEngine.average(listOf(floatArrayOf(1f, 3f), floatArrayOf(3f, 5f)))
        assertEquals(listOf(2f, 4f), avg.toList())
        assertEquals(0f, VoicePinEngine.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)), 0f) // size mismatch
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoicePinFlowTest {
    private class FakeSource(var isReady: Boolean, var next: SpokenPin) : SpokenPinSource {
        var captures = 0
        override suspend fun ready() = isReady
        override suspend fun capturePin(): SpokenPin { captures++; return next }
    }

    private class InstantTts : Tts {
        private val _speaking = MutableStateFlow(false)
        override val speaking: StateFlow<Boolean> = _speaking
        val spoken = mutableListOf<String>()
        override fun speak(text: String) { spoken += text }
        override fun stop() = Unit
        override fun setRate(rate: Float) = Unit
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @After fun tearDown() = scope.cancel()

    private val secrets = FakeSecretStore()
    private val pin = PinAuthenticator(secrets, PinHasher(1_000), EmptyCoroutineContext)
    private val sessions = SessionManager()
    private val auth = AuthCoordinator({ SecuritySettings() }, sessions, pin, { BiometricStatus.AVAILABLE }, AuditSink.NONE)
    private val settings = SettingsRepository(tempDataStore(scope))
    private val netWorth = ToolRegistry.byName("networth_snapshot")!!

    @Test fun saysPinThenUnlocksWithTheOwnersVoice() = kotlinx.coroutines.runBlocking {
        pin.setPin("2580", "2580", StrongAuthProof(AuthMethod.BIOMETRIC), deviceSecure = true)
        val tts = InstantTts()
        val source = FakeSource(isReady = true, next = SpokenPin("2580", voiceMatched = true))
        val flow = VoicePinFlow(source, tts, auth, settings)
        val collector = scope.launch { auth.prompt.collect { flow.onPrompt(it, voiceTurn = true) } }
        val result = kotlinx.coroutines.withTimeout(15_000) { auth.authorize(listOf(netWorth), AuthMethod.PIN) }
        assertTrue(result is AccessResult.Granted)
        assertEquals(listOf(VoicePinFlow.SAY_PIN), tts.spoken)
        assertEquals(1, source.captures)
        collector.cancel()
    }

    @Test fun saysWhyWhenSpokenPinIsNotSetUpAndStaysQuietOutsideVoiceTurns() = runTest {
        val tts = InstantTts()
        val notReady = FakeSource(isReady = false, next = SpokenPin("2580", true))
        val prompt = AuthPrompt(1, AuthPrompt.Stage.PIN, dev.jarvis.agent.AuthTier.SENSITIVE, true, false, true)
        val flow = VoicePinFlow(notReady, tts, auth, settings)
        flow.onPrompt(prompt, voiceTurn = true)
        flow.onPrompt(prompt.copy(id = 2), voiceTurn = true) // said once, not on every retry
        assertEquals(listOf(VoicePinFlow.NOT_SET_UP), tts.spoken)
        val quiet = InstantTts()
        val ready = FakeSource(isReady = true, next = SpokenPin("2580", true))
        VoicePinFlow(ready, quiet, auth, settings).onPrompt(prompt, voiceTurn = false)
        assertEquals(0, notReady.captures + ready.captures)
        assertTrue(quiet.spoken.isEmpty())
    }

    @Test fun givesUpSpeakingAfterThreeTries() = runTest {
        val tts = InstantTts()
        val source = FakeSource(isReady = true, next = SpokenPin(null, true))
        val flow = VoicePinFlow(source, tts, auth, settings, maxTries = 3)
        for (id in 1L..5L) {
            flow.onPrompt(AuthPrompt(id, AuthPrompt.Stage.PIN, dev.jarvis.agent.AuthTier.SENSITIVE, true, false, true), voiceTurn = true)
        }
        assertEquals(3, source.captures)
        assertEquals(VoicePinFlow.GIVE_UP, tts.spoken.last()) // then tells the user to type it or use fingerprint
    }
}
