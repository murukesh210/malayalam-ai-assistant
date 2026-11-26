# Malayalam AI Assistant (4 GB RAM Offline Android App)

A lightweight, dark-themed, 100% offline Android Kotlin assistant (`minSdk 26`, `compileSdk 35`, `targetSdk 35`, Jetpack Compose + Material 3) modeled after the smartphone **Gemini app** and engineered specifically for **4 GB RAM Android smartphones**.

## Key Architecture Highlights

1. **Strict 4 GB RAM Discipline ("One Model at a Time")**
   - Central `ModelCoordinator` enforces a global coroutine `Mutex`: **only ONE AI model** is ever resident in RAM at any instant.
   - Before loading Model B, Model A is deterministically closed/released and cleaned up.
   - **45-second idle auto-unload** timer + immediate release in `Application.onTrimMemory(...)` and `VoiceInteractionSession.onHide()`.
   - LiteRT-LM runs strictly on **CPU XNNPACK (4 threads)**, `maxNumTokens = 1024`, `maxOutputTokens = 160`, with unused vision/audio backends disabled to prevent GPU driver OOM crashes on budget chipsets.

2. **4 GB-Safe Post-Install Models (0 MB Bundled in APK)**
   - **Background Matcher:** `EmbeddingGemma 2 Text (270M)` (`.litertlm`, `~165 MB` disk, `~191 MB` RAM) + instant TF-IDF / Manglish n-gram fallback before download.
   - **Daily Actions Router:** `FunctionGemma (270M)` (`functiongemma-270m-ft-mobile-actions.litertlm`, `~289 MB` disk, `~510 MB` RAM) for offline Alarms, Calendar events, Contacts/Dialer, App launching, and Room To-Dos.
   - **Malayalam Audio Listener:** `sherpa-onnx` Android AAR running CPU-only `IndicConformer Malayalam` (`model.onnx` + `tokens.txt`) fed by native Android `MediaExtractor` + `MediaCodec` 16 kHz mono PCM decoding (capped to first 15s, silence-trimmed, **no FFmpeg**).
   - **Situation Classifier & Light Chat:** `Gemma 3 1B IT (dynamic_int4 QAT)` (`~529 MB` disk, `~1.15 GB` peak RAM). `Gemma 4 E2B` is hidden/disabled by default and only offered as an optional toggle on 8 GB+ phones.

3. **"AI Hears & Places Dialogue" SAF Vault Pipeline**
   - Select one persistent folder via SAF (`Documents/MalayalamAssistant/`) — automatically creates `.nomedia` and stores `dialogues.json` + `embeddings_cache.json` so clips survive APK reinstalls.
   - Sequential `WorkManager` pipeline (`Listening... → Understanding Situation... → Indexing...`) presents **Review & Confirm Cards** (with 1-tap **Confirm** or **Auto-Accept All**) and a **Test Simulator** showing Top 3 match scores.

4. **Floating Gemini Overlay & Background Notification Reactions**
   - Full `VoiceInteractionService` + `VoiceInteractionSession` Default Digital Assistant overlay (Power-button hold / corner swipe) with `ml-IN` / `en-IN` live mic, **Read Screen** (`onHandleAssist`), and instant audio **Stop** bar.
   - Background `NotificationListenerService` + `BatteryLowReceiver` with OTP skipping, 15s cooldown, DND/Silent checks, and **Headphones / Bluetooth Only** mode.

## Building `app-debug.apk`

- **GitHub Actions:** Push to GitHub or trigger `.github/workflows/build-apk.yml` (`Actions → Build Debug APK`, running Temurin JDK 21 + Gradle 9.8.2 + AGP 9.4.1) to compile and download the `app-debug` artifact.
- **Local CLI (JDK 21 + Android SDK 35):**
  ```bash
  ./gradlew assembleDebug --stacktrace
  ```
