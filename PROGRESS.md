# PROGRESS

## Current: Phase 13, voice actions (D36), same branch `phase-12-notifications` (one PR for 12 + 13)
- [x] Code (2026-11-26): "call Mom" recognised on the phone (no Claude) or via Claude's `make_call`; contact
  lookup, "Which one?" for several matches, "Call Mom, number ending 2222? Say call."; only "call"/"dial"/"go ahead"
  or a tap places it. On screen: Call dialog + voice. Hands-free: spoken confirm. `TelecomManager.placeCall` with
  CALL_PHONE (Settings → Messaging → Phone calls), else the dialer opens. Logged + audited.
  New WhatsApp messages to any contact: full confirmation, then WhatsApp opens with the text typed; you tap send.
  **397 unit tests, 0 failures.**
- [ ] Device test: allow Phone calls; "call <contact>" in chat (dialog), by voice ("call"), with "Hey Jarvis" with
  the phone locked/in pocket (spoken confirm, then the call starts); "call Ravi" with two Ravis (asks which);
  "WhatsApp <contact> I'm on my way" with no notification from them (WhatsApp opens with the text).

## Phase 12, other app notifications (D35)
- [x] Code (2026-11-26): the notification listener also keeps non-chat apps' notifications (bank alerts, deliveries,
  reminders) in Room v4 `captured_notifications`, 3 days. Opt-in switch in Settings → Phone data (off by default;
  off deletes them), ignore list, Private mode stops capture. Skips chats, codes, ongoing/status notifications,
  secret ones, system apps. Tools `notifications_recent` / `notifications_search` (group NOTIFICATIONS, Tier 1),
  answered on the phone (`NotificationAnswers`); `private_data` kinds of the same names; consent feature
  "Other app notifications". **386 unit tests, 0 failures.**
- [ ] Device test: notification access on → switch on → wait for a bank/delivery notification → "what did I miss?",
  "notifications from <app>"; add the app to the ignore list and check new ones are skipped; switch off and check
  the answer becomes "turn it on"; check app names show properly (not package names).

## Phases 6-14 (D35: the old Phase 6 split into one phase per data source; D36: actions)
| Phase | What | Status |
|---|---|---|
| 6 | Finance: INDmoney off Anthropic, answers on the phone, recipes (D32) | Merged (#7). Device-tested |
| 7 | Email: Gmail read-only + triage | Merged (#7). Sign-in device-tested; triage needs a check |
| 8 | Calendar + Drive metadata | Merged (#7). Needs device test |
| 9 | Calls: call log + call recordings (D30) | Merged (#7). Needs device test |
| 10 | Messages: SMS + chat notifications + confirmed sending | Merged (#7). Needs device test |
| 11 | Consent + redaction, Private mode | Merged (#7). Needs device test |
| 12 | Other app notifications (D35) | Code done, this branch. Needs device test |
| 13 | Voice actions: calls, new WhatsApp messages (D36) | Code done, this branch. Needs device test |
| 14 | Email actions: reply/send Gmail (D36) | Next |

## Phases 6-11 log (built as the old Phase 6; its step numbers are kept below)
Step map: 2 = Phase 6, 3 = Phase 7, 4 = Phase 8, 5 = cancelled, 6 = Phase 11, 7 = Phase 9, 8 = Phase 10.
- [x] Step 1: research and plan (no code). D29 written; Google Cloud steps in docs/setup-keys.md section 3;
  CLAUDE.md sections 2 and 12 updated. **Waiting for the user's review and picks** (phone model, calendar source,
  LLM option).
- [x] Step 2: INDmoney off Anthropic (code done 2026-11-22; **272 unit tests, 0 failures**; not yet built or
  device-tested; not committed, at the user's request)
  - `McpClient` (Streamable HTTP, JSON or SSE replies, sessions) replaces `McpProbe` and `McpServerConfig`
  - net worth, holdings, SIPs and watchlist answered on the phone (`FinanceAnswers`), with no Claude call, free,
    offline from the last saved result
  - market questions: Claude gets the public tools as client tools and the phone runs them; personal tools blocked
  - personal turns never resent as history; `PrivateDataTest` checks real HTTP bodies for token and holdings
  - **device check needed:** INDmoney's real output format is undocumented. Templates match common field names;
    otherwise the reply lists the fields. Debug builds log the shape only (`adb logcat -s JarvisShape`).
  - device: portfolio answered "On device" (user, 2026-11-22). "Savings account balance" went to Haiku, so smart
    routing was added (D29): clear phrases on the phone, Claude hands the rest back via `private_data`.
    **279 unit tests, 0 failures**; APK SHA-256 `0bae585d…85ea89`, served on :8770
  - user chose option B: Claude categorises every question; `private_data` hands personal ones back to the phone;
    follow-ups keep the question, never the answer; phrase list only offline/over budget. **280 tests, 0 failures**
- [x] Step 3: Google sign-in + Gmail read-only (code done 2026-11-25; **297 unit tests, 0 failures**; not yet built
  or device-tested: needs the user's Google Cloud project, docs/setup-keys.md section 3)
  - `GoogleAuthorizer`: play-services-auth 22.0.0 Authorization API, scope `gmail.readonly` only. Play services
    holds the grant and hands out 1-hour tokens; the app stores no Google token (only a "linked" marker).
  - `GmailApi`: messages.list + messages.get `format=metadata` (From, Subject) and snippet. Never bodies/attachments.
  - `GmailConnector`: tools gmail_unread, gmail_recent (cached snapshots, offline with age label), gmail_search
    (sender/topic from Claude's `item`; offline it filters the saved latest list). 401 -> clear token, retry once,
    then "sign in again". Offers no tools to Claude.
  - `EmailFilter` (before caching/showing): one-time codes and 2FA mails always dropped; promotions excluded in the
    query; excluded senders editable in Settings (banks by default). Replies say how many were hidden.
  - `EmailAnswers`: on-phone templates (count, latest 5, search hits), sender names without addresses.
  - `private_data` kinds unread_email, recent_email, email_search; prompt updated. Email is Tier 1 (recent unlock).
  - Settings + setup wizard: Gmail card (sign in, test, sign out, excluded senders).
  - Tests: connector against a mock Gmail (metadata only, filter, retry, reauth), templates, filter, and an
    end-to-end test proving no Google token, sender, subject or snippet reaches Anthropic.
  - Not done (later): excluded Gmail labels (only senders/promotions now), "Private mode" switch (step 6).
  - Device (2026-11-25): sign-in and Gmail work on the phone. User feedback: answers listed every email.
  - **Triage added (2026-11-25, D29 "Email triage"):** important emails first (security, bills with amount and due
    date, government, people, jobs, bank, travel, orders); newsletters/social/promos only counted. Bank mail no
    longer hidden by default (OTPs still are). **310 unit tests, 0 failures.** Needs a device check with the real inbox.
- INDmoney answers fixed (2026-11-25): the device showed INDmoney wraps its data as JSON text inside {"result": "..."}
  (total_networth, total_invested, total_current_value, investments[asset_type, invested_value, current_value,
  return, return_percentage]). The reader unwraps it; net worth = total, invested, gain, 3 biggest asset types;
  bank questions use the Liquid asset type. Raw JSON is never shown. Settings → Data → "Copy data format (no
  amounts)" for future format checks without adb. **318 unit tests, 0 failures.**
- Recipe answers (D32, 2026-11-25): finance questions in any phrasing ("where am I bleeding money", "card dues",
  "large vs small cap", "gold exposure") answered from Claude's placeholder recipe over the data outline; the phone
  fills values; invented figures fall back to templates. Template fallback now lists the biggest asset classes.
  Chat: Copy button under replies, long-press copies your message, top-bar copies the whole chat (private replies
  flagged sensitive for the clipboard). **330 unit tests, 0 failures.** Needs device test.
- D33 (2026-11-25): unlocked phone needs no PIN for personal/sensitive reads (setting, on by default; answers stay
  on screen); voice match removed; spoken PIN fixes (mic hand-over, echo skip, shorter minimum, recognizer timeout,
  forgiving digits, spoken explanations). **337 unit tests, 0 failures.** Needs device test: net worth with the
  phone unlocked (no prompt), and the spoken PIN from the lock screen / pocket.
  - Device: net worth works without a PIN on the unlocked phone (user, 2026-11-25). Finance answers on an
    unlocked phone are now spoken too (user request).
- [x] Step 4: Calendar (CalendarContract) and Google Drive metadata search (2026-11-26). Needs device test.
- [~] Step 5: on-device summaries **cancelled** (2026-11-25, user: on-device model too slow, phone hung). Claude
  is the only brain; personal data still answered on the phone via `private_data` (D29 update).
- [x] Step 6: consent + redaction settings, Private mode (2026-11-26). Needs device test.
- [x] Step 7: call log, then call recordings with on-phone transcripts (D30, 2026-11-26). Needs device test.
- [x] Step 8: SMS / notification messaging with confirmed sending (CLAUDE.md section 7, 2026-11-26). Needs device test.
- **Phase 6 code complete (D34), 376 unit tests, 0 failures.** Device checklist:
  - Calendar: allow, "what's on today?", "when is my dentist appointment?"
  - Drive: sign in on the Drive card (add the Drive API + scope in Google Cloud first), "find the budget sheet"
  - Calls: allow (maybe "Allow restricted settings" first), "who called?", "missed calls", "last call with Mom"
  - Recordings: choose the dialer folder, Transcribe, "what did Rahul say about the deadline?"
  - Messages: allow SMS + notification access; "any WhatsApp messages?"; "text Mom I'm on my way" (check the
    full draft, "send it", the countdown, Cancel); reply to a WhatsApp with its notification showing
  - Privacy: Private mode on/off; allow one feature and check the first-send preview

## Next: Phase 14, email actions (D36): reply to and send Gmail by voice
Needs the `gmail.send` scope added to the user's Google Cloud project (docs/setup-keys.md section 3) and a new
Google sign-in for it.

## JARVIS gap list (what a real JARVIS would do that this one can't yet), kept current
| Gap | Where it fits |
|---|---|
| Reply to / send email by voice | Phase 14 (next) |
| Alarms, timers, reminders ("wake me at 6", "remind me to call Mom at 5") | Phase 19 (AlarmClock intents, WorkManager) |
| Create/move calendar events by voice | Phase 15 or 19 (calendar write, Tier 3 confirm) |
| Navigation by voice ("take me home") | Phase 5b (Maps intent; prototype pending) |
| Answer / reject incoming calls by voice, announce the caller | Phase 5b riding (ANSWER_PHONE_CALLS) |
| Read new messages aloud as they arrive (riding, headset) | Phase 5b / 18 |
| Media control ("play", "pause", "next song", volume) | Phase 19 (media session / AudioManager) |
| Phone settings ("torch on", "do not disturb", "Wi-Fi") | Phase 19 (only what Android allows apps; settings panels otherwise) |
| Open apps by voice ("open Swiggy") | Phase 19 (launcher intents) |
| Proactive alerts (bill due, meeting clash, big portfolio move) | Phase 18 Monitor |
| Morning briefing | Phase 18 |
| "What's this?" camera/screen | Phase 15.9 / 19 |
| Remember things about me | Phase 16 |

## Then: Phase 15, JARVIS features (D31, user-approved 2026-11-25; was "6c" before D35), one at a time
1 People view, 2 meeting prep, 3 missed-call follow-ups, 4 watch list, 5 night debrief, 6 location routines,
7 bills and subscriptions, 8 travel, 9 screen awareness + camera.

## Previous phase
**Phase 5: "Hey Jarvis" wake word.** Work is split into stacked pull requests:
- PR #3 `phase-5-wake-word`: wake word, pocket mode, spoken PIN.
- PR #4 `ui-jarvis-hud`: JARVIS HUD redesign.
- `wake-openwakeword`: openWakeWord engine, personal voice check, continuous conversation (D27, D28).

Latest build: **248 unit tests, 0 failures**. Tested on the phone over Wi-Fi adb and the laptop speaker (see below).
**Waiting for the user's own tests** of the spoken PIN with the phone locked and in a pocket, and of conversations.

## openWakeWord branch: verified on the phone (2026-11-21)
- [x] Speaker test with synthetic clips: 12/12 "hey jarvis" woke JARVIS; 0/10 near-misses woke it.
  - No recognizer beeps while idle.
  - vivo no longer turns on the proximity sensor.
  - Idle CPU ~0%.
- [x] "Hey Jarvis, what's the time?" in one breath answered "It's 1:48 PM."
- [x] The wake screen appears ~0.1-0.5 s after the wake.
- [x] Choosing "PIN" by voice reached the PIN step.
- [ ] Spoken PIN unlock with the user's voice (locked and pocket).
- [ ] Multi-turn conversation.
- [ ] "Not for me" learning.
- [ ] Battery over a few hours.

## Phase 5: done (code), see D21, D24-D28
- [x] openWakeWord "hey jarvis" engine (default) with a personal voice check and "Not for me" learning (D27)
- [x] Instant wake reaction; question in the same breath; continuous conversation; silent waiting (D27, D28)
- [x] Spoken PIN: voice match optional (default off), works locked and hands-free; 11 s answer window (D28)
- [x] Wake-word engine: sherpa-onnx keyword spotting (free, Apache-2.0, on-device, no account). Porcupine dropped
  because its free tier ended (June 2026). The library and model are downloaded and hash-checked by Gradle, not
  committed.
- [x] `ListeningService`: microphone foreground service, persistent notification with Stop, pauses during
  push-to-talk, resumes after each exchange; "Tap to resume" notification after a reboot
- [x] Proximity sensor: one reading on wake; in-hand opens the wake screen, pocket runs audio-only
- [x] Wake screen (`WakeActivity`): over other apps and the lock screen, turns the screen on; animation (orb
  bloom, voice-reactive aurora, edge light, live words); tap to stop talking, ✕ to close; spoken
  "PIN or fingerprint?"
- [x] Pocket mode: Bluetooth headset routing (setCommunicationDevice); private questions refused out loud
  (needs the phone in hand)
- [x] Settings → "Hey Jarvis": toggle, "Pop up over other apps", "Don't stop in the background"
- [x] Unit tests for pocket-mode access, proximity reading, and a full hands-free exchange with fakes
- [x] "Anytime, no touch" (D22): JARVIS can be the default digital assistant. Android keeps it running and
  restarts listening after a reboot with no tap; the assist gesture opens the wake screen too
- [x] Standby: silence gate, so the wake-word model runs only while someone is talking (D22)
- [x] Spoken PIN + voice match (D23): on-device only; 70 MB models downloaded on demand and hash-checked; voice
  enrolment needs fingerprint; a stranger's voice never gets the PIN checked; typing always works
- [x] Keyword model didn't recognise the user's voice; tried software gain and a microphone choice (D24)
- [x] Default detector is now the phone's own speech recognizer (D25); keyword model kept as "beta"
- [x] Time, date and battery answered on the phone with no Claude call; local time sent with every question (D25)
- [x] Accent training: "Teach JARVIS your voice", n-best + near-spelling matching, learned phrases (D26)
- [x] Wake sound: Silent / Chime / Hum / custom phrase / chime + phrase, with "Play it" (D26)

## Phase 5: please test on your phone (after the build)
0. New (D26): Settings → Hey Jarvis → Wake sound (pick one, "Play it"); "Teach JARVIS your voice" → Start
   training; then lock the phone and check how often "Hey Jarvis" is caught, on the phone mic and the C40.
1. Settings → "Hey Jarvis": turn it on (allow mic + notifications), tap Allow on "Pop up over other apps" and
   "Don't stop in the background". A "Listening for Hey Jarvis" notification appears.
2. Phone on the table, screen off: say "Hey Jarvis, what's the capital of France?" (pause after "Hey Jarvis").
   The screen lights up with the animation, shows your words, answers out loud, then fades away.
3. Phone in hand, unlocked, in another app: same thing over the app.
4. "Hey Jarvis" → "what's my net worth?": it asks "PIN or fingerprint?" out loud; check fingerprint works on the
   wake screen (also over the lock screen).
5. Phone in a pocket with the C40 connected: "Hey Jarvis, what time is it in London?". It should answer
   through the helmet with the screen off. Then ask for your net worth: it should say to take the phone out.
6. False triggers: leave it on during TV or a conversation for a while; tell me if it fires on its own or
   misses you often (sensitivity is tunable).
7. Battery: leave it on for a few hours and check Settings → Battery for JARVIS.
8. Reboot the phone: a "Tap to resume Hey Jarvis" notification should appear; tap it.
9. While "Hey Jarvis" is on, the chat mic button still works.
10. Settings → "Hey Jarvis" → Default digital assistant → choose JARVIS. Reboot: "Hey Jarvis" should work
    without opening the app or tapping anything.
11. Security → Spoken PIN → Download (70 MB), then Set up voice match (fingerprint, then read 3 digit phrases).
    In a voice chat: "what's my net worth" → say "PIN" → "Say your PIN." → say it. It should unlock.
    Ask someone else to say your PIN: it should say "That didn't sound like you" and switch to fingerprint.
Design polish: merged (PR #1, 2026-11-21).

## Phase 4: done (code), see D20
- [x] Mic button in the chat box (when it's empty): tap to talk, live transcript, animated orb that follows your voice
- [x] Speech recognition: on-device preferred (audio stays on the phone), falls back if the language isn't installed
- [x] Spoken replies, sentence by sentence while streaming; music ducks; speed setting
- [x] Barge-in: tap the mic or "Speaking · tap to stop" to interrupt
- [x] Quiet mode: speaker toggle in the top bar (screen only)
- [x] Screen-only rule: fingerprint answers are never read out ("Done, it's on your screen."); nothing sensitive
  spoken while the phone is locked
- [x] Spoken "PIN or fingerprint?" with a spoken answer (PIN still typed: spoken PIN needs voice match, later phase)
- [x] "lock" works by voice
- [x] Settings → Voice; onboarding step 2 (microphone + test voice)
- [x] 24 new unit tests (sentence splitting, speech cleanup, spoken choice, voice-session rules, event order)

## Phase 4: device checklist (user: "works", 2026-11-21)
1. Update; the wizard shows the new "Talk to JARVIS" step: allow the microphone, tap Test voice.
2. Tap the mic, ask "what's the capital of France?": your words appear live, then the answer is spoken.
3. Ask something long and tap the mic while it's talking: it stops and listens again.
4. Tap the speaker icon (quiet mode) and ask by voice: the answer appears but isn't spoken.
5. "What's my net worth?" by voice: it asks "PIN or fingerprint?" out loud; say "fingerprint": after the
   fingerprint, it says "Done, it's on your screen." and doesn't read the number. Say "PIN" another time: type
   the PIN; the answer is spoken.
6. Play music, then ask by voice: the music dips while JARVIS talks.
7. Say "lock": it says "Locked."
8. Settings → Voice: change speed, Test voice; turn on-device recognition off and on.
Phase 3: Security. Done and device-tested (2026-11-20).

## Design polish: done (code), see D19
- [x] Brand theme (navy + blue/cyan/violet), shapes, type; JARVIS orb identity
- [x] Adaptive app icon (+ themed monochrome) and animated launch screen
- [x] Chat: navigation drawer, orb avatar, bubbles, status chips, typing dots, pill composer with Send→Stop,
  greeting + suggestion chips, animated items, screen transitions
- [x] Settings / Security / Onboarding / Usage / History / Audit redesigned (cards, icon tiles, chips,
  "Saved ✓" buttons, segmented policy control, hero usage card)
- [x] Fix: PIN dialog shows "Saving…" and closes itself on success
- [x] Fix: fields scroll above the keyboard (Settings, Security, Onboarding, Chat)
- [x] Change: unlocked sensitive replies stay visible until an explicit "lock" (not on timeout/screen-off)
- [x] 2 new unit tests for the reveal behaviour
- [x] Compiles; **155 unit tests, 0 failures**; debug APK built (SHA-256 `6dc09957…b5daa41`)
- [x] Swapped `material-icons-extended` (debug APK ~76 MB, ~20 min builds) for `AppIcons`: the 40 icons used,
  from Google's Material Icons (Apache-2.0)
- [ ] Release (R8) check before the PR

## Design polish: device checklist (user: "looks good", 2026-11-21)
1. App icon on the home screen (and themed icon if you use Material You themed icons); launch animation.
2. Chat: drawer (☰), recent chats, New chat (+); suggestion chips; reply chips; Stop while streaming.
3. Settings → Data on this phone: tap the minutes box; it should move up above the keyboard.
4. Security → Change PIN: "Saving…" then the dialog closes by itself.
5. Unlock an old net-worth reply, wait 3 minutes or turn the screen off and on: it stays visible. Type "lock":
   it hides again.
6. Light mode (phone in light theme) looks right too.
Phase 2: done and device-tested (2026-11-20). Phase 1: done and device-tested (2026-11-20).

## Phase 3: done
- [x] Access tiers enforced in code (`AuthCoordinator` gate in `AgentLoop`, before saved data or connectors):
  open / personal (recent unlock) / sensitive (PIN or fingerprint) (D18)
- [x] PIN: set/change/remove in Settings → Security (needs fingerprint or screen lock first), 4-8 digits, weak PINs
  rejected, salted PBKDF2 600k hash in the encrypted store, never sent to Claude or logged (D16)
- [x] 3-strike lockout: only well-formed wrong PINs count; persisted; only fingerprint/screen lock resets it;
  notification on lockout
- [x] Fingerprint via androidx.biometric 1.4.0-rc01 (`registerForAuthenticationResult`); Android 10 handled (D15)
- [x] "That's sensitive. PIN or fingerprint?" with 6 s default to fingerprint; "…, fingerprint" / "…, use PIN"
  pre-empts it
- [x] Sessions: sensitive 2 min, personal 5 min (editable); end on screen-off, "lock", Lock now
- [x] Screen-only delivery: fingerprint answers are flagged "Shown on screen only" (voice in Phase 4 honours it)
- [x] Sensitive replies hidden in Chat until you unlock (History can't bypass the gate); DB v1→v2 auto-migration
- [x] Tool policy: Strict / Smart (default) / Auto + per-tool Default/Allow/Ask/Deny (D17)
- [x] Audit: every auth event, policy change and lock; no secrets
- [x] Onboarding step 3 (Security) added; existing installs see the wizard once more to set a PIN or skip
- [x] 50 new unit tests: hasher, PIN rules, lockout persistence/reset, sessions, policy matrix, method parsing,
  the full PIN/fingerprint flow with virtual time, the agent gate, and a real v1→v2 database migration

## Phase 3: verified on PC (2026-11-20)
- `gradlew testDebugUnitTest`: **153 tests, 0 failures**.
- `assembleDebug` and `assembleRelease` (R8) build. Debug APK ~42.6 MB, release ~4.9 MB (biometric library +
  its fragment dependency add ~0.9 MB). Test APK SHA-256 `28f7f286…4445d0c`.
- Fixed after that build (compiles; ships with the Phase 4 APK): when the PIN locks mid-prompt, the fingerprint
  prompt's subtitle now says "PIN is locked, use fingerprint." In the test APK the message isn't shown there.

## Phase 3: device checklist (user reports all working, 2026-11-20; APK SHA-256 `28f7f286…4445d0c`)
1. Update over the existing app. The setup wizard appears once with the new **Security** step: tap **Set PIN**,
   confirm with your fingerprint, enter a PIN twice (1234 or 1111 should be refused). Allow notifications if asked.
2. Open History: your old chats are still there (database upgrade worked).
3. Ask "what's my net worth?" → "That's sensitive. PIN or fingerprint?" Wait 6 s without tapping: the fingerprint
   prompt should open by itself. After it, the reply says "Shown on screen only".
4. Ask again within 2 minutes: no prompt. Turn the screen off and on, ask again: it prompts again.
5. Choose **PIN**, enter a wrong PIN twice ("2 tries left", "1 try left"), then a typo like "12" (no penalty),
   then the right PIN.
6. Lockout: enter 3 wrong PINs. You should get "PIN is locked, use fingerprint." plus a notification; fingerprint
   unlocks it, and Settings → Security no longer says LOCKED.
7. Close and reopen the app: the old net-worth reply shows "Sensitive. Hidden until you unlock." Tap **Unlock**.
8. Type "lock": it says "Locked." without calling Claude; the next net-worth question prompts again.
9. "net worth, fingerprint" goes straight to the fingerprint prompt.
10. Settings → Security → set "Total net worth…" to **Deny**: asking net worth says it's turned off (no cost on
    the Usage screen). Set it back to Default.
11. Audit log → Auth filter: prompts, PIN wrong/ok, lockout, reset, lock, with no PIN digits anywhere.
12. If your phone is Android 10: the fingerprint prompt shows "Use PIN" instead of your phone's screen lock option.

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
- Fingerprint result not bound to a Keystore key (no CryptoObject); see D15.
- Database isn't encrypted with an app key; relies on Android sandbox + file-based encryption (D14; revisit before public release).
- After the app process is killed, it opens a new chat (old chats are in History).
- No Diagnostics screen or QR key import yet.
- No context compaction: the last 8 turns (clipped) are resent (compaction is Phase 16).
- Placeholder launcher icon (system default).

## Open questions for you
0. **Database encryption:** still open. Sensitive replies are now hidden in the UI, but the database file isn't
   app-encrypted (SQLCipher adds native libraries and APK size). Recommendation: keep relying on Android's
   protection for now and revisit before the public release (Phase 20).
1. **Licence:** Porcupine's Android SDK is Apache-2.0 (needs a per-user AccessKey). Apache-2.0 for this repo?
2. **Project folder:** the spec shows `jarvis-android/`; I built at the workspace root (`AI Assistent/`). Fine, or rename?
3. **Effort/latency:** chat runs at `effort: low` (cheapest). Kept for now.
4. **Routing:** now Haiku 5.5 everywhere by default (D11). Revisit after device testing if answers feel weak.

## Next: Phase 5 (wake word), with your requests (2026-11-21)
- "Hey Jarvis" wake word, always-on in a foreground service (persistent notification, on/off toggle,
  battery-optimisation prompt). Engine: whichever is free and best optimised; compare Porcupine vs openWakeWord
  (cost, battery, accuracy, licence) before building and record the choice.
- Full-screen wake animation (fluid, Siri-like feel but JARVIS's own design: orb blooms in, light ripples with
  your voice, pulses while answering).
- Proximity sensor: show the animation and wake the screen only when the phone is NOT in a pocket.
- In a pocket: screen stays off, conversation is audio only; if a Bluetooth headset (e.g. C40) is connected, use
  its mic and speaker (overlaps Phase 5b audio routing).
- Note from testing: the C40 woke up when voice was used; the app has no Bluetooth code, so this was Android
  routing assistant/mic audio to the paired headset. Proper helmet handling is Phase 5b.
**Starts after Phase 4 is device-tested and its pull request is open.**
