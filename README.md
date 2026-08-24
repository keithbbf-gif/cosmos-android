# COSMOS Voice (Android)

Native Android voice client for the COSMOS `/api/v1` API.

- **No Chrome, no Web Speech, no Google speech service.** Speech-to-text is
  [VOSK](https://alphacephei.com/vosk/), an open-source recognizer running
  entirely on-device — voice-in works **offline** once the model is installed.
- Voice-out is Android's built-in TextToSpeech.
- Pure client: talks to the existing COSMOS server (`GET /api/v1/status`,
  `POST /api/v1/voice`). No server changes.

## Getting the APK (built by GitHub Actions — no local toolchain needed)

1. Push this repo to GitHub. The workflow `.github/workflows/android.yml`
   runs on every push (and manually via the Actions tab → "Android APK" →
   Run workflow).
2. It sets up JDK 17 + the Android SDK + Gradle 8.7, generates the Gradle
   wrapper (the `gradle-wrapper.jar` binary is deliberately not committed),
   runs `./gradlew assembleDebug`, and uploads the APK.
3. When the run is green, open the run page → **Artifacts** →
   download **cosmos-voice-debug-apk** (a zip containing `app-debug.apk`).

## Sideloading onto the phone

1. Copy `app-debug.apk` to the phone (download it from the GitHub run page in
   the phone's browser is easiest — unzip if the browser kept it zipped).
2. Tap the APK. Android will ask to allow installs from that app
   (Settings → Apps → Special app access → **Install unknown apps** →
   enable for your browser/file manager).
3. Install. It is a debug-signed build — Play Protect may warn; choose
   "Install anyway."

## First run

1. Open **COSMOS Voice** → the setup panel shows a **Server base URL** field.
   For the LAN trial: `http://192.168.1.107:8791`. Later: the Tailscale URL.
   Leave the bearer token blank while the server runs `--no-auth`.
2. Tap **Connect / Test** — it GETs `/api/v1/status` and shows `ready` /
   `tree_id`.
3. Tap the big **MIC** button. On the very first tap the app downloads the
   VOSK model (see below) — a one-time ~40 MB download with a progress bar.
   Then grant the microphone permission when asked.
4. Speak. Pause → the finalized transcript is POSTed to `/api/v1/voice`.
   The reply's `spoken` text is read aloud and logged in the console.
   The `session_id` from the reply is carried forward automatically —
   the sid is the conversation.
5. If the server answers `needs_confirm`, a purple **CONFIRM** button
   appears. Nothing runs until you tap it — the confirm re-POSTs with the
   single-use `confirm_id` nonce. Never auto-runs.

## The VOSK model

- Model: `vosk-model-small-en-us-0.15` (~40 MB zip, ~50 MB unpacked).
- **Not bundled** in the repo or APK. On first mic use the app downloads it
  from the official URL
  `https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip`
  into app-private storage (`filesDir`) and unzips it.
- After that one download, speech recognition is **fully offline** — road
  use with no signal works.
- If the download fails (no internet at that moment), the mic button just
  retries the download next tap.

## Notes / trial caveats

- `android:usesCleartextTraffic="true"` is set so plain `http://` works for
  the LAN trial. When the server gets TLS or a Tailscale HTTPS URL, tighten
  this to a network security config.
- minSdk 26 (Android 8.0+), targetSdk 34. Kotlin + Jetpack Compose.
- Local builds: run `gradle wrapper --gradle-version 8.7` once (needs any
  installed Gradle) to generate `gradlew` + `gradle-wrapper.jar`, then
  `./gradlew assembleDebug`. Requires JDK 17 and the Android SDK
  (`ANDROID_HOME` set); CI does all of this for you.
