# JARVIS — Personal AI Assistant for Android

A real, native Android app (Kotlin + Jetpack Compose) — not a WebView wrapper.
Dark, glassmorphic UI with a glowing AI orb, voice in/out, a safe whitelist-only
command engine for phone actions, local memory, and local chat history.

## What's implemented right now (Phase 1 + 2 from the spec)

- **UI**: Home (orb + mic + quick commands), Chat, Memory, History, Settings — all in Compose.
- **Voice**: real `SpeechRecognizer` input and `TextToSpeech` output. Mic is only ever
  active in the foreground, while you're looking at a visible "Listening..." indicator —
  no background/silent recording.
- **AI brain**: a swappable `AiService` interface. The shipped implementation talks to
  any OpenAI-compatible `/v1/chat/completions` endpoint (OpenAI directly, or your own
  proxy) using a key you type into Settings once — it's stored with
  `EncryptedSharedPreferences` (Android Keystore-backed AES), never in the APK.
- **Command engine**: the AI can *propose* an action as a small JSON block; `CommandEngine`
  parses it against a closed whitelist of types and `AndroidActionExecutor` performs it
  using only public Android Intents (open app, open Wi-Fi/Bluetooth settings, camera,
  browser, maps, dialer, contacts, calendar, clock, set alarm, set timer, create reminder,
  share text, adjust volume). Nothing outside that list can ever run — arbitrary
  AI-generated code is never executed. Alarms, reminders, dialing, and sharing ask for
  confirmation first.
- **Memory**: local Room-backed memory list. The AI adds to it only when you explicitly
  ask it to remember something; you can view/delete/clear it from the Memory screen.
- **History**: local Room-backed conversations with search, delete, and clear-all.
- **Offline mode**: if there's no connectivity, JARVIS says so and still handles local
  actions (open apps/settings, alarms, timers) without trying to hit the AI API.
- **Web search hook**: current-info questions ("today", "latest", "weather", "aaj", ...)
  get routed through a Brave Search API call (your own key, entered in Settings) before
  being handed to the model as context, instead of letting the model guess.
- **Wake word ("Hey Jarvis")**: an opt-in Settings toggle that runs a continuous
  listen-for-"Jarvis" loop inside the background service, so you can talk without tapping
  the mic bubble first. Always shown via the persistent notification while active (Android
  requires this for any background mic use — there's no silent version of this).
- **Home / launcher mode**: `MainActivity` also declares the `HOME` category, so JARVIS can
  be picked as the device's default Home app from Android's launcher chooser
  (Settings → Apps → Default apps → Home app).
- **Live wallpaper**: an animated JARVIS orb (`LiveWallpaperService`) that runs behind the
  home screen icons and reflects the same idle/listening/thinking/speaking state as the
  background service, via a shared `JarvisGlobalState` — the wallpaper itself never touches
  the microphone. Set from Settings → "Set as live wallpaper".

## Not yet built (Phase 3/4 from the spec — architecture is ready for these)

- Reading notifications (only with explicit permission) — not wired in yet.

## Tech stack

Kotlin, Jetpack Compose, Material 3, Navigation-Compose, Room, Retrofit + OkHttp,
Coroutines/Flow, `androidx.security.crypto` (Keystore-backed encrypted prefs),
Android `SpeechRecognizer` / `TextToSpeech`. Manual dependency injection (`AppContainer`)
instead of Hilt/Dagger, specifically to keep the Gradle/CI build simple and fast without
annotation-processor setup headaches.

## Required Android permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Voice input via `SpeechRecognizer` |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Talking to the AI API and search API; detecting offline mode |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Setting alarms via the system Clock app |
| `POST_NOTIFICATIONS` | Android 13+ requires this even for system-triggered notifications like alarms |

No contacts/SMS/notification-listener/location permissions are requested — the app doesn't
need them for anything currently implemented.

## Required API keys (yours — never hard-coded)

Entered once on your phone in **Settings**, stored encrypted on-device:

1. **AI provider API key** — e.g. an OpenAI API key (`sk-...`). Required for JARVIS to think.
2. **Search API key** (optional) — a [Brave Search API](https://brave.com/search/api/) key,
   only needed if you want current-info questions ("today's weather", "latest news") to be
   backed by real search results instead of being declined.

Nothing is stored in source control, in `local.properties`, or baked into the build — these
are runtime, on-device, per-user settings.

---

## Folder structure

```
JarvisAssistant/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── .gitignore
├── .github/workflows/build.yml
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── values/strings.xml
        │   ├── xml/backup_rules.xml, data_extraction_rules.xml
        │   └── mipmap-*/ic_launcher.png, ic_launcher_round.png
        └── kotlin/com/jarvis/assistant/
            ├── JarvisApplication.kt
            ├── MainActivity.kt
            ├── di/AppContainer.kt
            ├── navigation/NavGraph.kt
            ├── ui/
            │   ├── AssistantViewModel.kt          (shared voice/AI/command orchestration)
            │   ├── theme/ (Color, Theme, Type)
            │   ├── components/AiOrb.kt
            │   └── screens/{home,chat,memory,history,settings}/
            ├── ai/ (AiService, OpenAiService, OpenAiApi, AiModels, PromptBuilder)
            ├── voice/ (SpeechToTextManager, TextToSpeechManager, VoiceState)
            ├── command/ (CommandModels, CommandEngine, AndroidActionExecutor)
            ├── search/WebSearchService.kt
            ├── data/
            │   ├── local/db/ (JarvisDatabase, entity/, dao/)
            │   ├── local/prefs/SecurePrefs.kt
            │   └── repository/ (MemoryRepository, ConversationRepository)
            └── util/ (NetworkMonitor, PermissionUtils)
```

---

## Building the APK — no Android Studio required

Everything below happens in a browser.

### 1. Create the GitHub repository

1. Go to [github.com/new](https://github.com/new).
2. Name it `jarvis-assistant` (or anything you like), keep it **Private** if you want,
   and click **Create repository**. Don't add a README/gitignore here — you already have them.

### 2. Upload the project files

Easiest way with no local git setup:

1. On your new repo's page, click **"uploading an existing file"** (or **Add file → Upload files**).
2. Drag in the whole `JarvisAssistant` folder contents — GitHub's web uploader preserves
   folder structure when you drag a folder in on desktop Chrome/Edge/Firefox.
3. **Important**: the `.github/workflows/build.yml` file needs to land at that exact path
   (`.github/workflows/build.yml`) for GitHub Actions to pick it up. If the web uploader
   flattens hidden folders, create it manually instead: in the repo, click
   **Add file → Create new file**, type `.github/workflows/build.yml` as the filename
   (GitHub auto-creates the folders), and paste in the workflow content.
4. Commit directly to `main`.

### 3. Run the build

1. Go to the **Actions** tab of your repo.
2. You should see the **"Build JARVIS APK"** workflow already queued (it triggers on push
   to `main`). If not, click it in the left sidebar, then **Run workflow → Run workflow**
   (this works because the workflow also has `workflow_dispatch` enabled).
3. Wait for the green checkmark (a first build typically takes 3–6 minutes).

### 4. Download the APK

1. Click into the finished workflow run.
2. Scroll to **Artifacts** at the bottom.
3. Download **`jarvis-debug-apk`** — it's a zip containing `app-debug.apk`.

### 5. Install it on your phone

1. Transfer `app-debug.apk` to your phone (e.g. via Google Drive, email to yourself, or a
   USB cable).
2. Tap the file. Android will ask to allow installing from this source the first time —
   allow it for that one file/app (Settings will guide you through this automatically).
3. Install, open, and grant the microphone permission when prompted.
4. Go to **Settings** inside the app and paste in your AI API key (and optionally a Search
   API key) before trying to talk to it — without a key, JARVIS will tell you it isn't
   configured yet rather than failing silently.

### If the build fails

Open the failed step in the Actions log — the two most likely causes on a first run are:
- A typo introduced while uploading files through the web UI (compare a file's content
  against what's shown in this README/chat).
- A transient Android SDK/component download timeout — just click **Re-run all jobs**.

---

## Debug order (matches the phased plan)

1. **Phase 1** (done): confirm the app opens, Chat screen sends/receives from your AI key,
   voice in/out works.
2. **Phase 2** (done): try "open YouTube", "set an alarm for 8pm", "open wifi settings" and
   confirm the confirmation dialogs and Intents fire correctly.
3. **Phase 3** (done): "remember that I like short answers", check the Memory screen; check
   History search/delete.
4. **Phase 4** (partial): wake-word toggle exists in Settings with an explanation; the
   foreground-service wiring to make it actually always-listening is the next build step,
   and it's additive — it won't require touching the AI/command/UI code above.

## Advanced Mobile Agent upgrade

The project now contains an in-place agent layer under `com.jarvis.assistant.agent`:

- `PhoneContextEngine` — bounded phone/device state snapshot.
- `AppRegistry` — launchable-app discovery and name resolution.
- `PermissionManager` — READY / REQUIRED / OPTIONAL capability reporting.
- `ConfirmationManager` — centralized risk/confirmation policy.
- `AgentPlanner` — validates bounded `AGENT_PLAN` JSON into the existing command whitelist.
- `TaskEngine` — sequential execute → re-scan/verify → bounded retry → completion/cancellation loop.

The existing Accessibility service was extended with richer screen metadata (role, selected/enabled
state and bounds), foreground-package discovery, automation status and failure tracking. The voice
recognizer now tears down stale sessions before starting another one to reduce recognizer-busy races.

No arbitrary AI-generated Kotlin/Java/Android code is executed. Screen capture remains subject to
Android's MediaProjection user-consent flow; the agent does not bypass protected content or Android
security restrictions.
