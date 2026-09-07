# JARVIS Ultra pass — bug fix + branding/terminology/permission-center

This session had two parts: fixing a real bug from the previous (AI Brain) pass, and
implementing the tractable parts of a much larger 34-section "full visual/branding
transformation" prompt. That prompt is, honestly, a multi-week UI/animation/branding project on
its own — this pass does not claim to complete all 34 sections. Here's exactly what happened.

## Critical fix: a compile-breaking name collision

`agent/CapabilityRegistry.kt` (added in the AI Brain pass) declared `enum class CapabilityState`
in package `com.jarvis.assistant.agent`. `agent/PermissionManager.kt` — pre-existing code from
before that pass, which I had read but never actually re-checked for name collisions — already
declared its own, differently-shaped `enum class CapabilityState` in the same package. Two
top-level declarations with the same name in the same package is a hard Kotlin compile error
("Conflicting declarations"), so **the project would not have built** in its previous state.

Fix: renamed my enum to `ToolAvailability` (`CapabilityRegistry.kt`, `DiagnosticEngine.kt`).
`PermissionManager`'s original `CapabilityState`/`CapabilityStatus` are untouched. I then swept
the entire project for any other duplicate top-level `class`/`enum class`/`data class`/`object`
declarations — there were none.

This is exactly the kind of issue I flagged as a real risk earlier (I can't compile here to
catch it myself) — worth knowing it actually happened once, not just as a hypothetical caveat.

## What's implemented from the visual/branding spec

| Spec section | What was done |
|---|---|
| #12 Permission Intelligence | Built a real "Permission Center" screen (`ui/screens/permissions/`) on top of the **pre-existing but previously unused** `PermissionManager` — it had zero consumers before this. Shows READY/REQUIRED/OPTIONAL/DENIED per permission with an explanation, and each row routes to the correct Android settings screen (or requests the runtime mic permission directly) — JARVIS never grants anything itself. Reachable from System Control (Settings). |
| #3 Brand identity / terminology | Bottom nav relabeled: Home→**Center**, Chat→**Command**, History→**Log**, Settings→**Control** (kept short for the 5-tab bar on a phone screen — full words like "COMMAND CENTER" wouldn't fit). Screen titles updated: History screen → "Activity Log", Settings screen → "System Control". |
| #23/#24 Branding / boot experience | Rewrote `BootScreen.kt`: an animated rotating/pulsing core (same HUD visual language as the Command Center, self-contained so boot has no dependency on the rest of the UI), a real CORE/MEMORY/VOICE/AUTOMATION/HUD checklist, ending on "WELCOME TO JARVIS / COMMAND CENTER ONLINE". Already wired into `MainActivity` before this pass — just upgraded the visual. |
| #23 App icon | Added a real adaptive icon: `drawable/ic_jarvis_core_{foreground,background,monochrome}.xml` (vector — a ring, a reactor-style scan arc, and a glowing core dot, in the app's actual cyan) plus `mipmap-anydpi-v26/ic_launcher.xml` / `ic_launcher_round.xml`. Since minSdk is 26, every device this app can run on will use the new adaptive icon; the old placeholder PNGs are unused fallback for (impossible, given minSdk) pre-26 devices. |
| #25 Notifications | The one place posting a notification (`OverlayService`'s foreground-service notification) was using the full-color launcher PNG as its status-bar icon, which renders wrong (Android notification icons must be a flat white/alpha silhouette). Added `drawable/ic_notification.xml` (a simple ring+dot silhouette) and switched the reference. |

## What was NOT done (explicitly out of scope for this pass)

- **Sections 2, 7, 8, 9, 10, 11, 13, 14, 18, 19, 26-29** describe things that were **already
  built** in earlier passes (device context engine, memory layers, error recovery, voice system,
  AI provider abstraction, offline fallback, security rules, clean-architecture separation) —
  nothing new needed there; re-verify against `BRAIN_UPGRADE_NOTES.md` from the prior pass rather
  than assuming this pass duplicated that work.
- **Section 6 (live wallpaper upgrade)** — `LiveWallpaperService.kt` was already a mature,
  telemetry-reactive, frame-rate-adaptive Canvas renderer with a sweep-gradient scan effect
  before this pass. Per the spec's own rule ("if something is already working, do not rewrite it
  unnecessarily"), I left it alone rather than redoing it for cosmetic reasons.
- **Section 15 (Activity Log content/format)** — the screen was renamed, but its content/format
  was not redesigned to the exact timestamped step-by-step format shown in the spec. The
  underlying data (conversation history) supports this; the presentation wasn't rebuilt this
  pass.
- **Section 16 (explicit FOCUS/SILENT modes)** — the existing `JarvisHudState` enum covers
  IDLE/LISTENING/THINKING/EXECUTING/etc.; FOCUS and SILENT as distinct user-facing modes were
  not added.
- **Section 20/21 (radial/HUD-style navigation replacing bottom nav, full responsive
  audit)** — the app still uses a bottom nav bar (now with renamed short labels), not a radial or
  side-panel HUD navigation system. Replacing the navigation shell is a much larger, riskier
  change than relabeling it, and wasn't attempted here. No landscape/multi-density testing was
  done (no emulator available).
- **Section 31/32 (build + 20-point validation)** — as with every pass in this conversation, I
  have no Android SDK here, so none of this was actually run. The collision above is proof that
  "looks right on review" and "actually compiles" are not the same thing — treat your own
  `./gradlew assembleDebug` as the real, first test.

## Update — second pass in this same "Ultra" thread

More of the deliberately-skipped items above are now real:

| Spec section | What was added |
|---|---|
| #15 Activity Log (real format) | `HistoryScreen` now has two tabs: the original conversation browser (untouched) and a new **System Log** tab (`ActivityLogViewModel` + `SystemActivityFeed`) showing real, timestamped `CommandHistoryEntity` rows in the spec's own format — TIMESTAMP / COMMAND RECEIVED / action+result / VERIFICATION PASSED-or-ACTION FAILED / COMMAND COMPLETE-or-FAILED. Not fake data — same rows the Dashboard and diagnostics already log. |
| #16 JARVIS Modes (SILENT, FOCUS) | Two new persisted, real toggles in Settings. **Silent mode**: `AssistantViewModel.speak()` returns immediately without touching TTS — JARVIS still replies in text and still executes everything. **Focus mode**: gates `JarvisNotifier` (see next row). |
| #25 Notifications | Previously scaffolded as *text in the spec only* — nothing in the app posted a JARVIS-branded notification. Added `agent/JarvisNotifier.kt`: a real "Task completed" notification fired only for multi-step plans (never per single command, so it can't spam), and a real, debounced low-battery notification (fires once per drop below 15%, not once per telemetry tick) wired into the Home screen's existing telemetry stream. Both respect Focus Mode and the real `POST_NOTIFICATIONS` permission state. |

Still not done, and still the same honest reasons as above: radial/HUD navigation replacing
the bottom bar, and any actual on-device verification.

## Update — third pass: remaining App Intelligence / Accessibility / Device gaps

| Spec section | What was added |
|---|---|
| #10 App Intelligence | New `LIST_INSTALLED_APPS` command ("which apps are installed?", "find the browser") reusing `AppRegistry`'s existing launcher query — same source of truth `OPEN_APP` already resolves names against. New `CLOSE_APP` command — Android gives a regular app no public API to force-close another app (a real platform restriction, not a gap), so this returns to the home screen instead and says so honestly in the result text rather than claiming the app was terminated. Both are fast local-only matches (work offline) as well as AI-issued commands. |
| #11 Accessibility Automation | Added a real `swipe` automation step (`AutomationStep.Swipe` + `SwipeDirection`), dispatched as an actual touch gesture via `dispatchGesture`/`GestureDescription` — for carousels and other UI that doesn't expose a scrollable accessibility node and so never responded to the existing scroll-forward/backward action. Wired through JSON parsing, the executor, and the AI's tool list. |
| #9 Device Understanding | Added CPU core count (`Runtime.getRuntime().availableProcessors()`) to `DeviceTelemetry`, shown on the Command Center. No live CPU-load percentage — modern Android has no public API for that without root, so this stays an honest static fact instead of a fake-looking gauge with nothing real behind it. |

Everything above is wired end-to-end (JSON parsing → executor → real Android API), not UI-only.
Cross-checked with a full grep sweep — every new symbol has exactly one definition and is
referenced consistently everywhere it's used.

## Recommended next step

Same as before, and more true now than ever with more surface area added: run
`./gradlew assembleDebug` yourself before asking for more feature work. Review here (brace
balance, duplicate-declaration sweep, cross-file reference checks) already caught one real
compile-breaking bug this thread — it's a good net, not a perfect one. A real build is the only
way to be certain.

## Update — third pass: radial HUD menu + a caught-and-avoided risk

Added the one remaining tractable item from the "not done" list: a **radial quick-access menu**
(`RadialQuickMenu` in `HomeScreen.kt`) — long-press the Command Center core to reveal five HUD-style
circular shortcuts (Diagnostic, Dashboard, Permissions, Memory, System Control) arranged around
it. This is additive, not a replacement — the bottom nav bar is untouched and still fully
functional. Wired through `NavGraph.kt` via new optional callback parameters on `HomeScreen`.

**A risk I caught and deliberately avoided while building it**: my first draft used
`androidx.compose.animation`'s `AnimatedVisibility`/`fadeIn`/`scaleIn` for the menu's
open/close transition. That's a separate Gradle artifact (`androidx.compose.animation:animation`)
that is not explicitly declared in this project's `build.gradle.kts` — only
`androidx.compose.animation:animation-core` is proven available (it's what the existing
`JarvisCore` rotation animation already uses). Since I can't compile here to confirm whether
Material3 pulls in the full `animation` artifact transitively as `api` or only `implementation`
(which would make it invisible to this module), I rewrote the menu to use only APIs already
proven to work elsewhere in this exact file — no new dependency risk, at the cost of a slightly
less cinematic open/close (it appears/disappears immediately rather than fading and scaling in).

Also re-verified the manifest while looking for other likely bug classes from the new
Notification/permission work: `android:foregroundServiceType="microphone"` is already correctly
declared on `OverlayService` (required on Android 14+ or the service would crash at runtime) —
that was already correct before this thread touched anything, nothing to fix there.

Genuinely still not done: replacing the bottom nav bar itself with a full radial/HUD navigation
shell (the addition above supplements it, doesn't replace it), and any actual on-device or
Gradle verification.

