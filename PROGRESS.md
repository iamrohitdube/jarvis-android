# PROGRESS

## Current phase
**Phase 3: Security.** In progress (started 2026-11-20).
Phase 2: done and device-tested (2026-11-20). Phase 1: done and device-tested (2026-11-20).

## Phase 2: done
- [x] Room 3 database (`jarvis.db`): conversations, messages, snapshots, audit log, usage log (D13)
- [x] Chat history saved on the phone:
  - the chat reopens where you left it while the app runs; **New** starts a fresh chat
  - **History** screen: search, open, delete one, delete all (with confirmation)
  - failed or stopped replies are kept but marked "Not completed" and never resent as context
- [x] Saved portfolio data with "last updated" labels (D14):
  - whole-account INDmoney results (net worth, holdings, SIPs, watchlist) are saved
  - reused for 15 min by default, skipping the INDmoney call; "refresh" / "latest" fetches live
  - each reply shows "INDmoney · updated 10:42" or "saved data from 10:30"
  - Settings → Data: reuse window (0 = always live), Clear saved data, Delete all history
- [x] **Audit log** screen (menu → Audit log): requests, tool calls (with tier), connector sign-in/refresh/sign-out,
  data deletions; filter by category; no content stored; kept 180 days
- [x] Usage log moved from DataStore to Room (Phase 1 entries imported once); kept ~13 months
- [x] 28 new unit tests (DAOs on an in-memory DB, snapshot reuse, audit, tool-call parsing, cached prompt block)

## Phase 2: verified on PC (2026-11-20)
- `gradlew testDebugUnitTest`: **103 tests, 0 failures**.
- `assembleDebug` and `assembleRelease` (R8) build. Debug APK ~40 MB, release ~4.0 MB (Room adds ~0.3 MB). Schema exported to `app/schemas/`.

## Phase 2: device checklist (user reports all working, 2026-11-20; APK SHA-256 `fd98da23…e6369e5`)
1. Update over the existing app (don't uninstall): your API key and INDmoney sign-in should still work, and the
   Usage screen should still show your Phase 1 requests.
2. Ask two questions, close the app fully, reopen it, and open **History**: the chat is there, and opening it
   shows the messages. Search finds it by a word from a reply.
3. "What's my net worth?" shows "INDmoney · updated <time>". Ask again within 15 min: the reply should say
   "saved data from <time>", come back faster, and cost less on the Usage screen. "Refresh my net worth" fetches live.
4. Delete one conversation, then Delete all: both ask first, and History is empty afterwards.
5. Menu → **Audit log**: the requests, the INDmoney tool calls (tier "sensitive"), "cache hit", and the deletions
   are listed, with no message text anywhere.
6. Tap Stop mid-reply: the partial reply stays, marked "Not completed".

## Phase 1: done

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

## Phase 1: verified (PC, 2026-11-20)
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

## Phase 1: device checklist (all passed 2026-11-20 except item 8)
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
- Database isn't encrypted with an app key; relies on Android sandbox + file-based encryption (D14; revisit in Phase 3).
- After the app process is killed, it opens a new chat (old chats are in History).
- No Diagnostics screen or QR key import yet.
- No context compaction: the last 8 turns (clipped) are resent (compaction is Phase 7).
- Placeholder launcher icon (system default).

## Open questions for you
0. **Database encryption:** add app-level encryption (e.g. SQLCipher, which adds native libraries and APK size) in Phase 3, or rely on Android's
   built-in protection? I'd decide it with the PIN/fingerprint work.
1. **Licence:** Porcupine's Android SDK is Apache-2.0 (needs a per-user AccessKey). Apache-2.0 for this repo?
2. **Project folder:** the spec shows `jarvis-android/`; I built at the workspace root (`AI Assistent/`). Fine, or rename?
3. **Effort/latency:** chat runs at `effort: low` (cheapest). Kept for now.
4. **Routing:** now Haiku 5.5 everywhere by default (D11). Revisit after device testing if answers feel weak.

## Next
Phase 3 (Security: tiers, PIN + hashing, 3-strike lockout, fingerprint, sessions, PIN-or-fingerprint flow,
screen-only delivery, tool policy). **Starts only after you OK Phase 2.**
