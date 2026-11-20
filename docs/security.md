# Security notes

Phase 1 status. The full tier/PIN/biometric model (CLAUDE.md section 6) arrives in Phase 3.

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
  - the text of earlier turns (up to 20)
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

## Known gaps (tracked in PROGRESS.md)
- Finance data is Tier 2 by spec, but Phase 1 has no auth gate yet. Anyone holding the unlocked phone can ask
  "what's my net worth?" until Phase 3.
- No audit log yet (Phase 2).
