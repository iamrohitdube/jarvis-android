# Security notes

Phase 3 status: access tiers, PIN, fingerprint, lockout, sessions and tool policy are implemented (CLAUDE.md section 6). Voice-specific parts (spoken PIN, voice match) come with the voice phases.

## Secrets
- Stored only in `KeystoreSecretStore`:
  - the Anthropic API key
  - INDmoney access and refresh tokens and their expiry
  - the dynamically registered OAuth client id and secret
  - pending PKCE state and verifier
- Encryption: AES-256-GCM with a non-exportable Android Keystore key. Ciphertext lives in the `secrets` DataStore.
- The saved key is never displayed. Key and token fields use `OutlinedSecureTextField`, so their values can't be
  copied out.
- `toString()` of `McpServerConfig` and `TokenSet` redacts tokens. OAuth errors surface only the OAuth `error` code,
  never `error_description`, which could echo request data.
- There's no HTTP logging interceptor and no analytics or telemetry.
- Android backup and device transfer are disabled (`allowBackup=false`, data extraction rules exclude everything).
- `.gitignore` excludes local.properties, keystores, env files and client secrets. CI runs gitleaks over the full history.

## What leaves the phone
- Anthropic receives:
  - your messages
  - the text of earlier turns (the last 8, clipped)
  - saved INDmoney data when a question is answered from it (marked as untrusted data)
  - the INDmoney access token, in `mcp_servers[].authorization_token`, so that Anthropic's MCP connector can call
    INDmoney on your behalf
  - tool results
- INDmoney receives the OAuth sign-in (in the browser, on INDmoney's own page) and token refreshes. A connector
  test sends an MCP `initialize` call straight from the phone.

## OAuth
- Uses PKCE S256 and a random `state`. The state is checked before the code exchange, and pending values are
  deleted after one use, whatever the outcome.
- Requests read-only scopes only (`portfolio:read market:read`).
- The redirect activity is exported (the browser has to reach it). It accepts only `dev.jarvis://oauth/indmoney`
  and never logs the URI.

## Access control (Phase 3)
- **Tiers:**
  - Tier 0: open, no check.
  - Tier 1: personal. Needs a recent phone unlock.
  - Tier 2: sensitive, which covers INDmoney net worth, holdings and SIPs. Needs a fresh PIN or fingerprint, or
    a session under 2 minutes old.
  - Tier 3: actions. Arrives with messaging in Phase 6.
- **Where it's enforced:** the gate (`AuthCoordinator`, via `AccessController`) runs in code before any tool or
  saved tool data is used. It's never left to the prompt.
- **The flow:**
  1. JARVIS asks neutrally: "That's sensitive. PIN or fingerprint?"
  2. If there's no answer within 6 s, it uses fingerprint.
  3. With fingerprint (or the phone's screen lock), the answer is marked "shown on screen only".
  4. You can pre-empt the question by typing "..., fingerprint" or "..., use PIN".
- **PIN:**
  - It's entered in a masked dialog, never in chat, and is never sent to Claude or logged.
  - Only a salted PBKDF2 hash (600k iterations) is stored, inside the Keystore-encrypted store.
  - After 3 well-formed wrong PINs, the PIN path locks; malformed input doesn't count. The lock survives
    restarts, and only a fingerprint/screen-lock success clears it. You get a notification when the PIN locks.
  - Changing or removing the PIN needs a fingerprint or screen lock.
- **Sessions:**
  - They're in memory only.
  - Tier 2 lasts 2 min and Tier 1 lasts 5 min (both editable).
  - They end on screen-off, on "lock", and on Lock now.
- **Saved sensitive replies:** these are hidden in Chat until you unlock, so History doesn't get around the gate.
- **Tool policy:**
  - Strict, Smart (default) or Auto, plus Allow / Ask / Deny for each tool (decision D17). There's no "off".
  - Denied tools are removed before the request is built.
- **Audit:** every auth event (prompt, PIN ok/wrong/malformed/locked, fingerprint ok/cancelled/error, lockout and
  reset, session lock, PIN set/changed/removed, policy changes) is recorded without secrets.

## Voice (Phase 4)
- The microphone is used only during push-to-talk, after a tap. There's no background listening until the
  wake-word phase.
- Speech recognition prefers the on-device recognizer, so audio stays on the phone. If that isn't available for
  your language, the phone's default recognizer is used, which may send audio to Google. You can turn the
  on-device preference off in Settings → Voice.
- Fingerprint-authorised answers are never spoken, and sensitive answers are never spoken while the phone is
  locked. Both are enforced in `VoiceSession`, not in prompts.
- Spoken PINs aren't supported yet (they need voice match). The PIN is always typed in a masked field.

## Wake word (Phase 5)
- "Hey Jarvis" detection runs entirely on the phone (sherpa-onnx). Audio isn't stored or sent anywhere until the
  wake word fires. After that, speech recognition follows the Voice rules above.
- The listener is off by default, shows a persistent notification while on, and has a Stop button.
- Hands-free with no screen (pocket, locked): personal and sensitive tools are refused out loud, because there's
  no way to confirm PIN or fingerprint. Only general questions and public data are answered (`HeadlessAccess`).
- The wake-word library and model are downloaded at build time from their official releases, with pinned SHA-256
  hashes (supply-chain check).

## Spoken PIN (Phase 5)
- The PIN is recorded by JARVIS itself and processed only on the phone (voice match + digit recognition models).
  It never goes to a cloud recognizer, to Claude, or to logs.
- Voice match first: if the voice doesn't match, the PIN isn't transcribed or checked, and the attempt doesn't
  count toward the lockout. That means no oracle for someone who overheard it.
- The voiceprint is an embedding vector (not audio), stored Keystore-encrypted. Enrolling or deleting it needs a
  fresh fingerprint or screen lock.
- Voice match is a second factor, not proof: a good recording of the user could fool it. Fingerprint remains the
  stronger choice when people are around (CLAUDE.md section 6).

## Prompt injection
The system prompt marks tool, email, web and document content as untrusted data. In Phase 1 every tool is
read-only, so nothing can act on injected instructions. Enforcement in code (confirmations for actions after
untrusted content) arrives with Tier 3 actions.

## Local data (Phase 2)
- Chat history, saved connector data, the audit log and the usage log are in a Room database in the app's private
  storage. Android's file-based encryption protects it while the phone is locked, other apps can't read it, and
  it's excluded from backup and device transfer.
- The database itself isn't encrypted with an app key (decision D14). Someone with the unlocked phone and USB
  debugging could read it from a **debug** build. Release builds aren't debuggable. Hiding sensitive replies in
  the UI doesn't protect the file itself. App-level database encryption is still an open question (see
  PROGRESS.md).
- The audit log never stores message text, tool output, tokens, PINs or keys. Every field is capped at 80
  characters so content can't slip in by mistake.
- The user can delete one conversation or all history, and clear saved data, from the app. Each of these actions
  is itself recorded in the audit log, without content.

## Known gaps (tracked in PROGRESS.md)
- The database file isn't app-encrypted (see above).
- The fingerprint result isn't bound to a Keystore key (no `CryptoObject`). A rooted or instrumented device could
  fake it. This is acceptable for the current threat model (decision D15).
- Conversation titles in History come from the first question ("what's my net worth?"). They're not hidden, but
  answers are.
