# Installing JARVIS on your phone

## Option A: USB (developer)
1. On the phone: Settings → About phone → tap **Build number** 7 times. Then Settings → System → Developer options
   → enable **USB debugging**.
2. Connect it by USB and accept the "Allow USB debugging?" prompt.
3. On the PC, in the project folder:
   ```
   gradlew.bat installDebug
   ```
   or install a built APK with `adb install -r app\build\outputs\apk\debug\app-debug.apk`.

## Option B: Wireless debugging (Android 11+)
1. Developer options → **Wireless debugging** → **Pair device with pairing code**.
2. `adb pair <ip>:<pair-port>` (enter the code), then `adb connect <ip>:<port>`.
3. `gradlew.bat installDebug`.

## Option C: APK from GitHub
CI uploads `jarvis-debug-apk` as an artifact on every push. Download it, open it on the phone, and allow
"Install unknown apps" for your browser or file manager when asked. Signed release APKs on GitHub Releases come in Phase 10.

## Requirements
Android 10 (API 29) or newer.
