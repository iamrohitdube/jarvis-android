package dev.jarvis.connectors

import dev.jarvis.agent.RecordingAnswers
import dev.jarvis.connectors.phone.RecordingFile
import dev.jarvis.connectors.phone.RecordingIndexer
import dev.jarvis.connectors.phone.RecordingNames
import dev.jarvis.connectors.phone.RecordingStore
import dev.jarvis.connectors.phone.RecordingTranscriber
import dev.jarvis.connectors.phone.RecordingsConnector
import dev.jarvis.connectors.phone.RecordingsResult
import dev.jarvis.testDb
import dev.jarvis.testJson
import dev.jarvis.voice.Resampler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RecordingsTest {
    private val db = testDb()
    @After fun tearDown() = db.close()

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 26, 12, 0, 0, 0, zone)
    private fun ms(day: Int, hour: Int) = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun personFromFileName() {
        assertEquals("Mom", RecordingNames.personOf("Call recording Mom_251125_1005.m4a"))
        assertEquals("Rahul Verma", RecordingNames.personOf("Rahul Verma(20261124113000).amr"))
        assertEquals("+915550001001", RecordingNames.personOf("+915550001001_20261125.mp3"))
        assertEquals("", RecordingNames.personOf("20261125_100512.m4a"))
    }

    @Test fun resamplerHalvesAndDownmixes() {
        assertEquals(4, Resampler.linear(FloatArray(8) { it.toFloat() }, 32_000, 16_000).size)
        assertEquals(listOf(0.5f, 0.5f), Resampler.downmix(floatArrayOf(0f, 1f, 1f, 0f), 2).toList())
    }

    private val files = listOf(
        RecordingFile("content://r/1", "Call recording Rahul_241125.m4a", ms(24, 11), 1000),
        RecordingFile("content://r/2", "Mom_251125.m4a", ms(25, 20), 1000),
        RecordingFile("content://r/3", "broken.m4a", ms(23, 9), 10),
    )
    private val texts = mapOf(
        "content://r/1" to "hi so about the launch we need the deadline moved to Friday because QA is not done",
        "content://r/2" to "beta did you eat dinner",
    )
    private var modelsReady = true
    private val transcriber = RecordingTranscriber { uri ->
        if (!modelsReady) null else (texts[uri] ?: throw IllegalStateException("can't decode")) to 125_000L
    }
    private val indexer = RecordingIndexer({ RecordingStore { files } }, db.transcripts(), transcriber, clock = { now.toInstant().toEpochMilli() })
    private val connector = RecordingsConnector(db.transcripts(), indexer, testJson, { true }, zone = { zone })

    @Test fun indexesNewFilesOnceAndSearchesTranscripts() = runBlocking {
        val s = indexer.run()
        assertEquals(2, s.transcribed); assertEquals(1, s.failed); assertEquals(0, s.remaining)
        assertEquals(0, indexer.run().transcribed) // nothing new the second time

        val r = connector.callTool(RecordingsConnector.SEARCH, JsonObject(mapOf("query" to JsonPrimitive("deadline friday")))) as ConnectorCall.Ok
        val result = testJson.decodeFromString(RecordingsResult.serializer(), r.text)
        assertEquals(listOf("Rahul"), result.recordings.map { it.name })
        assertTrue(result.recordings.single().snippet, result.recordings.single().snippet.contains("deadline moved to Friday"))

        val text = RecordingAnswers.render("recordings_search", r.text, now)
        assertTrue(text, text.startsWith("1 call mentions \"deadline friday\":\n• Rahul, 24 Nov (2 min): \""))
    }

    @Test fun missingModelsStopsWithoutMarkingFiles() = runBlocking {
        modelsReady = false
        val s = indexer.run()
        assertTrue(s.noModels)
        assertEquals(3, indexer.pending().size) // nothing marked as done or failed
    }

    @Test fun answersSayWhatIsNotTranscribedYet() {
        val raw = """{"recordings":[],"not_transcribed":2}"""
        assertEquals(
            "No transcribed call recordings yet.\n2 newer recordings aren't transcribed yet (Settings → Phone data → Transcribe).",
            RecordingAnswers.render("recordings_recent", raw, now),
        )
    }
}
