package dev.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake networks that record what the pipeline feeds them. */
private class FakeOww(private val scoreOf: (List<FloatArray>) -> Float = { 0.9f }) : OwwModels {
    val melInputs = mutableListOf<FloatArray>()
    val embedWindows = mutableListOf<List<FloatArray>>()
    var classifyCalls = 0
    override fun mel(samples: FloatArray): Array<FloatArray> {
        melInputs += samples.copyOf()
        // ceil(n/160 - 3) frames, like the real model; each frame holds the chunk's last sample value.
        val frames = kotlin.math.ceil(samples.size / 160.0 - 3).toInt()
        return Array(frames) { FloatArray(32) { samples.last() } }
    }
    override fun embed(window: List<FloatArray>): FloatArray { embedWindows += window.map { it.copyOf() }; return FloatArray(96) { window.last()[0] } }
    override fun classify(features: List<FloatArray>): Float { classifyCalls++; return scoreOf(features) }
    override fun close() = Unit
}

class OwwStreamTest {
    @Test fun eachStepFeeds1760SamplesWith480OfContext() {
        val m = FakeOww()
        val s = OwwStream(m)
        m.melInputs.clear()
        s.accept(FloatArray(1280) { 1f })
        s.accept(FloatArray(1280) { 2f })
        assertEquals(1760, m.melInputs[0].size)
        // Second step: the first 480 samples are the end of the previous chunk.
        assertTrue(m.melInputs[1].take(480).all { it == 1f })
        assertTrue(m.melInputs[1].drop(480).all { it == 2f })
    }

    @Test fun scoresOncePer80msAndBuffersPartialChunks() {
        val s = OwwStream(FakeOww())
        assertEquals(0, s.accept(FloatArray(1000)).size)
        assertEquals(1, s.accept(FloatArray(280)).size)
        assertEquals(2, s.accept(FloatArray(2560)).size)
    }

    @Test fun melTransformAndWindowOf76() {
        val m = FakeOww()
        val s = OwwStream(m)
        m.embedWindows.clear()
        s.accept(FloatArray(1280) { 30f })
        val window = m.embedWindows.last()
        assertEquals(76, window.size)
        assertEquals(30f / 10f + 2f, window.last()[0], 1e-6f) // x/10 + 2
    }

    @Test fun firstFiveScoresAreZeroLikeTheReference() {
        val s = OwwStream(FakeOww { 0.9f })
        val scores = s.accept(FloatArray(1280 * 7))
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0.9f, 0.9f), scores)
    }

    @Test fun resetRestoresStartStateWithoutWarmupDelay() {
        val s = OwwStream(FakeOww { 0.9f })
        s.accept(FloatArray(1280 * 3))
        s.reset()
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0.9f), s.accept(FloatArray(1280 * 6)))
    }
}

class OwwDetectorTest {
    @Test fun normalFiresOnFirstScoreOverHalf() {
        val d = OwwDetector.forSensitivity(WakeSensitivity.NORMAL)
        assertFalse(d.offer(0.49f))
        assertTrue(d.offer(0.51f))
    }

    @Test fun strictNeedsTwoConsecutiveHighScores() {
        val d = OwwDetector.forSensitivity(WakeSensitivity.STRICT)
        assertFalse(d.offer(0.8f))
        assertFalse(d.offer(0.2f))
        assertFalse(d.offer(0.8f))
        assertTrue(d.offer(0.75f))
    }

    @Test fun oneUtteranceFiresOnce() {
        val d = OwwDetector(0.5f, 1, refractorySteps = 25)
        assertTrue(d.offer(0.9f))
        repeat(25) { assertFalse(d.offer(0.9f)) }
        assertTrue(d.offer(0.9f))
    }

    @Test fun bestScoreIsReportedAndCleared() {
        val d = OwwDetector(0.5f, 1)
        d.offer(0.2f); d.offer(0.4f)
        assertEquals(0.4f, d.takeBest(), 0f)
        assertEquals(0f, d.takeBest(), 0f)
    }
}

class FollowUpCaptureTest {
    private fun chunk(level: Short) = ShortArray(1280) { if (it % 2 == 0) level else (-level).toShort() }
    private val loud: Short = 3000   // ~0.09 rms
    private val quiet: Short = 10

    @Test fun pauseAfterWakeMeansNoFollowUp() {
        val c = FollowUpCapture(speechLevel = 0.01f)
        var n = 0
        while (!c.done) { c.offer(chunk(quiet)); n++ }
        assertEquals(null, c.audio())
        assertTrue(n * 80 <= 1500) // gives up within ~1.4 s (0.8 s wake-sound window + 0.6 s)
    }

    @Test fun tailOfJarvisIsIgnored() {
        val c = FollowUpCapture(speechLevel = 0.01f)
        repeat(3) { c.offer(chunk(loud)) } // first 240 ms: "...vis"
        while (!c.done) c.offer(chunk(quiet))
        assertEquals(null, c.audio())
    }

    @Test fun sameBreathQuestionIsCapturedUntilSilence() {
        val c = FollowUpCapture(speechLevel = 0.01f)
        repeat(3) { c.offer(chunk(quiet)) }
        repeat(15) { c.offer(chunk(loud)) } // 1.2 s of "what's the time"
        var quietChunks = 0
        while (!c.done) { c.offer(chunk(quiet)); quietChunks++ }
        assertEquals(8, quietChunks) // 640 ms of silence ends it
        assertEquals((3 + 15 + 8) * 1280, c.audio()!!.size)
    }

    @Test fun steadyBackgroundNoiseStillEnds() {
        val fan: Short = 450   // ~0.014 rms, like the room in the device test
        val c = FollowUpCapture(speechLevel = 0.002f, initialAmbient = 0.014f)
        repeat(3) { c.offer(chunk(fan)) }
        repeat(15) { c.offer(chunk(loud)) }
        var n = 0
        while (!c.done) { c.offer(chunk(fan)); n++ }
        assertTrue("ended after ${n * 80} ms", n <= 10)
        assertTrue(c.audio() != null)
    }

    @Test fun backgroundNoiseAloneIsNotAFollowUp() {
        val fan: Short = 450
        val c = FollowUpCapture(speechLevel = 0.002f, initialAmbient = 0.014f)
        while (!c.done) c.offer(chunk(fan))
        assertEquals(null, c.audio())
    }

    @Test fun stopsAtEightSeconds() {
        val c = FollowUpCapture(speechLevel = 0.01f)
        var n = 0
        while (!c.done) { c.offer(chunk(loud)); n++ }
        assertEquals(100, n)
    }
}

class FollowUpTextTest {
    @Test fun keepsTheQuestion() {
        assertEquals("what's the time", FollowUp.clean("what's the time"))
        assertEquals("what's the time", FollowUp.clean("vis what's the time"))
        assertEquals("what's my net worth", FollowUp.clean("Jarvis, what's my net worth?"))
    }

    @Test fun dropsFragments() {
        assertEquals(null, FollowUp.clean("is"))
        assertEquals(null, FollowUp.clean("Jarvis"))
        assertEquals(null, FollowUp.clean(""))
        assertEquals(null, FollowUp.clean(null))
    }
}

class OwwVerifierTest {
    private val rnd = java.util.Random(3)
    /** Fake feature windows: "user saying hey jarvis" vs other speech differ along a few hidden directions. */
    private fun sample(user: Boolean) = FloatArray(OwwVerifier.DIM) { j ->
        val signal = if (j % 97 == 0) (if (user) 1.2f else -1.2f) else 0f
        signal + rnd.nextGaussian().toFloat()
    }

    @Test fun learnsTheUsersWakeAndRejectsOtherSpeech() {
        val pos = List(24) { sample(true) }
        val neg = List(150) { sample(false) }
        val r = OwwVerifierTrainer.train(pos, neg, userPeakScores = listOf(0.08f, 0.4f, 0.2f))
        assertTrue("accepted ${r.positivesAccepted}/${r.positives}", r.positivesAccepted >= 22)
        assertTrue("rejected ${r.negativesRejected}/${r.negatives}", r.negativesRejected >= 145)
        // Held-out samples
        val heldPos = List(20) { sample(true) }.count { r.verifier.probability(listOf(it)) >= 0.5f }
        val heldNeg = List(100) { sample(false) }.count { r.verifier.probability(listOf(it)) < 0.5f }
        assertTrue("held-out accepted $heldPos/20", heldPos >= 17)
        assertTrue("held-out rejected $heldNeg/100", heldNeg >= 95)
    }

    @Test fun candidateThresholdFollowsTheUsersWeakestWake() {
        val r = OwwVerifierTrainer.train(List(4) { sample(true) }, List(20) { sample(false) }, listOf(0.08f, 0.5f))
        assertEquals(0.05f, r.verifier.candidateThreshold, 1e-6f) // half of 0.08 = 0.04, raised to the 0.05 floor
        val low = OwwVerifierTrainer.train(List(4) { sample(true) }, List(20) { sample(false) }, listOf(0.001f))
        assertEquals(0.05f, low.verifier.candidateThreshold, 1e-6f) // never below 0.05
    }

    @Test fun saveAndLoadGiveTheSameAnswers() {
        val r = OwwVerifierTrainer.train(List(10) { sample(true) }, List(40) { sample(false) }, listOf(0.3f))
        val f = java.io.File.createTempFile("verifier", ".bin")
        try {
            r.verifier.save(f)
            val back = OwwVerifier.load(f)!!
            val x = sample(true)
            assertEquals(r.verifier.probability(listOf(x)), back.probability(listOf(x)), 1e-6f)
            assertEquals(r.verifier.candidateThreshold, back.candidateThreshold, 0f)
        } finally { f.delete() }
    }

    @Test fun corruptFileLoadsAsNoCheck() {
        val f = java.io.File.createTempFile("verifier", ".bin")
        try { f.writeBytes(byteArrayOf(1, 2, 3)); assertEquals(null, OwwVerifier.load(f)) } finally { f.delete() }
    }
}

class OwwTrainingSetTest {
    private val rnd = java.util.Random(5)
    private fun v(shift: Float) = FloatArray(OwwVerifier.DIM) { (if (it % 97 == 0) shift else 0f) + rnd.nextGaussian().toFloat() }

    @Test fun falseWakeLessonTurnsAnAcceptedMomentIntoARejectedOne() {
        // A "conversation" moment that looks a bit like the user's wake.
        val tricky = List(6) { v(0.6f) }
        val set = OwwTrainingSet(MutableList(24) { v(1.2f) }, MutableList(150) { v(-1.2f) }, peaks = mutableListOf(0.2f))
        val before = set.fit().verifier
        val trickyBefore = tricky.map { before.probability(listOf(it)) }.average()
        set.addFalseWake(tricky.take(4))
        val after = set.fit().verifier
        val trickyAfter = tricky.map { after.probability(listOf(it)) }.average()
        assertTrue("tricky moment before %.2f, after %.2f".format(trickyBefore, trickyAfter), trickyAfter < trickyBefore - 0.2)
        // The user's real wake still passes.
        assertTrue(List(10) { v(1.2f) }.count { after.probability(listOf(it)) >= 0.5f } >= 9)
    }

    @Test fun saveLoadKeepsEverything() {
        val set = OwwTrainingSet(MutableList(3) { v(1f) }, MutableList(5) { v(-1f) }, MutableList(2) { v(0f) }, mutableListOf(0.3f, 0.1f))
        val f = java.io.File.createTempFile("training", ".bin")
        try {
            set.save(f)
            val back = OwwTrainingSet.load(f)!!
            assertEquals(3, back.positives.size); assertEquals(5, back.negatives.size); assertEquals(2, back.hardNegatives.size)
            assertEquals(listOf(0.3f, 0.1f), back.peaks)
            assertEquals(set.negatives[4][7], back.negatives[4][7], 0f)
        } finally { f.delete() }
    }

    @Test fun trimKeepsTrainingFast() {
        val set = OwwTrainingSet(MutableList(2) { v(1f) }, MutableList(2_000) { v(-1f) })
        set.trim()
        assertEquals(OwwTrainingSet.MAX_NEG, set.negatives.size)
    }
}

class QuietMicCaptureTest {
    private fun chunk(level: Short) = ShortArray(1280) { if (it % 2 == 0) level else (-level).toShort() }

    @Test fun quietVoiceInAHummingRoomIsStillHeard() {
        // Room hum ~0.013, the user's voice only ~0.026 (2x): must count as speech.
        val c = FollowUpCapture(speechLevel = 0.002f, initialAmbient = 1f, skipMs = 0, waitMs = 3_000)
        repeat(5) { c.offer(chunk(430)) }
        repeat(8) { c.offer(chunk(850)) }
        while (!c.done) c.offer(chunk(430))
        assertTrue(c.audio() != null)
    }
}
