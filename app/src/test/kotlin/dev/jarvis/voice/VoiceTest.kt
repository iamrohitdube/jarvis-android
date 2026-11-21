package dev.jarvis.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceChunkerTest {
    @Test fun emitsEachSentenceOnceItIsComplete() {
        val c = SentenceChunker()
        assertTrue(c.feed("Your net worth is about ").isEmpty())
        assertTrue(c.feed("12.4 lakh").isEmpty()) // the decimal point isn't a sentence end
        assertEquals(listOf("Your net worth is about 12.4 lakh."), c.feed(". It rose"))
        assertEquals(emptyList<String>(), c.feed(" 2% this week!"))
        assertEquals(listOf("It rose 2% this week!"), c.flush())
    }

    @Test fun questionMarksNewlinesAndQuotes() {
        val c = SentenceChunker()
        assertEquals(listOf("Want details?"), c.feed("Want details? Say"))
        assertEquals(listOf("Say \"more\"."), c.feed(" \"more\". Next"))
        assertEquals(listOf("Next line"), c.feed(" line\nAnd"))
        assertEquals(listOf("And"), c.flush())
    }

    @Test fun abbreviationsAndInitialsDontSplit() {
        val c = SentenceChunker()
        val out = c.feed("Funds e.g. index funds cost less vs. active ones. Dr. Rao and J. Smith agree. ") + c.flush()
        assertEquals(listOf("Funds e.g. index funds cost less vs. active ones.", "Dr. Rao and J. Smith agree."), out)
    }

    @Test fun veryLongTextIsSpokenInChunks() {
        val c = SentenceChunker(maxChars = 40)
        val out = c.feed("word ".repeat(30))
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.length <= 40 })
    }

    @Test fun markdownIsCleanedBeforeSpeaking() {
        val c = SentenceChunker()
        assertEquals(listOf("Total: 12 lakh."), c.feed("**Total**: 12 lakh. "))
    }
}

class SpeechTextTest {
    @Test fun dropsMarkdownAndUrls() {
        assertEquals("See the guide for more", SpeechText.clean("See [the guide](https://x.y/z) for more"))
        assertEquals("Open link now", SpeechText.clean("Open https://example.com/a?b=c now"))
        assertEquals("Equity 60% Debt 40%", SpeechText.clean("- Equity 60%\n- Debt 40%"))
        assertEquals("Summary", SpeechText.clean("## Summary"))
        assertEquals("a , b", SpeechText.clean("a | b"))
    }
}

class SpokenChoiceTest {
    @Test fun understandsCommonAnswers() {
        assertEquals(SpokenChoice.Choice.PIN, SpokenChoice.parse("PIN please"))
        assertEquals(SpokenChoice.Choice.PIN, SpokenChoice.parse("use my pin"))
        assertEquals(SpokenChoice.Choice.FINGERPRINT, SpokenChoice.parse("Fingerprint."))
        assertEquals(SpokenChoice.Choice.FINGERPRINT, SpokenChoice.parse("my thumb"))
        assertNull(SpokenChoice.parse("umm"))
        assertNull(SpokenChoice.parse("pin or fingerprint")) // ambiguous: default applies
        assertNull(SpokenChoice.parse("spinach")) // "pin" inside a word doesn't count
    }
}

private class FakeTts : Tts {
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking
    val spoken = mutableListOf<String>()
    var stops = 0
    override fun speak(text: String) { spoken += text; _speaking.value = true }
    override fun stop() { stops++; _speaking.value = false }
    override fun setRate(rate: Float) = Unit
    fun finishSpeaking() { _speaking.value = false }
}

private class FakeStt : Stt {
    var events = Channel<SttEvent>(Channel.UNLIMITED)
    var stopRequests = 0
    override fun isAvailable() = true
    override fun listen(preferOnDevice: Boolean): Flow<SttEvent> = events.consumeAsFlow()
    override fun stopListening() { stopRequests++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionTest {
    private val tts = FakeTts()
    private val stt = FakeStt()
    private var locked = false

    private fun TestScope.session() = VoiceSession(stt, tts, backgroundScope) { locked }.also { runCurrent() }

    @Test fun voiceTurnIsSpokenSentenceBySentence() = runTest {
        val v = session()
        v.beginTurn(voice = true)
        v.onTextDelta("About 12 lakh. Up ")
        assertEquals(listOf("About 12 lakh."), tts.spoken)
        v.onTextDelta("2% this week.")
        v.onFinished()
        assertEquals(listOf("About 12 lakh.", "Up 2% this week."), tts.spoken)
        runCurrent()
        assertEquals(VoiceState.SPEAKING, v.state.value)
        tts.finishSpeaking(); runCurrent()
        assertEquals(VoiceState.IDLE, v.state.value)
    }

    @Test fun typedTurnsAndQuietModeAreSilent() = runTest {
        val v = session()
        v.beginTurn(voice = false)
        v.onTextDelta("Hello there. "); v.onFinished()
        v.speakReplies = false
        v.beginTurn(voice = true)
        v.onTextDelta("Hello there. "); v.onFinished()
        assertTrue(tts.spoken.isEmpty())
    }

    @Test fun fingerprintAnswersAreNeverReadOut() = runTest {
        val v = session()
        v.beginTurn(voice = true)
        v.onAuthorized(screenOnly = true, sensitive = true)
        v.onTextDelta("Your net worth is 12 lakh. ")
        v.onFinished()
        assertEquals(listOf(VoiceSession.SCREEN_ONLY_LINE), tts.spoken)
    }

    @Test fun sensitiveRepliesAreNotSpokenWhileLocked() = runTest {
        locked = true
        val v = session()
        v.beginTurn(voice = true)
        v.onAuthorized(screenOnly = false, sensitive = true)
        v.onTextDelta("Your net worth is 12 lakh. ")
        v.onFinished()
        assertEquals(listOf(VoiceSession.LOCKED_LINE), tts.spoken)
    }

    @Test fun pinAuthorisedSensitiveRepliesAreSpokenWhenUnlocked() = runTest {
        val v = session()
        v.beginTurn(voice = true)
        v.onAuthorized(screenOnly = false, sensitive = true)
        v.onTextDelta("About 12 lakh. ")
        assertEquals(listOf("About 12 lakh."), tts.spoken)
    }

    @Test fun bargeInStopsSpeechAndDropsTheRest() = runTest {
        val v = session()
        v.beginTurn(voice = true)
        v.onTextDelta("First. Second")
        v.bargeIn()
        v.onTextDelta(" part. Third. ")
        v.onFinished()
        assertEquals(listOf("First."), tts.spoken)
        assertTrue(tts.stops >= 1)
        assertFalse(v.voiceTurn)
    }

    @Test fun listeningShowsPartialsAndDeliversTheFinalText() = runTest {
        val v = session()
        tts.speak("old reply")
        var heard: String? = null
        v.startListening { heard = it }
        assertEquals(1, tts.stops) // starting to listen barges in on speech
        stt.events.send(SttEvent.Partial("what's my"))
        runCurrent()
        assertEquals(VoiceState.LISTENING, v.state.value)
        assertEquals("what's my", v.partial.value)
        stt.events.send(SttEvent.Final("what's my net worth"))
        stt.events.close()
        runCurrent()
        assertEquals("what's my net worth", heard)
        assertEquals(VoiceState.THINKING, v.state.value)
    }

    @Test fun recognitionErrorsAreReported() = runTest {
        val v = session()
        v.startListening { }
        stt.events.send(SttEvent.Failed(SttError.NO_SPEECH)); stt.events.close()
        runCurrent()
        assertEquals(SttError.NO_SPEECH, v.error.value)
        assertEquals(VoiceState.IDLE, v.state.value)
        v.clearError()
        assertNull(v.error.value)
    }

    @Test fun askSpeaksTheQuestionThenListensForTheAnswer() = runTest {
        val v = session()
        v.beginTurn(voice = true)
        var answer: String? = null
        v.ask(VoiceSession.CHOICE_QUESTION) { answer = it }
        runCurrent()
        assertEquals(listOf(VoiceSession.CHOICE_QUESTION), tts.spoken)
        assertTrue(v.state.value != VoiceState.LISTENING) // waits until the question has been spoken
        tts.finishSpeaking(); runCurrent()
        assertEquals(VoiceState.LISTENING, v.state.value)
        stt.events.send(SttEvent.Final("fingerprint")); stt.events.close()
        runCurrent()
        assertEquals("fingerprint", answer)
    }

    @Test fun askIsSkippedInQuietModeOrTypedTurns() = runTest {
        val v = session()
        v.beginTurn(voice = false)
        v.ask(VoiceSession.CHOICE_QUESTION) { }
        v.speakReplies = false
        v.beginTurn(voice = true)
        v.ask(VoiceSession.CHOICE_QUESTION) { }
        assertTrue(tts.spoken.isEmpty())
    }
}

class SpokenChoiceMoreTest {
    @org.junit.Test fun commonMishearingsOfPin() {
        listOf("ping", "pins", "P I N", "the pin please", "pen").forEach {
            org.junit.Assert.assertEquals(it, SpokenChoice.Choice.PIN, SpokenChoice.parse(it))
        }
    }

    @org.junit.Test fun checksEveryGuess() {
        org.junit.Assert.assertEquals(SpokenChoice.Choice.PIN, SpokenChoice.parseAny(listOf("being", "pin")))
        org.junit.Assert.assertEquals(null, SpokenChoice.parseAny(listOf("hmm", "what")))
    }

    @org.junit.Test fun answerWindowIsElevenSeconds() {
        org.junit.Assert.assertEquals(11_000L, dev.jarvis.security.AuthCoordinator.CHOICE_TIMEOUT_MS)
    }
}


class LockedPinSpeechTest {
    private class SilentStt : Stt {
        override fun isAvailable() = true
        override fun listen(preferOnDevice: Boolean) = kotlinx.coroutines.flow.emptyFlow<SttEvent>()
        override fun stopListening() = Unit
    }
    private class Rec : Tts {
        override val speaking = kotlinx.coroutines.flow.MutableStateFlow(false)
        val spoken = mutableListOf<String>()
        override fun speak(text: String) { spoken += text }
        override fun stop() = Unit
        override fun setRate(rate: Float) = Unit
    }

    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    @org.junit.After fun tearDown() { bg.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    @org.junit.Test fun pinUnlockedAnswerIsSpokenOnALockedPhone() {
        val tts = Rec()
        val v = VoiceSession(SilentStt(), tts, bg, deviceLocked = { true })
        v.beginTurn(voice = true)
        v.onAuthorized(screenOnly = false, sensitive = true, byPin = true)
        v.onTextDelta("Your net worth is 12 lakh. ")
        v.onFinished()
        org.junit.Assert.assertEquals(listOf("Your net worth is 12 lakh."), tts.spoken)
    }

    @org.junit.Test fun withoutPinASensitiveAnswerIsNotSpokenWhenLocked() {
        val tts = Rec()
        val v = VoiceSession(SilentStt(), tts, bg, deviceLocked = { true })
        v.beginTurn(voice = true)
        v.onAuthorized(screenOnly = false, sensitive = true, byPin = false)
        v.onTextDelta("Your net worth is 12 lakh. ")
        v.onFinished()
        org.junit.Assert.assertEquals(listOf(VoiceSession.LOCKED_LINE), tts.spoken)
    }
}
