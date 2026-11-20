# PROGRESS

## Current phase
**Phase 1: Skeleton.** Built, unit-tested and **device-tested (2026-11-20): passed.** Phase 2 starts when you say go.

## Phase 1: done
- [x] Project: Kotlin, Compose (Material 3), Hilt, Coroutines/Flow; AGP 9.5.1, Kotlin 2.4.21; minSdk 29, target 37
- [x] `.gitignore` (keys, local.properties, keystores), git repo initialised (nothing committed yet)
- [x] Secure storage: `KeystoreSecretStore` (Android Keystore AES-256-GCM + DataStore)
- [x] Settings screen:
  - API key (test + save)
  - INDmoney sign-in, test, sign-out and manual-token fallback
  - models per route
  - quick-model routing toggle
  - fallback toggle
  - monthly budget
  - spend-limit notice
- [x] Onboarding wizard (resumable, per-step todo/done/skipped): step 1 Anthropic key, step 4 connectors
- [x] Chat UI: streaming text, tool status line ("Checking INDmoney…"), Stop, errors with a fix button, budget banner
- [x] `AnthropicClient`:
  - Messages API over OkHttp with SSE streaming
  - retries (408/409/429/5xx, connection errors)
  - typed errors
  - cancellation
- [x] `AgentLoop`: route → connectors → stream → `pause_turn` resume (max 3) → refusal/max_tokens handling → usage log
- [x] `ToolRegistry` (catalog with group, risk, tier, confirmation) + `ToolRouter` (lazy tools, quick-model routing)
- [x] INDmoney via MCP connector (beta `mcp-client-2025-11-20`):
  - OAuth 2.1 discovery, dynamic client registration and PKCE
  - refresh-before-use, plus one retry with a refreshed token on MCP auth errors
  - read-only scopes
  - allowlisted toolset
- [x] `UsageMeter` + Usage screen: tokens (incl. cache read/write), estimated cost, latency, time to first token,
  monthly total, 80% budget warning
- [x] CI: unit tests, debug APK artifact, gitleaks secret scan
- [x] Docs: README, docs/architecture, decisions, security, setup-keys, install, navigation (placeholder)

## Cost-first change (2026-11-20, after Phase 1 build)
At your request the app now spends as little as possible (details in docs/decisions.md D11):
- Haiku 5.5 is the default for all chat; Sonnet is opt-in.
- Short replies: output caps of 1500 (chat) and 500 (quick); thinking is off for simple questions.
- Only the last 8 turns are resent, each clipped to 1200 characters; the persona prompt is shorter.
- Monthly budget defaults to $5 and stops requests when used up (toggle in Settings).
- Voice-first short replies (D12): one short sentence by default, no filler; "tell me more" / "explain" gives a
  longer answer (up to ~6 sentences).
Rough cost: a typical question costs about $0.0002–0.001; 30 questions a day comes to roughly $0.20–0.60 a month.

## Verified (PC, 2026-11-20)
- `gradlew testDebugUnitTest`: **75 tests, 0 failures** (65 + 10 for the cost-first and short-reply changes). Coverage:
  - SSE parsing and stream assembly
  - HTTP client headers, retries and errors (MockWebServer)
  - router and prompt builder
  - agent loop (pause_turn, connector sign-in, MCP auth retry, refusal)
  - pricing and budget
  - PKCE (RFC 7636 vector), OAuth client and INDmoney connector (state check, refresh, re-auth, sign-out)
- `assembleDebug` and `assembleRelease` (R8) build. Debug APK ~39 MB, release ~3.7 MB.
- Live check: INDmoney's MCP endpoint returns 401 with OAuth metadata (DCR, PKCE S256, refresh tokens, scopes
  `portfolio:read market:read insights:write`).

## Not yet verified (needs your phone)
1. App installs and launches; the wizard appears on first launch.
2. Anthropic key **Test & save** works with a real key.
3. A chat streams a reply; Stop interrupts mid-stream.
4. INDmoney **Sign in**:
   - does INDmoney accept our custom-scheme redirect `dev.jarvis://oauth/indmoney` during dynamic registration?
   - does the browser return to the app?
5. INDmoney **Test** passes.
6. "What's my net worth?" calls `networth_snapshot` and answers.
7. Usage screen shows the request with cost; set a tiny budget (e.g. $0.01) to see the 80% banner, then the
   "budget used up" stop (with a Change budget button).
10. Replies are short enough when spoken, and "tell me more" gives the longer version.
9. Haiku answer quality is acceptable for chat and "what's my net worth?". If not, try `claude-sonnet-5-5` as the
   chat model in Settings and compare cost on the Usage screen.
8. What does the API return when the INDmoney token is invalid? (Our refresh-and-retry relies on a heuristic match
   on the 400 message; capture the exact message for the record.)

## Device test results
**2026-11-20:** APK installed over the hotspot (debug build, SHA-256 `1282a05c…7bd3d3`). The user reports everything
works with a real Anthropic key. Phone model, Android version and per-item notes weren't recorded.
- Not captured: item 8, the exact API error when the INDmoney token is invalid. It'll be logged when it first happens.

## Decisions
See [docs/decisions.md](docs/decisions.md) (D1–D10). Key ones:
- Completed turns are resent as text only (keeps lazy tools and model routing compatible with preserved thinking).
- `effort: low` for chat.
- Server-side refusal fallback on Sonnet/Opus.
- Keystore + DataStore instead of the deprecated EncryptedSharedPreferences.

## Known limitations / deferred
- No auth gate on finance yet (Tier 2 gate is Phase 3).
- Conversation is in memory only (Room in Phase 2).
- Usage log capped at 1000 entries in DataStore (moves to Room in Phase 2).
- No Diagnostics screen or QR key import yet.
- No context compaction: the last 8 turns (clipped) are resent (compaction is Phase 7).
- Placeholder launcher icon (system default).

## Open questions for you
1. **Licence:** Porcupine's Android SDK is Apache-2.0 (needs a per-user AccessKey). Apache-2.0 for this repo?
2. **Project folder:** the spec shows `jarvis-android/`; I built at the workspace root (`AI Assistent/`). Fine, or rename?
3. **Effort/latency:** chat runs at `effort: low` (cheapest). Kept for now.
4. **Routing:** now Haiku 5.5 everywhere by default (D11). Revisit after device testing if answers feel weak.

## Next
Phase 2 (Room DB, history, cached snapshots with "last updated" labels, audit log screen). **Starts only after you OK Phase 1.**
