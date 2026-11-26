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
3. **Personal data, on the phone (D29):** if any granted tool is personal (above Tier 0: net worth, holdings,
   SIPs, watchlist), Claude is not called. Each whole-account read comes from a fresh snapshot (default 15 min;
   "refresh"/"latest"/"live" skips it) or a live call the phone makes to INDmoney (`McpClient`, Streamable HTTP).
   `FinanceAnswers` turns the result into a short reply. If INDmoney can't be reached, the last saved result is
   used with its age. The reply is marked `personal`, costs nothing and is never resent to Claude as history.
4. **Public tools:** for market-data questions the connector returns the tool definitions (`tools/list`), which go
   to Claude as ordinary client tools. If a connector isn't signed in, the loop stops with "sign in" and doesn't
   call Claude.
5. **Request:** `PromptBuilder.build()` creates the body: model, `max_tokens`, `stream`, automatic caching, the
   stable persona, text-only history with personal turns removed, the question, `output_config.effort`, thinking
   off on the quick route (Haiku only), and the client tools. There is no `mcp_servers` and no connector token.
6. **Stream:** `AnthropicClient.stream()` retries 408/409/429/5xx and connection errors before streaming starts,
   then emits text events. Stop cancels the HTTP call. `pause_turn` is resumed up to 3 times.
7. **Tool rounds:** on `stop_reason: tool_use`, the phone runs each requested tool through the connector, up to 4
   rounds. Only routed public tools can run; a personal or unrouted tool gets an error result and an audit entry
   ("blocked"). Results go back as `tool_result` blocks. The reply carries `DataStamp`s, shown as
   "INDmoney · updated 10:42" or "saved data from 10:30".
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
Phase 16. The schema is exported to `app/schemas/` so future versions can ship tested migrations.

## Swappable interfaces
`LlmClient`, `SecretStore`, `Connector`, `AuditSink`, `AccessController`, `BiometricAvailability`. Coming later: `WakeWordEngine`, `Stt`, `Tts`.

## On disk vs in memory
- DataStore: settings and encrypted secrets.
- Room: everything in the table above.
- In memory only: the reply currently streaming. If you tap Stop, the partial reply is saved and marked
  "Not completed", and it's never resent as context.
