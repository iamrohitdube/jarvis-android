package dev.jarvis

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.jarvis.claude.UsageMeter
import dev.jarvis.security.SecretKey
import dev.jarvis.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import okio.Path.Companion.toOkioPath
import java.io.File
import java.nio.file.Files

val testJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

class FakeSecretStore(initial: Map<SecretKey, String> = emptyMap()) : SecretStore {
    val values = MutableStateFlow(initial)
    override suspend fun get(key: SecretKey) = values.value[key]
    override suspend fun put(key: SecretKey, value: String) { values.value = values.value + (key to value) }
    override suspend fun remove(vararg keys: SecretKey) { values.value = values.value - keys.toSet() }
    override fun presence(): Flow<Set<SecretKey>> = values.map { it.keys }
}

fun tempUsageMeter(scope: CoroutineScope): UsageMeter {
    val dir: File = Files.createTempDirectory("usage").toFile()
    val store = PreferenceDataStoreFactory.createWithPath(scope = scope) {
        File(dir, "usage.preferences_pb").toOkioPath()
    }
    return UsageMeter(store, testJson)
}

/** Builds an SSE body from (event, json) pairs. */
fun sse(vararg events: Pair<String, String>): String =
    events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" }

/** A minimal complete text-only stream. */
fun textStream(text: String, stopReason: String = "end_turn", model: String = "claude-sonnet-5-5"): String = sse(
    "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"$model","content":[],"usage":{"input_tokens":100,"output_tokens":1,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}}""",
    "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
    "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}""",
    "content_block_stop" to """{"type":"content_block_stop","index":0}""",
    "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"$stopReason"},"usage":{"output_tokens":20}}""",
    "message_stop" to """{"type":"message_stop"}""",
)
