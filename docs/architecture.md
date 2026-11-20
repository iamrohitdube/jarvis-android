# Architecture (Phase 1)

```
UI (Compose)                    Agent                         Claude / connectors
-----------------               ------------------------      ---------------------------------
ChatScreen ── ChatViewModel ──▶ AgentLoop.run(history, text)
                                  │ ToolRouter (keywords → mode, model, tools)
                                  │ Connector.mcpServer()  ──▶ IndMoneyConnector (OAuth refresh)
                                  │ PromptBuilder (JSON body, betas)
                                  └▶ LlmClient.stream() ─────▶ AnthropicClient (OkHttp, SSE)
                                       SseParser → StreamAccumulator → StreamUpdate events
                                  UsageMeter.record()  (tokens, cost, latency; local only)
SettingsScreen / Onboarding ──▶ SettingsViewModel ──▶ SecretStore (Keystore AES-GCM) / SettingsRepository
OAuthRedirectActivity ────────▶ IndMoneyConnector.completeSignIn()
```

## One chat turn
1. `ToolRouter.route()` picks a **mode** (Chat or Quick), a **model** and a **tool list** from keywords. Tool-free
   short questions go to the quick model.
2. For each connector the tools need, `Connector.mcpServer()` returns a config with a valid token, refreshing it if
   it has expired. If the connector isn't signed in, the loop stops with "sign in" and doesn't call Claude.
3. `PromptBuilder.build()` creates the body:
   - `model`, `max_tokens`, `stream: true`
   - automatic caching
   - the stable persona system prompt
   - text-only history and the new user message
   - `output_config.effort`
   - for tool requests: `mcp_servers` and an allowlisted `mcp_toolset` (beta `mcp-client-2025-11-20`)
   - optional `fallbacks: "default"`
4. `AnthropicClient.stream()` retries 408/409/429/5xx and connection errors before streaming starts. It then
   parses SSE and emits text deltas and tool start/finish events. Cancelling the flow (Stop) cancels the HTTP call.
5. `stop_reason: "pause_turn"`: the assistant content blocks are appended unchanged and the request is re-sent,
   up to 3 times.
6. If the API reports an MCP auth error (heuristic on the 400 message), the loop refreshes the token once and
   retries. If that fails, it asks the user to sign in again.
7. One `UsageEntry` per turn: tokens (input, output, cache write and read), estimated cost, latency, time to first
   token, tool calls. No prompt or reply text is logged.

## Swappable interfaces
`LlmClient`, `SecretStore`, `Connector`. Coming later: `WakeWordEngine`, `Stt`, `Tts`, `Storage` (Room).

## What's in memory vs on disk (Phase 1)
- On disk (DataStore):
  - settings
  - encrypted secrets
  - the usage log (last 1000 entries)
- In memory only: the conversation (lost when the process dies). Room-backed history arrives in Phase 2.
