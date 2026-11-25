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

## D19. Design system and polish pass (2026-11-21, between Phases 3 and 4)
- **Brand theme (`ui/theme/Theme.kt`):** dark-first. Deep navy (#0A0E17) with an electric blue / cyan / violet
  accent gradient, plus custom light and dark Material 3 colour schemes. Wallpaper-based dynamic colour is off so
  the app always looks like JARVIS. Corners are generous (8/12/16/24/28 dp), and the type scale is tuned with
  semibold titles.
- **Identity:** the JARVIS orb, a glowing core with rotating gradient rings.
  - Adaptive launcher icon (vector, with a monochrome layer for Android 13+ themed icons).
  - Animated launch screen (androidx core-splashscreen 1.2.0): the ring sweeps and the core grows in, and on exit
    the orb scales and fades into the app.
  - A Compose `JarvisOrb` that breathes when idle and spins faster while thinking. It's used on the empty chat,
    as JARVIS's avatar, in the drawer and in onboarding.
- **Chat**, modelled on production assistant apps:
  - navigation drawer (new chat, recent chats, history, usage, audit, security, settings)
  - user bubbles; assistant replies as plain text beside the orb avatar
  - status chips ("updated 10:42", "Shown on screen only"), typing dots while waiting
  - pill composer whose Send button morphs into Stop
  - greeting and suggestion chips on the empty screen
  - items animate in
- **Settings and other screens:**
  - grouped cards with icon tiles and status chips (Saved, Connected, Expired, Locked)
  - Save buttons confirm with "Saved ✓" instead of needing a dialog
  - segmented control for the tool-policy level
  - Usage gets a hero card with a gradient budget bar and stat tiles
  - Audit gets category icons and outcome chips
  - History gets a pill search bar and empty states
- **Motion:** slide/fade screen transitions, animated visibility for status lines, a countdown ring around the
  fingerprint icon in the "PIN or fingerprint?" dialog.
- **Icons:** first built with `material-icons-extended` 1.7.8. That made the debug APK ~76 MB and debug builds
  ~20 min, because it ships thousands of icons and only R8 (release) strips them. It was replaced by
  `ui/icons/AppIcons.kt`: only the 40 icons the app uses, generated from Google's Material Icons "Round" SVGs (npm
  `@material-design-icons/svg` 0.14.15, Apache-2.0, attribution in the file header). They have the same names as
  `Icons.Rounded.*` and look identical.
- **Fixes:**
  - The PIN dialog now shows "Saving…" while the deliberately slow hash runs, keeps the input on errors, and
    closes itself on success (before, it looked stuck and had to be closed by hand).
  - Scrolling screens (Settings, Security, Onboarding, Chat) pad for the keyboard, so a focused field moves up
    above the keyboard instead of hiding behind it.
  - Clearing the usage log now asks first.
- **Reveal behaviour (updates D18):** once a saved sensitive reply has been seen unlocked, it stays visible for
  the rest of the app process, even after the 2-minute session expires or the screen turns off. Only an explicit
  "lock" / Lock now hides them again. Reveals are in memory only, so a restart hides them. New sensitive questions
  still need PIN or fingerprint as before.

## D20. Voice, push-to-talk (Phase 4, 2026-11-21)
- **Pipeline:** mic tap → Android `SpeechRecognizer` (partial results shown live) → agent → `TextToSpeech`, one
  sentence at a time as text streams in (`SentenceChunker`). Speech starts after the first sentence, not after the
  whole reply. The chunker won't split on decimals ("12.4 lakh"), common abbreviations ("e.g.", "Rs.", "vs.") or
  initials, and markdown and URLs are stripped before speaking.
- **Speech recognition:**
  - On-device recognition is preferred when the phone has it (Android 12+, `createOnDeviceSpeechRecognizer`), so
    audio stays on the phone. If the on-device language isn't installed, that session falls back to the default
    recognizer, which may use Google's servers. There's a toggle for this in Settings.
  - All recognizer calls run on the main thread, as the platform requires.
  - Push-to-talk only: the recognizer isn't meant for continuous listening, and the wake word is Phase 5.
- **Speech output:**
  - Audio attributes are `USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH`.
  - JARVIS holds transient audio focus with ducking while speaking, so music dips instead of stopping. It stops
    talking if another app takes focus, such as a call. Android 15+ allows focus only for the top app or a
    foreground service; push-to-talk is always the top app.
  - Utterance callbacks can come from any thread, so speaking state is kept thread-safe.
  - One TTS engine is shared app-wide (initialising it is slow), and speed is adjustable.
- **What is spoken:**
  - Only turns started by voice. Typed questions get on-screen answers.
  - Quiet mode: the speaker toggle in the top bar, or Settings → Voice → Speak replies.
  - Fingerprint-authorised (screen-only) replies are never read out. JARVIS says "Done, it's on your screen."
    instead. The agent emits an `Authorized` event before any reply text, so this is decided before speaking
    starts.
  - Sensitive replies are never spoken while the phone is locked ("It's on your screen once you unlock.").
  - PIN-authorised sensitive replies are spoken and shown, as CLAUDE.md requires.
- **Barge-in:** tapping the mic while JARVIS is speaking or thinking stops speech and cancels the current request,
  then starts listening. Tapping the "Speaking · tap to stop" pill stops speech and keeps the reply on screen.
- **Spoken auth choice:** in a voice turn, "That's sensitive. PIN or fingerprint?" is spoken and JARVIS listens
  once for "PIN" or "fingerprint" (also "thumb", "biometric"). An unclear answer or silence leads to the existing
  6-second default (fingerprint). The PIN itself is still typed: spoken PINs need voice match (CLAUDE.md section
  6), which arrives with the wake-word / voice-match work.
- **Permissions:**
  - `RECORD_AUDIO` is asked for on the first mic tap, after a short explanation, or in onboarding step 2.
  - Android 11+ `<queries>` entries for `android.speech.RecognitionService` and
    `android.intent.action.TTS_SERVICE`, per the platform docs.
- **Onboarding:** a new step 2, "Talk to JARVIS" (microphone permission + Test voice), so existing installs see the
  wizard once more.

## D21. "Hey Jarvis" wake word (Phase 5, 2026-11-21)
**Engine: sherpa-onnx keyword spotting, not Picovoice Porcupine.** CLAUDE.md planned Porcupine. Research on
2026-11-20 found:
- Picovoice told users that free-tier AccessKeys stop working after 30 June 2026, with "no non-commercial tier
  planned".
- Its FAQ now says it is B2B only.
- Keys are validated online.

The user asked for "whichever is free and optimised".
- **sherpa-onnx** (k2-fsa, v2.0.2, Apache-2.0) with the `kws-zipformer-gigaspeech-3.3M` model:
  - The model is Apache-2.0 per its model card.
  - It needs no account and no training: "hey jarvis" is written in the model's BPE pieces (`▁HE Y ▁JA R VI S`;
    all six are present in its vocabulary).
  - It runs fully on the phone on one CPU thread.
- **Cost:** about 33 MB of native libraries (ONNX Runtime + sherpa) plus a 5 MB int8 model, arm64 only. It is a
  tiny streaming speech model rather than a dedicated wake-word network, so it may use somewhat more CPU. That
  has to be measured on the phone.
- **Backup behind the same `WakeWordEngine` interface:** openWakeWord `hey_jarvis`. Its pre-trained models are
  CC BY-NC-SA, so they must not be committed to an Apache repo.
- **Supply chain:** the library (50 MB AAR) and model (17.6 MB) are not committed. The Gradle task
  `fetchWakeWordAssets` downloads them from the official GitHub releases into `third_party/` (gitignored),
  checks pinned SHA-256 hashes, and extracts only the int8 encoder/decoder/joiner + tokens. A changed or tampered
  download fails the build.
- **APK:** `abiFilters = arm64-v8a`, so the APK doesn't carry four copies of the native libraries. This rules out
  32-bit phones and x86 emulators.
- **Keyword tuning:** boost 2.0 and threshold 0.25 are starting values, to tune on the phone for false triggers
  against misses.

**Android rules that shape the design** (official docs, checked 2026-11-20; see the research summary in this
decision):
- **Starting the mic service:** a microphone foreground service may only start while JARVIS is visible, or from a
  notification tap, and never from BOOT_COMPLETED (Android 14+). So the listener starts from Settings or when
  JARVIS is opened. After a reboot, a "Tap to resume" notification appears; a tap is an allowed start.
- **Audio on Android 17:** background playback needs a foreground service with while-in-use capability. The mic
  service started from the app has it, so text-to-speech in pocket mode keeps working.
- **Showing the wake screen:** a foreground service is not a background-activity-launch exemption.
  - With "Display over other apps" granted, `WakeActivity` opens directly over anything, including the lock
    screen (`showWhenLocked` + `turnScreenOn` in the manifest).
  - Otherwise JARVIS posts a full-screen-intent notification, which opens the screen when the phone is locked or
    off and shows a heads-up while in use.
  - Wake locks are not used to turn the screen on; that approach is deprecated.
- **Default digital assistant:** the role (a VoiceInteractionService) would remove most of these limits: the system
  keeps it running, it's exempt from the start restrictions, and it can restart listening after a reboot. It's
  planned for Phase 5b, alongside the helmet button.

**Flow:**
1. The detector runs in `ListeningService`.
2. On "Hey Jarvis" it stops (freeing the mic) and reads the proximity sensor once (≤ 400 ms, no continuous
   battery use).
3. Not covered: the wake screen opens. It's JARVIS's own animation: the orb blooms in, aurora waves follow the
   voice, a light traces the screen edges, and the live transcript shows. One voice exchange runs (with the spoken
   "PIN or fingerprint?" and normal PIN/fingerprint), then the screen fades out and listening resumes.
4. Covered (pocket): no screen. The exchange is audio-only, routed through a connected Bluetooth headset with
   `setCommunicationDevice` (Android 12+; classic HFP and LE Audio) and TTS on the call route. Private questions
   can't be confirmed without the screen, and CLAUDE.md forbids speaking Tier 1/2 data on a locked phone, so the
   headless agent uses `HeadlessAccess`: personal and sensitive tools are refused ("That's private. Take your
   phone out and ask me again.") unless the phone is unlocked and a session is already valid. General questions
   and public market data work normally.
5. Push-to-talk in the chat screen asks the listener to release the mic first (`WakeBus.micBusy`).

**Each "Hey Jarvis" exchange** is saved to History as its own conversation.

**Verify on the phone:**
- false triggers and missed detections
- battery use over a few hours with the screen off
- whether the wake screen opens over the lock screen (with and without the overlay permission)
- whether the fingerprint prompt works over the lock screen
- whether the C40 picks up the mic and speaker in pocket mode
- whether a reboot leads to the "Tap to resume" notification

## D22. "Hey Jarvis" anytime: default assistant role + standby gate (Phase 5, 2026-11-21)
The user asked for the wake word to work without touching the phone, any time, with everything else on standby.
- **Default digital assistant.** JARVIS ships a `VoiceInteractionService` (+ session service + a stub
  `RecognitionService`, which the role still requires to be named) and an `ACTION_ASSIST` entry on the wake
  screen. The user selects it once in Android Settings → Default apps → Digital assistant; the role isn't
  requestable from the app. As role holder:
  - the system keeps the service running and binds it with foreground priority;
  - `onReady()` may start the microphone listener with no visible screen, including after a reboot (a documented
    while-in-use exemption);
  - JARVIS may open the wake screen from the background without the overlay permission;
  - the assist gesture and lock-screen assist open the wake screen.

  Cost: it replaces Gemini/Google Assistant. Without the role, the overlay permission + "tap to resume" fallbacks
  from D21 still work.
- **Standby.** Only the wake-word listener runs. The rest (agent, network, database) is idle until a wake word or
  the user opens the app.
- **Silence gate:** an `EnergyGate` in front of the keyword model. While it's quiet, each 100 ms chunk is only
  measured for loudness and the model doesn't run. It opens when the level rises above max(floor, 3x the learned
  background noise), replays a 300 ms pre-roll so the start of "Hey" isn't lost, and closes after 1.5 s of quiet
  (resetting the model's stream). The noise floor is learned only while quiet, so a fan or traffic hum doesn't
  keep it open. Silero VAD (MIT, 0.6 MB) is the upgrade path if the simple gate proves too eager or too strict.

## D23. Spoken PIN with voice match (Phase 5, 2026-11-21)
The user asked to say the PIN out loud. CLAUDE.md section 6 requires "spoken PIN + voice match", so:
- **On-device only.** Android's `createOnDeviceSpeechRecognizer` doesn't officially guarantee that audio stays
  on the phone (its reference still says implementations may stream to servers), so it isn't used for PINs.
  JARVIS records the PIN itself (`AudioRecord`). The same samples go to:
  - **voice match:** 3D-Speaker ERes2Net English (sherpa-onnx release, Apache-2.0, 26.5 MB, 192-dim, EER ~0.8-1%
    on VoxCeleb1-O);
  - **digits:** Moonshine tiny English v2 quantized (MIT for English models, 43.6 MB), then `PinSpeech`, which
    turns "two five eight zero" / "2580" / "double five" into digits.
- **Download on demand.** About 70 MB, fetched only when the user turns it on (Security → Spoken PIN), into
  no-backup app storage. Each file is pinned by SHA-256. The Moonshine files come individually from the author's
  Hugging Face repo at a pinned commit (Android can't unpack `.tar.bz2` natively); their hashes were verified as
  identical to the official sherpa-onnx GitHub release archive. The speaker model comes from the GitHub release.
- **Enrolment.** A fresh fingerprint or screen lock is required, like changing the PIN, so someone holding the
  phone can't enrol their own voice. The user reads 3 random 6-digit phrases (not the PIN). The averaged,
  normalised embedding is stored encrypted in `SecretStore` (`VOICE_PRINT`). No audio is kept.
- **Verification:** cosine similarity ≥ 0.6 (sherpa-onnx examples use 0.5-0.6; to be calibrated on the phone).
  At least 1 s of voiced audio is required.
- **Rules (`AuthCoordinator.submitSpokenPin`):**
  - voice didn't match: the PIN isn't transcribed or checked (no oracle for a stranger), it doesn't count toward
    the 3-strike lockout, and JARVIS switches to fingerprint;
  - voice matched + wrong PIN: counts as a wrong PIN;
  - nothing heard or unclear: "didn't catch the PIN", no penalty;
  - success: spoken and shown (PIN path).
- **Flow (`VoicePinFlow`).** In a voice turn, after "PIN", JARVIS says "Say your PIN." and records. That's up to 3
  spoken tries per authentication. Typing in the dialog always works. The flow is silent in quiet mode or until
  spoken PIN is set up.
- **Not available hands-free.** In a pocket or on a locked phone, private questions are refused (D21), so spoken
  PINs only happen with the phone in hand (wake screen or chat).
- **Privacy:** transcripts are never logged. `debug=false` on the native models. Audit entries record only
  `voice_pin` outcomes.
- **Training-data caveat:** both public speaker models are trained on VoxCeleb (YouTube-sourced). Fine for
  personal use; noted for the public release.


## D24: Wake-word audio fixes (software gain, microphone choice), 2026-11-21
- **Problem.** On the user's phone the keyword model never fired. Their recordings were very quiet and muffled
  (most of the energy below 300 Hz). The same engine worked on PC test clips.
- **Tried:** `AutoGain` (software gain up to about 16x, driven by level), the platform `AutomaticGainControl`
  effect, and a `MicSource` setting (standard, voice-recognition, call, camcorder, unprocessed) with a "Compare
  microphones" test that picks the loudest. "Save recording" writes a WAV to Downloads for troubleshooting.
- **Result:** louder audio, but still not recognised for this user. These tools stay for the keyword engine
  (now "Keyword model (beta)").

## D25: Default wake engine = the phone's own speech recognizer, 2026-11-21
- **Choice.** `SystemSpeechWakeEngine` runs short back-to-back `SpeechRecognizer` sessions and wakes when the
  transcript contains "Jarvis". The user chose this after the keyword model failed for their voice. Anything said
  after the name in the same breath is passed on as the question.
- **On-device recognizer preferred**, so audio stays on the phone. If the phone has none, or the user turns it
  off, speech near the phone goes to the default recognizer (usually Google) while listening. Settings says so.
- **Trade-offs:** more battery than a keyword model, and short gaps between sessions.
- **The keyword model stays selectable** (Settings → Hey Jarvis → Detector).
- **Phone-answered questions (`LocalAnswers`).** The time, the date/day and the battery are answered on the
  phone with no Claude request (free, instant). Every other question carries a one-line local time and time zone
  on the user message, which keeps the cached prompt prefix stable.

## D26: Training on the user's accent, and a wake sound, 2026-11-21
- **Why.** The phone recognizer sometimes hears "Jarvis" as something else ("service", "Travis"), so wakes were
  missed.
- **Matching:**
  - the listener asks for up to 5 guesses (n-best) and checks all of them;
  - it accepts near spellings (edit distance 1, starting with "ja", e.g. "jarviz").
- **"Teach JARVIS your voice":**
  - The user says "Hey Jarvis" x3 and "Jarvis" x2.
  - Every guess of 1-3 words without the name becomes a learned phrase. Up to 20 are kept, stored in settings
    (not secret: they're mishearings of "Jarvis").
  - Phrases match only as whole word sequences, so "hey service" wakes JARVIS but "customer service" doesn't.
  - One-word learned phrases are ignored on Strict.
  - Each phrase can be removed.
  - No audio is stored.
- **Wake sound (`WakeCue`).** Options: Silent, Chime (default), Hum, a custom phrase, or chime + phrase.
  - The sounds are made on the phone (no audio files) and played before the mic opens, so JARVIS doesn't hear
    itself.
  - If the question came in the same breath, or quiet mode is on, only the sound plays.
  - On a Bluetooth call route (pocket mode), the sound uses the voice-communication stream, so it should reach
    the headset.


## D27: Wake word = openWakeWord "hey jarvis" plus a personal voice check, 2026-11-21
- **Why the change.** The phone-recognizer engine (D25) ran full recognition sessions back to back. On the user's
  phone (Android System Intelligence) every session played an "open"/"failure" sound, so it beeped every ~5 s, and
  vivo's audio policy kept the proximity sensor on. It also cost the most battery. "Hey Siri"/"Hey Google" use a small
  always-on keyword model instead; Android's always-on hotword API is limited to system apps.
- **Engine.** openWakeWord (github.com/dscripka/openWakeWord) with its pre-trained `hey_jarvis_v0.1` model. The
  streaming pipeline from `utils.py`/`model.py` is ported to Kotlin (`OwwStream`):
  - every 80 ms: mel of 1280 + 480 context samples (`x/10 + 2`);
  - one embedding from the last 76 mel frames;
  - a score from the last 16 embeddings;
  - the first 5 scores are zeroed.
  On 48 synthetic clips the port matched the official Python package to within 0.08 (the gap is the random warm-up
  noise): it woke on 12/12 "hey jarvis", and at threshold 0.5 its only false wakes were "Hey Travis" (2/36).
- **Runtime:** LiteRT 2.3.2 (TensorFlow Lite) runs the .tflite models on one CPU thread.
  - ONNX Runtime was ruled out because sherpa-onnx bundles its own `libonnxruntime.so` (1.28.2, not on Maven).
  - LiteRT's Java Interpreter prepares a model on load, and the mel model's placeholder input is 1 sample, which
    can't be prepared. So the hash-verified file is loaded into memory with its input shape set to `[1, 1760]`
    (byte offset pinned for v0.5.1). The file on disk is unchanged.
- **Licences.** The code is Apache-2.0. The pre-trained models are **CC BY-NC-SA 4.0** (non-commercial). So they're
  downloaded on demand (3.7 MB, pinned SHA-256, Settings → Hey Jarvis), never committed or bundled. Attribution is
  shown in Settings.
- **Silence gate.** The models run only while there's sound. The mic is the plain `MIC` source, so there are no
  recognizer sessions, no sounds and no proximity sensor. Measured idle CPU: ~0%.
- **Personal voice check ("Teach JARVIS your voice")**, the same idea as openWakeWord's custom verifier models:
  - **What it is:** logistic regression over the model's own 16x96 features, trained on the phone.
  - **What it's trained on:** 8 "Hey Jarvis" said different ways (each at 4 time offsets), 4 read sentences full of
    sound-alikes, 15 s of free talk and 4 s of silence.
  - **How waking works with it:** the base model only nominates (score >= half the user's weakest training wake,
    floor 0.05), and the check decides (>= 0.7/0.8/0.9 by sensitivity).
  - **Training details:** class-balanced, L2 0.3, with the step size from a power-iteration estimate of the largest
    curvature.
  - **Simulation with real features:** the "user's" held-out wakes scored 0.99, while their "Hey Travis"/"Hey,
    service" scored 0.00.
  - **"Not for me"** on the wake screen adds that moment's features as hard negatives (weighted 3x) and retrains.
  - **Storage:** only feature numbers are kept (no audio), in no-backup app storage.
- **Instant reaction.** The wake is reported at once (screen in ~0.1-0.5 s) while the same mic keeps recording what
  follows, until ~0.65 s of quiet (relative to the room level measured before the wake). So "Hey Jarvis, what's the
  time?" works in one breath. In a pocket the wake sound plays at once; in hand it waits until the question is
  recorded, so it isn't recorded with it.

## D28: Voice conversation, transcription and spoken PIN changes (user requests), 2026-11-21
- **Continuous conversation.** After answering, JARVIS keeps listening with the conversation as context (last 6
  exchanges), and sleeps after 15 s of silence or on "stop / that's all / thanks / bye / go to sleep / never mind".
- **Silent waiting.** It waits for speech on JARVIS's own mic and only then transcribes, so waiting makes no
  recognizer sounds.
- **Transcription.** Recordings go to the phone's recognizer (Android 13+ `EXTRA_AUDIO_SOURCE`), then to on-device
  Moonshine if that fails. On the user's phone, Android System Intelligence started answering everything with
  "no speech". `EXTRA_BIASING_STRINGS` was removed: on that recognizer it returned "no speech" for clear answers.
- **"PIN or fingerprint?"**
  - The answer window is 11 s instead of 6 s (the user asked for +5 s; 6 s included asking the question).
  - The answer is a fixed 3.5 s recording, so a quiet "PIN" isn't missed, and JARVIS asks once more if unclear.
  - More spellings of "PIN" are accepted.
- **Voice match for the spoken PIN is now optional, off by default**, at the user's request. This deviates from
  CLAUDE.md section 6, which says "spoken PIN + voice match".
  - Turning it off needs a fingerprint or screen lock, and the change is audited.
  - With it on, the cut-off is 0.5 instead of 0.6 (the user's own voice was rejected at 0.6), and the score is
    audited for calibration.
  - Enrolment is now 5 phrases.
- **Spoken PIN with the phone locked or hands-free.** This deviates from D21 (private data needed the phone in hand).
  - Hands-free, a private question now goes straight to "Say your PIN." if spoken PIN is set up and not locked out.
  - An answer unlocked by PIN this turn may be spoken while locked. CLAUDE.md forbids speaking Tier 1/2 data while
    locked *without the required auth*, and the PIN path is "result spoken AND shown".
  - Fingerprint answers stay screen-only.
  - The 3-strike lockout is unchanged, and only biometrics can reset it.
- **PIN digits** come from the phone's on-device recognizer (never a cloud one) and Moonshine; the first clean 4-8
  digit answer is used.

## D29: Personal data stays on the phone (Gmail, Calendar, Drive, INDmoney), 2026-11-22
**Status: step 2 (INDmoney) built 2026-11-22; step 3 (Gmail) built 2026-11-25; later steps proposed. Choices marked "pick" need the
user's answer before the matching step.**
This changes CLAUDE.md section 2 (connectors via the Anthropic MCP connector) and section 12, Phase 6.

**Rule.** Connector tokens never leave the phone, except to the service they belong to. Raw emails, files, events
and holdings never go to Claude. Claude gets personal data only for a feature the user has opted in to, redacted on
the phone, with a preview the first time. The default is nothing personal. No new cloud services.

**INDmoney: the app becomes the MCP client.**
- Today the token goes to Anthropic inside `mcp_servers`, so Anthropic sees the token and every tool result.
- The app already does INDmoney's OAuth (discovery, dynamic registration, PKCE, refresh) and an MCP `initialize`
  call (`McpProbe`). Step 2 adds `tools/list` and `tools/call` over Streamable HTTP, on OkHttp +
  kotlinx.serialization: JSON-RPC POST, a JSON or SSE reply, the `Mcp-Session-Id` header and the protocol-version
  header.
- Not the official MCP Kotlin SDK: it brings Ktor alongside OkHttp and is far more than three JSON-RPC calls need.
- `mcp_servers` and the MCP beta header are removed from Anthropic requests. Tests check that no request body
  contains a token or holdings.
- Risk: the tools' output format isn't documented. The first device run logs only the shape (field names and
  types, no values; debug builds, tag `JarvisShape`) so templates can be written against it.
- Built (step 2):
  - Personal tools (above Tier 0) never reach Claude: the agent answers them on the phone (`FinanceAnswers`
    templates). Unknown shapes fall back to a plain list of the top-level fields, never a guess.
  - Public market tools become ordinary client tools. The phone runs them, and the loop refuses any personal or
    unrouted tool Claude asks for.
  - Turns answered from personal data are flagged and never resent as history.
  - The budget stop doesn't apply to on-phone answers (they're free).
  - Narrow portfolio questions ("allocation breakdown") are answered from the whole-account snapshot, because
    the narrower tools need arguments.
  - Offline: the last saved result, labelled with its age.
  - Before D29, saved snapshots were also attached to Claude requests as `<cached_data>`. That is removed.
- **Smart routing (user's choice: option B, 2026-11-22).** A phrase list missed phrasings like "savings account
  balance", so Claude now categorises every question (except time/date/battery/lock, answered locally).
  - Claude is offered the public market tools (the phone runs them, under the tool policy) and `private_data`
    (kinds: net_worth, bank_balance, holdings, sips, watchlist; several at once; optional `item` for one holding).
    No personal tool is ever offered.
  - Calling `private_data` ends the Claude turn. The phone runs the security gate (fingerprint/PIN, screen-only
    rules) and answers. Claude never receives the data.
  - Follow-ups: earlier questions stay in the history Claude sees, private answers become
    "[answered privately on the phone]". This gives Claude the context for "and my SIPs?" without the numbers.
  - The phrase list is only a backup: Claude unreachable (offline) or the monthly budget used up.
  - Cost: one small Haiku call per personal question (the routing), about $0.0002. General finance words
    ("money", "fund", "invest") no longer trigger personal data.
  - Rejected: an on-device embedding classifier (A) and Gemini Nano (C), for now. The user chose B.

**Google sign-in: Google Identity Services `AuthorizationClient` (play-services-auth).**
- Google's current recommendation for access to Google user data on Android, with Credential Manager for sign-in.
  JARVIS needs no sign-in identity, only authorization.
- Google Play services holds the grant and returns short-lived access tokens on the phone. The app keeps no
  refresh token and no client secret, and needs no backend. It calls `authorize()` again when a token expires,
  silently while the grant is valid. If Google needs the user (grant revoked or expired), JARVIS shows "Google
  sign-in expired. Sign in again".
- Android OAuth client = package `dev.jarvis` + the APK's SHA-1. Each user makes their own Cloud project
  (bring-your-own, like the API key). Steps are in docs/setup-keys.md section 3.
- Rejected: AppAuth with a refresh token in `SecretStore`. That's more token handling for the app, and Google
  steers Android apps to the Authorization API.

**Scopes (minimal).**
- Gmail: `gmail.readonly` (restricted). The app requests only `format=metadata` (From, Subject, Date) and the
  snippet. It never downloads bodies or attachments in this phase. `gmail.metadata` would also be restricted, and
  it blocks the `q=` search that "email from X" needs.
- Drive: `drive.metadata.readonly` (restricted): names, types, folders, dates, no file content. `drive.file` is
  too narrow: it covers only files the app created or opened, so "find the file called Y" wouldn't work.
- Calendar: **recommended without OAuth.** Read the calendar Android already syncs, through `CalendarContract`
  (`READ_CALENDAR` permission). There's no Google Cloud scope, no token, it works offline, and it covers every
  account on the phone. Read-only now. Writes (Tier 3) come later through the same provider. **Pick:** this, or
  the Calendar API with `calendar.readonly`.
  **User picked `CalendarContract` (phone's calendar), 2026-11-25.**

**Weekly re-login.** In "Testing" status Google ends a test user's grant 7 days after consent. The plan is to
publish the Cloud project "In production" without verification. That's allowed for personal use under 100 users,
shows a one-time "Google hasn't verified this app" warning, and has no 7-day expiry. Open question for the public
release: every user has to create their own Cloud project and Android client, and Google may not allow the same
package + SHA-1 in two projects. Check this before Phase 10.

**On-device answers first.** A local intent router extends `LocalAnswers`: unread count, "email from X", today's
events, "next meeting", net worth, SIPs, "find the file Y". These are templates over the Room cache, with no LLM
call and no Claude call. Anything the router doesn't match that needs personal data gets "I can't answer that on
the phone. Allow sending a redacted version to Claude?", which leads to that feature's consent setting.

**On-device LLM for summaries (optional). Depends on the exact phone model (not known yet; adb was offline).**
- **ML Kit GenAI (Gemini Nano through AICore)** is the first choice when the phone is on Google's list. The vivo
  X200, X200 Pro, X200T, X300 series, X500 Pro and X Fold3 Pro/5/6 are listed (Prompt API on Gemini Nano 4); the
  T4 Ultra and X200 FE are listed with Gemini Nano 2.
  - Nothing ships in the APK; AICore downloads the model. Free.
  - Beta. Summarization takes up to about 4000 tokens and gives 1-3 bullets, in English.
  - **Foreground only:** a call from a background service fails with `BACKGROUND_USE_BLOCKED`. It works in Chat
    and on the wake screen, but not hands-free in a pocket (screen off). There it falls back to counts and lists.
- **Otherwise: LiteRT-LM** (Google's successor to MediaPipe LLM Inference, which is now deprecated on Android).
  Kotlin API stable; CPU, GPU and NPU. It runs in a background service too. Model choices:
  - Qwen3-0.6B: 586 MB, Apache-2.0. Measured on a vivo X300 Pro at about 21 tokens/s on GPU.
  - Gemma3-1B: 1.0 GB, Gemma terms of use (check that the licence is compatible).
  - Gemma-3n-E2B: 3 GB. Too big for a download on demand.
  The model is downloaded on demand and hash-checked, like the Moonshine models, never put in the APK.
  LiteRT-LM's own licence needs checking before use.
- **Pick (in step 5):** ML Kit if supported, LiteRT-LM + Qwen3-0.6B otherwise, or summaries off. If summaries are
  off, the user gets lists and counts.
- **Update 2026-11-25: on-device LLM dropped (user decision).** The user tried an on-device model before: too slow,
  too much RAM, and the phone hung. Claude is the only brain. Every question goes to Claude, which decides whether
  to answer itself, use public/internet tools, or hand the question back to the phone with `private_data`
  (finance, email, calendar, call log...), where the phone fetches and answers without Claude seeing the data.
  The phrase-list router stays only as the backup (offline, over budget, no key). Step 5 is cancelled; without
  consent, personal answers are lists and counts, not summaries.

**Consent and redaction.** Each feature (Email, Calendar, Drive, Finance) has the setting "Never send to Claude"
(the default) or "Send minimal, redacted data". Redaction runs on the phone and strips email addresses, phone
numbers, account, card and IFSC-style numbers, OTPs and codes, URLs with tokens, and amounts unless the feature
needs them. The first send shows the exact text and asks for approval. Tests cover the redactor with adversarial
inputs and check that "Never" means no personal field in any request body.

**Privacy filters.** Excluded Gmail labels and senders are dropped before caching. Banks, OTP and 2FA senders and
the Promotions category are excluded by default. Excluded Drive folders. A "Private mode" switch blocks every
connector, including the cache.

**Unchanged.** Tiers (email, calendar and Drive need a recent unlock; finance needs PIN or fingerprint), spoken
confirmation for sends and edits, email and file content treated as untrusted data, and an audit entry for every
access (service, tier and counts, no content).

**Offline cache.** Room tables for inbox headers (sender, subject, date, snippet, labels, unread), today's and
tomorrow's events, Drive file metadata and the last portfolio snapshot, each with "last updated". Kept short and
deleted with "Clear saved data". Database encryption is still the open question from D14.

## D30: Call log and call recordings (user request), 2026-11-25
- **Call log:** `READ_CALL_LOG`, tool group `calls`, Tier 1 (recent unlock). Answered on the phone through
  `private_data` like finance ("missed calls today", "when did X last call?"). Play policy allows this permission
  only for default Phone/SMS/Assistant handlers; JARVIS registers as the assistant (Phase 5b) and is sideloaded anyway.
- **Call recordings:** Android blocks third-party call recording (Play policy since 2022; no call audio source for
  apps), so JARVIS never records. It reads files the phone's built-in dialer already saved, from a folder the user
  picks once (Storage Access Framework, no broad storage permission). Verify the vivo dialer's folder on the device.
- **Transcription on the phone** with the speech models already in the app (not an LLM), in the background while
  charging. Transcripts stay in the encrypted local DB; keyword search runs on the phone.
- **Claude** sees transcript text only per D29 consent ("Send minimal, redacted data" for the Calls feature), with
  a preview the first time. Call recording laws vary; the user is responsible for the recordings their dialer makes.
- To verify before building: current `READ_CALL_LOG` rules on Android 16, SAF access to the dialer folder, and
  transcription speed for long recordings on the user's phone.

## D31: JARVIS feature set after Phase 6 (user-approved), 2026-11-25
Ideas taken from open-source assistants (isair/jarvis knowledge-graph memory, proactive Jarvis repos, OpenClaw's
heartbeat checklist). Ideas only; no code copied. OpenClaw's community skill store is not used (not audited).
Built as Phase 6c, one feature at a time: People view, meeting prep, missed-call follow-ups, editable watch list,
night debrief, location routines, bills and subscriptions, travel, screen awareness + camera. Each follows D29.
