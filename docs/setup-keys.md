# Setting up keys

Everything is entered on the phone. Nothing goes in files or in this repo.

## 1. Anthropic API key
1. On a PC or the phone, open https://console.anthropic.com → **API keys** → **Create key**.
2. **Set a monthly spend limit** (Console → Billing → Limits). JARVIS's in-app budget is only a local estimate.
3. In JARVIS: first-launch wizard step 1 (or Settings → Anthropic API key). Paste the key and tap **Test & save**.
   The test is a free `GET /v1/models` call. The key is saved, encrypted, only if the test passes.

## 2. INDmoney
1. In JARVIS: wizard step 4 (or Settings → INDmoney) → **Sign in**.
2. A browser tab opens INDmoney's sign-in page (mobile number, OTP, MPIN) and a consent screen. JARVIS never sees
   those credentials.
3. After you approve, the browser returns to JARVIS. Tap **Test** (a free MCP call to INDmoney, with no Claude tokens).
4. If the browser doesn't return to the app, or INDmoney rejects the sign-in request, use **Advanced: paste a token**.
   Pasted tokens can't be refreshed.

From Phase 6 (D29), the app talks to INDmoney itself. The INDmoney token is only ever sent to INDmoney, never to
Anthropic.

## 3. Google (Gmail and Drive), one-time PC setup (Phase 6, D29)
You create your own small Google Cloud project. It is free and takes about 10 minutes. JARVIS has no server: the
phone gets its Google access through Google Play services and talks only to Google.

Calendar doesn't need this: JARVIS reads the calendar already synced on your phone (it asks for the Calendar
permission). You only need this section for Gmail and Drive.

Google renames console menus from time to time. If a label below doesn't match, look for the nearest one.

### 3.1 Create the project and turn on the APIs
1. Open https://console.cloud.google.com and sign in with the Google account whose Gmail and Drive JARVIS should read.
2. Top bar → project picker → **New project**. Name: `jarvis-personal`. Location: **No organization**. **Create**,
   then select the new project.
3. ☰ → **APIs & Services** → **Library**. Search for and **Enable** each of:
   - **Gmail API**
   - **Google Drive API**

### 3.2 Consent screen (Google Auth Platform)
1. ☰ → **APIs & Services** → **OAuth consent screen** (opens **Google Auth Platform**) → **Get started**.
2. **App information:** App name `JARVIS (personal)`, User support email = your own Gmail. **Next**.
3. **Audience:** **External**. **Next**.
4. **Contact information:** your own Gmail. **Next**. Agree to the policy → **Continue** → **Create**.
5. **Data Access** → **Add or remove scopes**. Tick (or paste into "Manually add scopes"):
   - `https://www.googleapis.com/auth/gmail.readonly`
   - `https://www.googleapis.com/auth/drive.metadata.readonly`
   - `https://www.googleapis.com/auth/gmail.send` (only if you want JARVIS to send and reply to email, D36)

   **Update** → **Save**. Google marks the first two as *restricted* and `gmail.send` as *sensitive*. That's
   expected (see 3.4). Already set up? Come back here and add `gmail.send`; nothing else changes.
6. **Audience** → **Test users** → **Add users** → your own Gmail → **Save**.
7. **Branding** (needed before **Publish app** in 3.4 is enabled). Leave **App logo** empty: a logo needs Google's
   review. Fill in the links (the project's pages, served by GitHub Pages from the `gh-pages` branch):
   - Application home page: `https://iamrohitdube.github.io/jarvis-android/`
   - Application privacy policy link: `https://iamrohitdube.github.io/jarvis-android/privacy.html`
   - Application terms of service link: `https://iamrohitdube.github.io/jarvis-android/terms.html`
   - Authorized domains: `iamrohitdube.github.io`
   - Developer contact information: your own Gmail. **Save**.

   If you fork JARVIS, publish your own copies of these pages and use your own links.

### 3.3 Android client (no secret is created)
1. Get the signing fingerprint of the APK you install. For the debug build made on this PC, in Git Bash:

   ```
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
   ```

   Copy the **SHA1** line. (`keytool` comes with any JDK: Android Studio's `jbr/bin`, or the Microsoft JDK already
   on PATH on the dev PC.) A release build later needs
   its own SHA-1, added as a second Android client.
2. **Google Auth Platform** → **Clients** → **Create client**.
   - Application type: **Android**
   - Name: `JARVIS debug`
   - Package name: `dev.jarvis`
   - SHA-1 certificate fingerprint: paste it
   - **Create**. There is no client secret, and nothing needs to be copied into the app.

### 3.4 Avoid signing in again every week
In **Testing** status, Google ends a test user's access **7 days** after consent, so JARVIS would ask you to sign
in again every week. For a personal app, switch to production without verification:

1. **Google Auth Platform** → **Audience** → **Publish app** → **Confirm**. Status becomes **In production**.
2. Don't submit for verification. Unverified apps with restricted scopes are allowed for **personal use, under
   100 users**.
3. The first time you sign in on the phone, Google shows **"Google hasn't verified this app"**. Tap **Advanced** →
   **Go to JARVIS (personal) (unsafe)** → tick the access boxes → **Continue**. This warning is about your own
   project. It appears once, not every week.
4. If you had signed in while the app was in Testing, sign out in JARVIS and sign in again after publishing, so
   the 7-day grant is replaced.

### 3.5 On the phone
Settings → **Gmail** → **Sign in** → pick your account → approve. JARVIS then runs a free test call (it reads the
latest inbox headers, with no Claude call). **Google Drive** has its own **Sign in** button: Gmail and Drive are
approved separately, so adding Drive later never interrupts Gmail. For Drive, make sure the **Google Drive API** is
enabled (3.1) and the `drive.metadata.readonly` scope is added (3.2). To disconnect fully: **Sign out** on each card,
and optionally https://myaccount.google.com/connections → JARVIS → **Delete all connections**.

**Sending email (D36):** after Gmail works, the Gmail card shows **Send and reply by voice** → **Allow sending**
→ approve. This asks only for `gmail.send` (send, not read or delete), separately from reading, so it never breaks
Gmail reading. Every email is shown in full and needs "send it", like an SMS.

## Coming later
- QR import from a PC page (planned, not built yet).
