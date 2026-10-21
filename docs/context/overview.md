# Overview

## What it is

Pocket Guard is an Android anti-theft and personal-safety app. Package `com.taiyeb.pocketguard`.
Android 8.0+ (minSdk 26), target SDK 36. Kotlin and Jetpack Compose. MIT licence.

It does two things:

1. **Theft alarm.** Armed and locked, the phone guards itself in a pocket, bag or on a table. If
   it is taken out or moved without the owner's unlock, it warns, then sounds a full-volume siren
   with a torch strobe and "THIS PHONE IS STOLEN" over the lock screen, and takes intruder photos.
2. **SOS.** Tap volume up 4 times quickly (screen on). It sounds a help whistle and a spoken
   "Help!", records video with sound, and alerts up to 5 emergency contacts by SMS and email with
   a live location link.

## Who it is for

- Public safety, primarily women: a student walking home, a bus commuter, someone harassed in a
  crowd. The idea came from a crowded train (pickpockets); SOS came from the owner's wife.
- Bangladesh first. Supabase runs in Singapore for that reason. The week in the schedule starts
  on Saturday.
- UI in English and Bangla. SOS SMS is always in English letters (English or Banglish) to keep
  SMS cheap.

## The two builds

Same code, two Gradle flavours:

| | `full` (GitHub / website APK) | `play` (Google Play AAB) |
|---|---|---|
| SMS | Sends by itself (`SEND_SMS`), retries up to 5 times | Fills in the SMS app; user taps Send |
| Battery | Asks to ignore battery optimisation | Opens the battery settings list |
| Schedule timing | Exact alarms (`SCHEDULE_EXACT_ALARM`) | May start a few minutes late |
| Flag | | `BuildConfig.PLAY_STORE` |
| Released as | `PocketGuard-vX.Y.Z.apk` (signed `fullRelease`) | `PocketGuard-vX.Y.Z-play.aab` |

The extra permissions live only in `app/src/full/AndroidManifest.xml`. The public download is
the full APK, served from https://pocket-guard.pages.dev. The Play build is not published yet.

## Main features (v2.4.0)

- Arm from the power button on Home, the notification, the Quick Settings tile or the schedule.
- Pocket guard: protected 0.5 s after it is covered and locked; any uncover of 0.8 s warns.
  Not fooled by a finger over the sensor in the hand, sliding in, or sitting down (v2.4.0).
- Table guard: slide, tilt, lift, pick-up, new angle or fall warns. Knocks and notification
  vibration don't.
- Tamper triggers: airplane mode, charger unplugged, earbuds/watch disconnect, restart during an
  alarm.
- Grace period 0 to 15 s (default 5). First 1.5 s of a warning is silent so the owner's
  fingerprint unlock makes no sound.
- **Theft alarm** and **SOS** are separate switches. Theft off = "SOS ready" mode.
- SOS: 4 volume-up taps, notification button, Quick Settings tile, Home card, or the
  "🆘 I need help" button on the theft alarm screen.
- Emergency contacts: up to 5, approved message wording, 5 s countdown with Cancel, follow-ups
  on movement, "false alarm" message, Share on WhatsApp.
- Live location page and alert emails, anonymous account, SOS video upload to R2, 90-day deletion.
  "Delete my online data" in Settings.
- Arming schedule: up to 5 time windows, like Focus mode.
- Evidence and History: intruder photos (never uploaded), SOS videos; open, save to gallery, share.
- First-run "Get it ready" setup, guided Accessibility setup, permission disclosures.
- Test mode (quiet siren, [TEST] SMS, off after 1 h), detection log, sensor check, lock check.

## Repo layout

| Path | What it is |
|---|---|
| `VERSION` | The version. A new value merged to `main` makes a release. |
| `app/src/main/java/com/taiyeb/pocketguard/` | All app code (below). |
| `app/src/full/` | Full-flavour manifest: SMS, battery-optimisation, exact-alarm permissions. |
| `app/src/test/` | JVM unit tests: `GuardEngineTest`, `MotionProcessorTest`, `MotionRulesTest`, `SosTapsTest`, `ArmScheduleTest`, `AlertPlanTest`. |
| `app/src/main/res/values*/strings.xml` | English and Bangla strings. |
| `e2e/` | Emulator end-to-end test (`run.sh`), local backend (`backend.sh`), Brevo stand-in (`mock_brevo.py`), `wording_hash.py`. |
| `supabase/` | Backend: `migrations/`, `functions/` (`notify`, `media`, `delete-account`, `_shared/`), `tests/`. |
| `site/` | Website on Cloudflare Pages: `index.html` landing page, `privacy.html`, `live/` live map, `img/`, `video/`. |
| `.github/workflows/` | `ci.yml`, `backend.yml`, `pages.yml`, `daily.yml`, `check-backend.yml`. |
| `docs/` | This knowledge base. |

### Key source files

| File | Role |
|---|---|
| `GuardEngine.kt` | Every guard decision as a pure-Kotlin state machine. Unit tested. |
| `GuardState.kt` | Phases (OFF, WAITING_FOR_POCKET, IN_POCKET, ON_TABLE, WARNING, SIREN) and shared state. |
| `MotionProcessor.kt` | Accelerometer to gravity direction, stillness, jolts, falls, walking. |
| `TableMonitor.kt` | Table placement and table rules (slide, tilt, lift, held, pose, fall, buzz filter). |
| `Detection.kt` | Sensor helpers (e.g. proximity near/far). |
| `GuardService.kt` | Foreground service: feeds sensors and broadcasts to the engine, runs side effects and the lock check. |
| `AlarmPlayer.kt`, `Tones.kt` | Beeps, siren, SOS whistle, volume lock, DND, torch, vibration. |
| `AlarmActivity.kt` | Alarm screen over the lock screen. |
| `IncidentRecorder.kt`, `IntruderCamera.kt`, `SosVideo.kt` | Intruder photos and SOS video (CameraX). |
| `History.kt`, `MediaActions.kt`, `HistoryScreen.kt` | History storage and open/save/share. |
| `SosTaps.kt`, `SosAccessibilityService.kt`, `SosKeysSetup.kt` | The 4-tap gesture and its Accessibility setup. |
| `SosTileService.kt`, `GuardTileService.kt` | Quick Settings tiles. |
| `SosAlerts.kt`, `AlertPlan.kt` | SOS alerts: countdown, SMS, follow-ups, false alarm. |
| `EmergencyContacts.kt`, `EmergencyContactsCard.kt` | Contacts, approved wording, approval fingerprint. |
| `Backend.kt`, `LiveShare.kt` | Anonymous sign-in, SOS record, live location, video upload, alert emails. |
| `ArmSchedule.kt`, `ArmScheduler.kt`, `ScheduleUi.kt` | Arming schedule. |
| `BluetoothReceiver.kt`, `TransitRecognition.kt` | Earbud and transit arming reminders; boot re-arm. |
| `Notifications.kt`, `Setup.kt`, `Disclosure.kt`, `DetectionLog.kt` | Notifications, setup checks, permission disclosures, on-phone log. |
| `Prefs.kt` | Settings in device-protected storage (readable before first unlock). |
| `Texts.kt`, `Language.kt` | Status texts and in-app language switch. |
| `MainActivity.kt`, `HomeScreen.kt`, `TriggersScreen.kt`, `SettingsScreen.kt`, `OnboardingScreen.kt`, `SetupScreen.kt`, `Components.kt`, `Theme.kt` | Compose UI: four tabs, guide, setup, theme. |
