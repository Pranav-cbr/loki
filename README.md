# LOKI — always-available Android voice assistant

Native Kotlin + Jetpack Compose. Package `com.loki.assistant`, minSdk 26, target/compile SDK 35.

> This source was written without access to an Android SDK, so it has **not been compiled or run**.
> Expect to fix a few small compile or API issues on first sync, and test on a real phone.

## Build & run
1. Open the `LOKI` folder in Android Studio (Koala or newer). Let Gradle sync (it downloads Gradle 8.9).
2. **Add the wake-word model** (APK won't listen without it, push-to-talk still works):
   download `vosk-model-small-en-in-0.4` (~36 MB, Apache-2.0) from https://alphacephei.com/vosk/models,
   unzip it, and copy its *contents* into `app/src/main/assets/vosk-model/` so you see `am/`, `conf/`, `graph/` directly inside.
3. Run on a physical device (emulators have poor microphone/recognizer support). Build > Build APK(s) for an installable APK.

## How it works
`Vosk (offline, restricted grammar: "hey loki"...)` → stop Vosk to free the mic → TTS "Yes?" →
`SpeechRecognizer` (one command, en-IN or hi-IN) → `CommandRouter` → TTS reply → resume Vosk.
Exactly one component holds the microphone at a time; `CommandRecognizer` refuses overlapping sessions.

Built-in commands: torch on/off, battery, time, date, open app, dial a number, web search, "stop listening".
Anything else goes to your optional backend (`/backend`, Node 18+, key stays server-side, bearer token + rate limit, HTTPS only).

## Wake-word notes (be aware)
* Vosk with a restricted grammar is a keyword *spotter*, not a purpose-trained one. "Loki" may not be in the small model's
  vocabulary, which is why the defaults also include "hey lowkey / hey low key". Tune the phrases in Settings and test in a quiet room.
  Expect more false triggers/misses than a trained wake word.
* Better accuracy: **Picovoice Porcupine** (train "Hey Loki" in their console, get a `.ppn` + AccessKey; free tier is for
  personal/non-commercial use, commercial use needs a paid licence) or **openWakeWord** (Apache-2.0 code; check the licence of any
  pretrained model you use). Implement `WakeWordEngine` (`WakeWord.kt`) and swap it in `LokiService.startWake()`.
* The CPU cost of continuous recognition is real: expect noticeable battery drain. Mitigations built in: pause below a battery
  threshold, "only while charging", and an opt-in wake lock (off by default, 10-minute timeout, refreshed once a minute).

## Android limits you cannot code around
* Microphone foreground service must be started while the app is visible (Android 12+/14). After a reboot, LOKI only posts a
  "tap to resume" notification (Android 14 forbids starting a microphone service from `BOOT_COMPLETED`).
* Not guaranteed: survival after Force stop, swipe-kill on aggressive OEMs, or low-memory kills. `START_STICKY` asks the system
  to restart it, and a restart from the background may be refused.
* Xiaomi/Oppo/Vivo/Realme/OnePlus/Samsung/Huawei add extra killers: set Battery → **Unrestricted**, enable Autostart, lock the app in Recents.
  The app has buttons to the battery settings and https://dontkillmyapp.com.
* Android 10+ blocks launching other apps from the background. When the app isn't visible, "open WhatsApp" posts a tap-to-open
  notification instead. (`SYSTEM_ALERT_WINDOW` can lift this but is intrusive, so it isn't used.)
* Another app using the mic (calls, recording) can steal it; Android 11+ also shows the mic indicator dot. That is intended.
* `SpeechRecognizer` from a service works on most Google-services phones but some OEM recognizers refuse background use; then use push-to-talk.
* Hindi: command recognition is hi-IN; the wake phrase itself is English. TTS replies in Hindi if the phone has Hindi voice data installed.

## Privacy
Wake-word audio never leaves the device. After the wake phrase, the system speech recognizer handles one utterance
(`PREFER_OFFLINE` is requested, but Google's recognizer may use the network). The backend receives only recognised text.
The backend token is stored in plain DataStore, so use a per-device revocable token and treat it as low-privilege.
