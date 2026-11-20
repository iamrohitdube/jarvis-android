# Security notes

Phase 2 status. The full tier/PIN/biometric model (CLAUDE.md section 6) arrives in Phase 3.

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

## Prompt injection
The system prompt marks tool, email, web and document content as untrusted data. In Phase 1 every tool is
read-only, so nothing can act on injected instructions. Enforcement in code (confirmations for actions after
untrusted content) arrives with Tier 3 actions.

## Local data (Phase 2)
- Chat history, saved connector data, the audit log and the usage log are in a Room database in the app's private
  storage. Android's file-based encryption protects it while the phone is locked, other apps can't read it, and
  it's excluded from backup and device transfer.
- The database itself isn't encrypted with an app key (decision D14). Someone with the unlocked phone and USB
  debugging could read it from a **debug** build. Release builds aren't debuggable. App-level database encryption
  is an open question for Phase 3, alongside the PIN/biometric gate.
- The audit log never stores message text, tool output, tokens, PINs or keys. Every field is capped at 80
  characters so content can't slip in by mistake.
- The user can delete one conversation or all history, and clear saved data, from the app. Each of these actions
  is itself recorded in the audit log, without content.

## Known gaps (tracked in PROGRESS.md)
- Finance data is Tier 2 by spec, but there's no auth gate yet. Anyone holding the unlocked phone can ask
  "what's my net worth?" or open History until Phase 3.
