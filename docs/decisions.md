# Decisions

Short records of choices that are not obvious from the code. Newest last.

## D1. Raw HTTP (OkHttp + kotlinx.serialization) instead of the Anthropic Java SDK (Phase 1)
The spec (CLAUDE.md section 1) names OkHttp + kotlinx.serialization. It keeps the APK small, avoids Jackson
and reflection on Android, and lets us pass content blocks back byte-for-byte. The wire format follows the
Claude API reference: `anthropic-version: 2026-10-21`, SSE streaming. That API version rejects `temperature`,
`top_p` and `top_k`, so we never send them (there's a unit test for this).

## D2. Thinking and effort
Sonnet 5.5, Haiku 5.5 and Opus 5.5 run adaptive thinking by default, and `{"type":"disabled"}` is a 400 on Sonnet 5.5
and Opus 5.5. We omit `thinking` and set `output_config.effort` per mode: `low` for chat, which is the recommended
start for chat-style traffic and keeps time-to-first-token down for voice later. Raise it per mode if quality needs it.

## D3. Completed turns are resent as plain text only
On Sonnet 5.5 and Opus 5.5, thinking blocks are tied to the exact prefix (system + tools + earlier messages).
The spec wants lazy tool loading (a different `tools` list per request) and per-request model routing (Sonnet ↔ Haiku).
Both would invalidate replayed thinking blocks, which is a 400 on newer accounts. So:
- Within one turn (a `pause_turn` resume), the full assistant content blocks are sent back unchanged.
- Across turns, only the user text and the final assistant text are kept. This also matches "keep working context short".
Trade-off: the model doesn't see its earlier reasoning or old raw tool results. For a status-line assistant that's fine;
re-fetching data is cheap and keeps it fresh.

## D4. Prompt caching
We use top-level automatic caching (`cache_control: {type: "ephemeral"}`), so the breakpoint follows the end of
the conversation. The system prompt has no timestamps or per-request data. Render order is tools → system → messages,
so changing the tool set between turns misses the cache for that request. Accepted: lazy tool loading saves more
tokens than the cache would on the short tool-free turns. Note: prompts under the model's minimum (512 tokens on
Sonnet 5.5) silently don't cache. Phase 1's persona prompt is short, so caching mostly kicks in as history grows.

## D5. Server-side refusal fallback
On Sonnet 5.5 and Opus 5.5 we send `fallbacks: "default"` with beta `server-side-fallback-2026-07-01` (toggle in
Settings, default on). If Claude declines a request in the `cyber` or `frontier_llm` categories, it is retried
server-side on another model. Haiku 5.5 has no server-side fallback, so we don't send it there. A remaining
`stop_reason: "refusal"` is shown as "Claude declined that request."

## D6. INDmoney auth: in-app OAuth 2.1 (DCR + PKCE) with a pasted-token fallback
INDmoney's MCP server advertises RFC 9728/8414 metadata, dynamic client registration, `authorization_code` +
`refresh_token`, PKCE S256 and `client_secret_post`. The app registers itself once per install (the client id and
secret are generated at runtime and stored encrypted, never in the repo), signs in through a Custom Tab, and
refreshes tokens before each use. We request only the `portfolio:read market:read` scopes, not `insights:write`.
The redirect URI is the custom scheme `dev.jarvis://oauth/indmoney`. **Unverified:** whether INDmoney accepts a
custom-scheme redirect. If it doesn't, Settings → INDmoney → "Advanced: paste a token" works, but those tokens can't be refreshed.

## D7. Secrets: Android Keystore AES-GCM + DataStore
`androidx.security:security-crypto` (EncryptedSharedPreferences) is deprecated. Secrets are encrypted with a
non-exportable AES-256-GCM Keystore key (the entry name is bound as associated data) and stored as ciphertext in a
dedicated Preferences DataStore. Android backup and device transfer are disabled for all app data. An explicit
encrypted export comes in Phase 10.

## D8. Lazy tool routing is local and keyword-based
`ToolRouter` picks tool groups and individual INDmoney tools by keyword, with no extra LLM call. The MCP toolset
runs in allowlist mode (`default_config.enabled = false` plus the routed tools), so only 2–3 tool definitions are
sent for "what's my net worth?" instead of 14. Short tool-free questions go to the quick model (Haiku 5.5).
Misroutes are possible. Phase 7+ may add a cheap Haiku classifier if keyword routing proves too coarse.

## D9. Toolchain
AGP 9.5.1 (built-in Kotlin), Gradle 9.6.1, Kotlin 2.4.21, KSP 2.3.14, Hilt 2.60.1, Compose BOM 2026.11.01,
compileSdk 37.2 (required by Compose 1.13), targetSdk 37, minSdk 29 (Android 10), JDK 17.

## D10. No Diagnostics screen or QR import in Phase 1
Phase 1 covers wizard steps 1 and 4 only. QR key import needs a camera/scanner dependency and the PC-side page
(docs/setup-keys.md). Both are deferred, and the Diagnostics screen arrives with the permissions work (Phases 4–5).

## D11. Cost-first defaults (2026-11-20)
The user asked for the lowest token use and cost. Changes:
- **Model:** Claude Haiku 5.5 for both the chat and quick routes ($0.10/$0.50 per MTok, about 1/20 of Sonnet 5.5).
  Sonnet stays one Settings field away for anyone who wants richer answers. Haiku 5.5 has no server-side fallback,
  so `fallbacks` is never sent to it (refusals are handled in the client).
- **Output caps:** chat `max_tokens` 8000 → 1500 (thinking counts toward it, at `effort: low`); quick 2000 → 500.
  A cut-off reply is flagged in the UI.
- **Thinking off for quick questions:** `thinking: {type: "disabled"}` on the tool-free route, only when the model is
  Haiku 5.5 (Sonnet 5.5 rejects `disabled`; Fable/Opus 5.5 can't turn thinking off). Tool turns keep adaptive
  thinking so the model still plans tool calls.
- **Shorter prompt:** the persona is trimmed and asks for 1–3 sentence answers by default.
- **Less history:** last 8 turns instead of 20, and each resent turn is clipped to 1200 characters (the current
  question is never clipped). Proper compaction is still Phase 7.
- **Budget:** default monthly budget $5, with "stop requests when the budget is used up" on by default. The check
  runs before any network call. The Anthropic Console spend limit remains the real cap.
- Prompt caching stays (5-minute auto-caching). Haiku 5.5 caches from 512 tokens, so most multi-turn chats get
  cache reads at 0.1x. A one-off question pays a 1.25x write on its prefix, which is fractions of a cent at Haiku rates.

## D12. Voice-first short replies (2026-11-20)
The app is mostly used by voice, so the default reply is the answer in one short sentence (two at most), with no
preamble, filler, markdown or follow-up offers. The persona prompt enforces this, and so do the caps: quick route
300 tokens (thinking off), chat route 1200 (thinking and tool calls count toward it). Asking for more ("tell me
more", "explain", "details", "elaborate", "break it down") routes to a DETAIL mode on the chat model, which allows
up to about 6 sentences (1500 tokens). Detection is by keyword in `ToolRouter`, so it costs no extra call.

## D13. Room 3 for local data (Phase 2, 2026-11-20)
Room 3.0.3 (`androidx.room3`), not Room 2.8.5. Both are maintained. Room 3 is the current major line, coroutine-only
and KSP-only, which matches this codebase. It can also build an in-memory database without an Android Context, so
DAO tests run as plain JVM unit tests with `sqlite-bundled-jvm` (no device, no Robolectric). The app uses the
phone's built-in SQLite (`AndroidSQLiteDriver`) to keep the APK small. Search is a plain `LIKE` over titles and
message text, which is enough at personal scale and avoids FTS4/FTS5 differences between Room 3 and device SQLite
builds. Snapshots use `@Insert(onConflict = REPLACE)` instead of `@Upsert`, because `@Upsert` sniffs SQLite error
messages, which the host-JVM Android stubs don't provide. The Phase 1 DataStore usage log is imported once at
start-up and then removed.

## D14. Saved connector data ("snapshots") and the database at rest (Phase 2)
- **What's cached:** only whole-account INDmoney reads (`networth_snapshot`, `networth_holdings`, the two SIP
  tools, `user_watchlist`). A result is saved only when the call had no filter arguments, so a saved result always
  means the same as a fresh call. Live market data (prices, option chains, fund details) is never cached.
- **Reuse:** if every tool a question needs has a saved result younger than the reuse window (default 15 min,
  0 = off), the request goes out with no MCP server or tools. The saved data is attached to the question as an
  untrusted `<cached_data>` block. That skips the INDmoney round-trip and its tool-definition and tool-result
  tokens. "Refresh", "latest", "live", "update" and similar words force a live fetch.
- **Labels:** every reply that used connector data shows its source and time: "updated 10:42" (live) or "saved data
  from 10:30" (cached).
- **At rest:** the database relies on Android's app sandbox and file-based encryption, with no app-level
  encryption. Adding SQLCipher (or similar) costs APK size and a key-management design. We'll decide in Phase 3,
  where the PIN/biometric work already handles keys.

## D15. Biometric library: androidx.biometric 1.4.0-rc01 (Phase 3, 2026-11-20)
The latest stable release (1.1.0) dates from 2021 and needs a `FragmentActivity`. 1.4.0-rc01 adds
`registerForAuthenticationResult`, which works with our plain `ComponentActivity` (since 1.4.0-alpha06), plus
`AuthenticationRequest`/`AuthenticationResult` types. It's a release candidate, so the API is frozen. The API was
checked against the library jar itself, because the guide still shows the classic `BiometricPrompt`.
- Android 11+: Class 3 biometric with the phone's screen lock as fallback, or the screen lock alone if no
  biometric is enrolled.
- Android 10: biometric and device credential can't be combined (official guide), so the prompt offers
  biometric with a "Use PIN" button that switches to the JARVIS PIN.
- No `CryptoObject` binding yet. The result gates app logic, not a Keystore key. That's acceptable for showing
  data the app already holds. Binding can be added if a later feature needs it.
- `onAuthAttemptFailed` (a non-matching finger) is left to the system prompt, which lets the user retry.

## D16. PIN storage and lockout (Phase 3)
- PBKDF2-HMAC-SHA256, 600,000 iterations (OWASP Password Storage Cheat Sheet, checked 2026-11-20), 16-byte random
  salt, iterations stored with the hash. Argon2id would need a native library. The hash lives in `SecretStore`
  (Keystore AES-GCM), because a 4-8 digit PIN is a small search space if the hash ever leaked.
- PINs are 4-8 digits. Trivial PINs (all one digit, straight runs like 1234/9876) are rejected.
- Only well-formed wrong PINs count. Three consecutive wrong PINs lock the PIN path. The counter lives in
  `SecretStore`, so it survives restarts and can't be edited to unlock. A correct PIN before lockout clears the
  counter.
- Only a fingerprint/device-credential success resets the lockout. In code that requires a `StrongAuthProof`,
  which the PIN path can't create. Voice (later phases) won't be able to either.
- Setting, changing or removing the PIN needs a fingerprint/device credential when the phone has a secure lock
  screen. Otherwise someone holding the unlocked phone could set their own PIN and read finance data.
- The PIN is typed in a masked field in a dialog, never in the chat box. It isn't stored in history, isn't sent
  to Claude, and isn't logged. The audit log records only outcomes.

## D17. Tool policy semantics (Phase 3)
CLAUDE.md's level descriptions are short, so this is the exact interpretation, enforced in `ToolPolicy`:
- **STRICT:** ask for everything except open (Tier 0) reads.
- **SMART (default):** ask for writes/actions and for sensitive (Tier 2) reads.
- **AUTO:** like SMART, but tools the user set to Allow run without asking, writes included.
- **Per-tool rules:**
  - DENY always wins.
  - ASK always asks.
  - ALLOW is honoured for reads at any level, and for writes only in AUTO.
  - Tools that need confirmation (send message, create event) always ask.
- For a Tier 2 read, "ask" is satisfied by the PIN/fingerprint check itself, or by a valid sensitive session.
  Otherwise every finance question within the 2-minute session would prompt twice. Lower-tier asks are an
  Allow / Don't allow dialog.
- If every tool a question needs is denied, the app answers locally ("turned off in Security settings") and
  doesn't call Claude.

## D18. Sessions, Tier 1, and sensitive data at rest (Phase 3)
- Sessions are in memory only: Tier 2 lasts 2 min, Tier 1 lasts 5 min (both editable). A Tier 2 grant covers
  Tier 1. All sessions end on screen-off, on "lock" (typed now, spoken from Phase 4), and on Settings → Lock now.
- Tier 1 ("needs a recent unlock") is granted when the phone is unlocked (`ACTION_USER_PRESENT`) or when JARVIS
  comes to the foreground on an unlocked phone. It never grants Tier 2.
- The gate runs in `AgentLoop` after routing and before saved snapshots or connectors, so cached finance data
  needs auth too. The prompt is neutral ("That's sensitive. PIN or fingerprint?") and is shown before any API
  call, so no data is revealed by asking.
- Replies that used Tier 2 data are stored with `sensitive = true`. Their text is hidden in Chat ("Sensitive.
  Hidden until you unlock.") unless a sensitive session is active, so History doesn't bypass the gate.
  Fingerprint-authorised replies are also stored `screenOnly = true`. Phase 4's text-to-speech will say "Done, it's
  on your screen." instead of reading them out.
- "net worth, fingerprint" / "..., use PIN" pre-empts the choice. Only a leading or trailing method word counts,
  and it's removed before the question goes to Claude.
- The database moved to version 2 (two new message columns) by Room auto-migration. A unit test builds a real v1
  database from the exported schema and checks that history survives the upgrade.
- Deferred to the voice phases: spoken PIN with voice match, and challenge digits. Typed PINs make challenge
  digits unnecessary for now.
