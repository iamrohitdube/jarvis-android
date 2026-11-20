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

## Coming later
- QR import from a PC page (planned, not built yet).
- Google (Gmail, Calendar, Drive) OAuth setup (Phase 6).
