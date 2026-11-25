package dev.jarvis.connectors

import dev.jarvis.FakeSecretStore
import dev.jarvis.agent.DriveAnswers
import dev.jarvis.connectors.google.DriveApi
import dev.jarvis.connectors.google.DriveConnector
import dev.jarvis.connectors.google.DriveResult
import dev.jarvis.connectors.google.GoogleTokenSource
import dev.jarvis.security.SecretKey
import dev.jarvis.testJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DriveConnectorTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private val tokens = object : GoogleTokenSource {
        override suspend fun accessToken() = "drive-tok"
        override suspend fun clearToken(token: String) {}
    }
    private fun connector(linked: Boolean = true) = DriveConnector(
        FakeSecretStore(if (linked) mapOf(SecretKey.GOOGLE_DRIVE_LINKED to "1") else emptyMap()),
        tokens, DriveApi(OkHttpClient(), testJson, server.url("/").toString().trimEnd('/')), testJson,
    )
    private fun ok(body: String) = MockResponse.Builder().code(200).addHeader("content-type", "application/json").body(body).build()

    private val files = """{"files":[
        {"name":"Budget 2026","mimeType":"application/vnd.google-apps.spreadsheet","modifiedTime":"2026-11-20T09:15:00.000Z","webViewLink":"https://docs.google.com/x"},
        {"name":"Rohan's offer.pdf","mimeType":"application/pdf","modifiedTime":"2026-11-25T05:00:00.000Z"}
    ]}"""

    @Test fun searchBuildsAnEscapedNameQueryAndAsksForMetadataOnly() = runBlocking {
        server.enqueue(ok(files))
        val r = connector().callTool(DriveConnector.SEARCH, JsonObject(mapOf("query" to JsonPrimitive("Rohan's offer")))) as ConnectorCall.Ok
        val req = server.takeRequest()
        assertEquals("Bearer drive-tok", req.headers["Authorization"])
        val q = req.url.queryParameter("q")!!
        assertTrue(q, q.contains("name contains 'Rohan\\'s offer'"))
        assertTrue(q, q.contains("trashed = false"))
        assertEquals("files(name,mimeType,modifiedTime,webViewLink)", req.url.queryParameter("fields"))
        val result = testJson.decodeFromString(DriveResult.serializer(), r.text)
        assertEquals(listOf("Sheet", "PDF"), result.files.map { it.type })
    }

    @Test fun notLinkedMeansSignIn() = runBlocking {
        assertEquals(ConnectorCall.NeedsSignIn, connector(linked = false).callTool(DriveConnector.RECENT, JsonObject(emptyMap())))
        assertEquals(0, server.requestCount)
    }

    @Test fun answers() {
        val now = ZonedDateTime.of(2026, 11, 25, 18, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        val raw = """{"query":"budget","files":[{"name":"Budget 2026","type":"Sheet","modified":"2026-11-20T09:15"}]}"""
        assertEquals("1 file for \"budget\":\n• Budget 2026 (Sheet, edited 20 Nov)", DriveAnswers.render("drive_search", raw, now))
        assertEquals("No Drive files named like \"x\".", DriveAnswers.render("drive_search", """{"query":"x"}""", now))
    }
}
