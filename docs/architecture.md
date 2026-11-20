# Architecture (Phase 3)

```
UI (Compose)                    Agent                         Claude / connectors
-----------------               ------------------------      ---------------------------------
ChatScreen ── ChatViewModel ──▶ AgentLoop.run(history, text)
   │  (HistoryStore: Room)        │ budget check (UsageMeter)
HistoryScreen                     │ ToolRouter (keywords → mode, model, tools)
AuditScreen ◀── AuditLog          │ SnapshotStore.freshFor()  → reuse saved data, or
UsageScreen ◀── UsageMeter        │ Connector.mcpServer()  ──▶ IndMoneyConnector (OAuth refresh)
                                  │ PromptBuilder (JSON body, betas, cached data block)
                                  └▶ LlmClient.stream() ─────▶ AnthropicClient (OkHttp, SSE)
                                       SseParser → StreamAccumulator → StreamUpdate events
                                  ToolCalls.extract() → SnapshotStore.save(), AuditLog, DataStamps
                                  UsageMeter.record()  (tokens, cost, latency; local only)
SettingsScreen / Onboarding ──▶ SettingsViewModel ──▶ SecretStore (Keystore AES-GCM) / SettingsRepository
OAuthRedirectActivity ────────▶ IndMoneyConnector.completeSignIn()
```

## One chat turn
1. **Budget:** if "stop at budget" is on and this month's local estimate has reached the budget, the turn ends
   before any network call.
2. **Route:** `ToolRouter.route()` picks a mode (Quick, Chat or Detail), a model and a tool list from keywords.
   A leading/trailing "fingerprint" or "PIN" is removed first and kept as the preferred auth method.
2b. **Security gate:** `AccessController.authorize()` (implemented by `AuthCoordinator`) applies the tool policy
   (denied tools removed; all denied = local answer) and the tier check: a valid session, or the PIN-or-fingerprint
   prompt. Nothing below runs unless this grants access. The grant says whether the reply is sensitive and
   screen-only.
3. **Saved data:** if every routed tool is a whole-account snapshot tool (net worth, holdings, SIPs, watchlist)
   with a result younger than the reuse window (default 15 min), the tools are dropped and the saved results are
   attached to the question as an untrusted `<cached_data>` block. Saying "refresh", "latest" or "live" skips this.
4. **Connectors:** otherwise, each connector the tools need returns an MCP config with a valid token (refreshed if
   expired). If a connector isn't signed in, the loop stops with "sign in" and doesn't call Claude.
5. **Request:** `PromptBuilder.build()` creates the body: model, `max_tokens`, `stream`, automatic caching, the
   stable persona, text-only history, the question (plus any cached data), `output_config.effort`, thinking off on
   the quick route (Haiku only), and for tool requests `mcp_servers` plus an allowlisted `mcp_toolset`.
6. **Stream:** `AnthropicClient.stream()` retries 408/409/429/5xx and connection errors before streaming starts,
   then emits text and tool events. Stop cancels the HTTP call. `pause_turn` is resumed up to 3 times. An MCP auth
   error triggers one token refresh and retry.
7. **After the reply:** `ToolCalls.extract()` pairs `mcp_tool_use` with `mcp_tool_result` blocks. Each call is
   audited. Successful whole-account calls made without arguments are saved as snapshots. The reply carries
   `DataStamp`s, shown as "INDmoney · updated 10:42" or "saved data from 10:30".
8. **Records:** one `UsageEntry` per turn (tokens, cost, latency) and one audit row for the request outcome. The
   chat screen saves the question and the reply to Room.

## Local data (Room 3, `jarvis.db`)
| Table | What | Retention |
|---|---|---|
| `conversations`, `messages` | Full verbatim chat history; searchable (LIKE) | Until you delete it |
| `snapshots` | Latest result of each whole-account connector read | Replaced on each fetch; "Clear saved data" in Settings |
| `audit_log` | Requests, tool calls, connector and data events; no content | 180 days |
| `usage_log` | Tokens, cost, latency per request; no text | ~13 months |

Context sent to Claude is still only the last 8 turns, clipped (`AgentLoop.trimHistory`). Context compaction is
Phase 7. The schema is exported to `app/schemas/` so future versions can ship tested migrations.

## Swappable interfaces
`LlmClient`, `SecretStore`, `Connector`, `AuditSink`, `AccessController`, `BiometricAvailability`. Coming later: `WakeWordEngine`, `Stt`, `Tts`.

## On disk vs in memory
- DataStore: settings and encrypted secrets.
- Room: everything in the table above.
- In memory only: the reply currently streaming. If you tap Stop, the partial reply is saved and marked
  "Not completed", and it's never resent as context.
