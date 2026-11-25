package dev.jarvis.connectors.phone

import dev.jarvis.connectors.Connector
import dev.jarvis.connectors.ConnectorCall
import dev.jarvis.connectors.ConnectorStatus
import dev.jarvis.connectors.ConnectorTestResult
import dev.jarvis.connectors.McpToolDef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A data source that lives on the phone itself (calendar, call log, SMS…), read through Android with a runtime
 * permission. No account, no token, works offline, and nothing leaves the phone (D29). "Not connected" means the
 * permission isn't granted; the UI asks for it in Settings → Phone data.
 */
abstract class LocalConnector(
    final override val id: String,
    final override val displayName: String,
    /** Whether Android has granted the permission(s) this source needs. */
    private val granted: () -> Boolean,
    /** Android permissions to request for this source (shown in Settings → Phone data). */
    val permissions: List<String> = emptyList(),
    /** One line for Settings: what JARVIS reads. */
    val purpose: String = "",
) : Connector {
    private val recheck = MutableStateFlow(0)

    override val status: Flow<ConnectorStatus> =
        recheck.map { if (granted()) ConnectorStatus.Connected else ConnectorStatus.NotConnected }

    /** Call after a permission dialog closes so the status updates. */
    fun refreshStatus() { recheck.value++ }

    /** Reads one tool's data as JSON text. Runs on an IO thread. */
    protected abstract suspend fun read(tool: String, arguments: JsonObject): String

    override suspend fun callTool(name: String, arguments: JsonObject): ConnectorCall {
        if (!granted()) return ConnectorCall.NeedsSignIn
        return try {
            ConnectorCall.Ok(withContext(Dispatchers.IO) { read(name, arguments) }, toolError = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            refreshStatus()
            ConnectorCall.NeedsSignIn
        } catch (e: Exception) {
            ConnectorCall.Failed(e.message ?: "$displayName couldn't be read")
        }
    }

    /** Phone data is answered on the phone only: no tools are offered to Claude (D29). */
    override suspend fun toolDefinitions(names: Collection<String>): List<McpToolDef> = emptyList()

    override suspend fun test(): ConnectorTestResult =
        if (granted()) ConnectorTestResult.Ok else ConnectorTestResult.Failed("JARVIS needs access to your $displayName.", needsReauth = true)

    /** Access is withdrawn in Android settings, not here. */
    override suspend fun signOut() = Unit

    protected fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
}
