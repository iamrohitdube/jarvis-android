# JARVIS for Android

A hands-free personal AI assistant for Android, powered by Claude. It runs on the phone only, with no backend
server. You bring your own Anthropic API key.

> **Status: Phase 3 (security).** You can chat with Claude by text, with streamed replies, and ask about your
> INDmoney portfolio ("what's my net worth?") through INDmoney's MCP server. Chats are saved and searchable on the
> phone, recent portfolio data is reused with a "last updated" label, there's an audit log, and sensitive
> answers need your JARVIS PIN or fingerprint. There's no voice,
> wake word or riding mode yet. See [PROGRESS.md](PROGRESS.md) and the phase plan in [CLAUDE.md](CLAUDE.md).

## Features in this build
- Chat screen with streaming replies, a Stop button and plain-language errors with a fix button.
- Secure settings. The Anthropic key and connector tokens are encrypted with the Android Keystore.
- INDmoney through Claude's MCP connector:
  - browser sign-in (OAuth 2.1 + PKCE)
  - read-only scopes
  - automatic token refresh
- Cost control:
  - only the relevant tools are sent per request
  - Claude Haiku 5.5 by default, the cheapest model (Sonnet 5.5 is optional in Settings)
  - short replies by default, thinking off for simple questions, small output caps
  - only the last few turns are resent, and long ones are clipped
  - prompt caching
- Usage screen showing tokens, estimated cost and latency per request. The monthly budget defaults to $5: it warns
  at 80% and stops requests when used up (both configurable).
- A first-launch setup wizard (Anthropic key, connectors).
- Chat history on the phone: searchable, deletable one by one or all at once.
- Saved portfolio data: reused for 15 minutes (configurable) to save a connector call, always labelled
  "updated 10:42" or "saved data from 10:30". Say "refresh" to fetch it live.
- Audit log of requests, tool calls, sign-in and security events (no message content), kept 180 days.
- Security:
  - sensitive answers (net worth, holdings) ask "PIN or fingerprint?"
  - fingerprint answers are shown on screen only
  - 3 wrong PINs lock the PIN until a fingerprint unlocks it
  - short sessions that end when the screen turns off or you type "lock"
  - per-tool Allow / Ask / Deny

## Setup
1. Install the app: see [docs/install.md](docs/install.md).
2. Open it. The wizard asks for your Anthropic API key and tests it. **Set a monthly spend limit on your
   Anthropic key** in the Anthropic Console.
3. Optional: sign in to INDmoney (see [docs/setup-keys.md](docs/setup-keys.md)).

## Permissions
| Permission | Why |
|---|---|
| Internet | To call the Anthropic API and connector sign-in/refresh endpoints. |
| Notifications (Android 13+, optional) | Only to alert you if your JARVIS PIN gets locked. Asked when you set a PIN. |
| Biometrics (no permission prompt) | Fingerprint or screen lock for sensitive answers, through Android's own prompt. |

Microphone, notifications, SMS and the others arrive with the features that need them,
each with an in-app explanation.

## Privacy and security
- No analytics or telemetry. Usage stats, chat history, saved data and the audit log stay on the phone and are
  excluded from backups.
- Your messages, plus data from connectors you use, are sent to Anthropic's API to produce answers.
- Secrets never go in the repo, logs or backups. See [docs/security.md](docs/security.md).

## Building
Requires JDK 17 and the Android SDK (platform 37.2, build-tools 36).
```
gradlew testDebugUnitTest   # unit tests
gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```
CI (GitHub Actions) runs the unit tests, builds the APK and runs a gitleaks secret scan.

## Docs
- [Architecture](docs/architecture.md)
- [Decisions](docs/decisions.md)
- [Security](docs/security.md)
- [Keys and connectors setup](docs/setup-keys.md)
- [Install](docs/install.md)

## Licence
Not chosen yet (see PROGRESS.md open questions).
