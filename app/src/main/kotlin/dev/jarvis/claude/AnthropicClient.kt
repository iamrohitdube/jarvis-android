package dev.jarvis.claude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** A request ready to send: the JSON body plus any `anthropic-beta` flags it needs. */
data class LlmRequest(val body: JsonObject, val betas: List<String> = emptyList())

sealed interface LlmEvent {
    data class Update(val update: StreamUpdate) : LlmEvent
    data class Completed(val message: AssembledMessage, val timeToFirstTokenMs: Long?) : LlmEvent
}

/** Typed API failures, mapped to plain-language messages by the UI. Messages never include secrets. */
sealed class LlmException(message: String) : Exception(message) {
    class MissingKey : LlmException("No Anthropic API key saved")
    class Unauthorized : LlmException("Anthropic API key was rejected")
    class PermissionDenied(msg: String) : LlmException(msg)
    class RateLimited(val retryAfterSeconds: Long?) : LlmException("Rate limited")
    class Overloaded : LlmException("Anthropic API is overloaded")
    class InvalidRequest(val apiMessage: String) : LlmException(apiMessage)
    class Server(val code: Int) : LlmException("Anthropic server error $code")
    class Network(cause: IOException) : LlmException(cause.message ?: "Network error")
}

/** Swappable LLM interface (CLAUDE.md: LlmClient). */
interface LlmClient {
    fun stream(request: LlmRequest): Flow<LlmEvent>
    /** Validates a key with a free call (GET /v1/models). */
    suspend fun testKey(apiKey: String): Result<Unit>
}

/**
 * Anthropic Messages API over raw HTTP (OkHttp + kotlinx.serialization), streaming via SSE.
 * Wire format verified against the Claude API reference (API version 2026-10-21).
 */
class AnthropicClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val apiKeyProvider: suspend () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val maxRetries: Int = 2,
    private val clock: () -> Long = System::currentTimeMillis,
) : LlmClient {

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() } ?: throw LlmException.MissingKey()
        val started = clock()
        coroutineScope {
            // A blocked OkHttp read ignores coroutine cancellation, so a watcher child cancels the
            // in-flight call as soon as the collector is cancelled (e.g. the user taps Stop).
            val activeCall = AtomicReference<Call?>(null)
            val watcher = launch {
                try { awaitCancellation() } finally { activeCall.get()?.cancel() }
            }
            try {
                executeWithRetry(buildMessagesRequest(key, request), activeCall).use { resp ->
                    val source = resp.body.source()
                    val parser = SseParser()
                    val acc = StreamAccumulator(json)
                    var firstTokenAt: Long? = null
                    while (true) {
                        val line = try {
                            source.readUtf8Line()
                        } catch (e: IOException) {
                            ensureActive()
                            throw LlmException.Network(e)
                        } ?: break
                        val event = parser.feed(line) ?: continue
                        for (u in acc.onEvent(event)) {
                            if (u is StreamUpdate.TextDelta && firstTokenAt == null) firstTokenAt = clock()
                            emit(LlmEvent.Update(u))
                        }
                        if (acc.finished) break
                    }
                    parser.finish()?.let { ev -> acc.onEvent(ev).forEach { emit(LlmEvent.Update(it)) } }
                    emit(LlmEvent.Completed(acc.result(), firstTokenAt?.let { it - started }))
                }
            } finally {
                watcher.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun testKey(apiKey: String): Result<Unit> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/v1/models?limit=1")
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .get().build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw mapError(resp)
            }
        }.recoverCatching { e -> if (e is IOException) throw LlmException.Network(e) else throw e }
    }

    private fun buildMessagesRequest(key: String, request: LlmRequest): Request {
        val builder = Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
        if (request.betas.isNotEmpty()) builder.header("anthropic-beta", request.betas.distinct().joinToString(","))
        return builder.post(request.body.toString().toRequestBody(JSON_MEDIA)).build()
    }

    /** Retries connection errors, 408/409/429/5xx before any stream byte is consumed. */
    private suspend fun executeWithRetry(request: Request, activeCall: AtomicReference<Call?>): Response {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val call: Call = http.newCall(request)
            activeCall.set(call)
            val resp = try {
                call.execute()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (attempt < maxRetries) {
                    attempt++; delay(backoffMs(attempt, null)); continue
                }
                throw LlmException.Network(e)
            }
            if (resp.isSuccessful) return resp
            val retryable = resp.code in RETRYABLE && resp.header("x-should-retry") != "false"
            val retryAfter = resp.header("retry-after")?.toLongOrNull()
            if (retryable && attempt < maxRetries) {
                resp.close(); attempt++; delay(backoffMs(attempt, retryAfter)); continue
            }
            resp.use { throw mapError(it) }
        }
    }

    private fun backoffMs(attempt: Int, retryAfterSeconds: Long?): Long =
        retryAfterSeconds?.coerceIn(1, 30)?.times(1000) ?: (500L shl (attempt - 1)).coerceAtMost(8000)

    private fun mapError(resp: Response): LlmException {
        val apiMessage = runCatching {
            json.parseToJsonElement(resp.body.string()).jsonObject["error"]?.jsonObject?.str("message")
        }.getOrNull() ?: "HTTP ${resp.code}"
        return when (resp.code) {
            401 -> LlmException.Unauthorized()
            403 -> LlmException.PermissionDenied(apiMessage)
            429 -> LlmException.RateLimited(resp.header("retry-after")?.toLongOrNull())
            529 -> LlmException.Overloaded()
            in 400..499 -> LlmException.InvalidRequest(apiMessage)
            else -> LlmException.Server(resp.code)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val API_VERSION = "2026-10-21"
        private val JSON_MEDIA = "application/json".toMediaType()
        private val RETRYABLE = setOf(408, 409, 429, 500, 502, 503, 504, 529)
    }
}
