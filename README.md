# JARVIS for Android

A hands-free personal AI assistant for Android, powered by Claude. It runs on the phone only, with no backend
server. You bring your own Anthropic API key.

> **Status: Phase 5 ("Hey Jarvis" wake word).** Say "Hey Jarvis" with the phone on the table, locked or in your
> pocket, and have a spoken conversation with Claude. You can also chat by text. JARVIS can answer questions about
> your INDmoney portfolio ("what's my net worth?") through INDmoney's MCP server, after a spoken PIN or your
> fingerprint. Riding mode comes next. See [PROGRESS.md](PROGRESS.md) and the phase plan in [CLAUDE.md](CLAUDE.md).

## Screenshots
<p align="center">
  <img src="docs/screenshots/home.png" width="280" alt="JARVIS home screen: greeting, animated arc-reactor orb, Talk to JARVIS button and quick actions">
  &nbsp;&nbsp;
  <img src="docs/screenshots/listening.png" width="280" alt="Push-to-talk: the orb and HUD rings react while JARVIS listens">
</p>

## Features in this build
- **"Hey Jarvis", hands-free:**
  - A small always-on wake model listens on the phone: no sounds, low battery, and no audio leaves the phone.
  - The screen reacts at once: a full-screen black HUD with an animated arc-reactor orb, even over the lock screen.
  - You can ask in one breath: "Hey Jarvis, what's the time?"
  - Conversations continue until you say "thanks" or stay quiet for 15 seconds.
  - In a pocket, JARVIS talks through your Bluetooth headset with the screen off.
- **Teach JARVIS your voice:** a 2-minute on-phone training so it catches your accent and ignores conversation.
  When it wakes by mistake, tap "Not for me" and it learns from that.
- **Voice everywhere:** push-to-talk in chat, spoken replies sentence by sentence, barge-in, quiet mode, and a
  customisable wake sound.
- **Spoken PIN:** say "PIN", then your PIN, even with the phone locked or in a pocket. It's recognised on the phone.
  Checking that it's your voice is optional.
- **Instant answers:** the time, date and battery are answered on the phone, with no API call.
- JARVIS HUD design: always dark, arc-reactor blue, frosted-glass cards.
- Chat screen with streaming replies, a Stop button and plain-language errors with a fix button.
- Secure settings. The Anthropic key and connector tokens are encrypted with the Android Keystore.
- INDmoney, called by the phone itself (D29). Your token, net worth and holdings never go to Anthropic:
  - portfolio questions ("net worth", "my SIPs", "holdings") are answered on the phone, free, and work offline
    from the last saved result
  - market questions (stock prices, funds) go to Claude, which asks the phone to look them up
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
| Microphone | Push-to-talk: JARVIS listens only after you tap the mic. Speech is recognised on the phone when supported. |
| Foreground service (microphone) | "Hey Jarvis": listens on the phone for the wake word while the notification shows. Off by default. |
| Display over other apps (optional) | Lets the "Hey Jarvis" screen pop up over other apps and the lock screen. |
| Battery optimisation exemption (optional) | Stops battery savers from killing the wake-word listener. |
| Modify audio settings | Routes pocket conversations through your Bluetooth headset. |
| Default digital assistant (optional, you choose it in Android Settings) | Keeps "Hey Jarvis" working any time, even after a restart, with no taps. |
| Wake model (download, 3.7 MB) | The "hey jarvis" detector (openWakeWord; models are CC BY-NC-SA 4.0, free for personal, non-commercial use). |
| Spoken PIN models (optional download, ~70 MB) | Digit recognition and optional voice match, entirely on the phone. |
| Run at startup | Only posts a "tap to resume listening" notification after a reboot. |
| Notifications (Android 13+, optional) | Only to alert you if your JARVIS PIN gets locked. Asked when you set a PIN. |
| Biometrics (no permission prompt) | Fingerprint or screen lock for sensitive answers, through Android's own prompt. |

Microphone, notifications, SMS and the others arrive with the features that need them,
each with an in-app explanation.

## Privacy and security
- No analytics or telemetry. Usage stats, chat history, saved data and the audit log stay on the phone and are
  excluded from backups.
- Your messages are sent to Anthropic's API to produce answers. Personal data (portfolio, and later email,
  calendar and files) is answered on the phone and isn't sent to Anthropic, nor are connector tokens (D29).
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

## Credits
- Wake word: [openWakeWord](https://github.com/dscripka/openWakeWord) by David Scripka. The code is Apache-2.0; the
  pre-trained models are CC BY-NC-SA 4.0 and are downloaded by the app, not included here.
- On-device speech: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0), Moonshine (MIT), 3D-Speaker
  ERes2Net (Apache-2.0). TensorFlow Lite runtime: [LiteRT](https://ai.google.dev/edge/litert) (Apache-2.0).

## Licence
Not chosen yet (see PROGRESS.md open questions). The wake model's non-commercial licence applies to the downloaded
models, not to this code.
