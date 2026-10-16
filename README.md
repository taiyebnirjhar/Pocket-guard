# Pocket Guard

Android anti-pickpocket alarm. Arm it before you get into a crowd. If the phone leaves your
pocket or bag without your unlock, it beeps (in your earbuds if connected), then sounds a
full-volume siren the thief can't silence, and the front camera photographs whoever is holding it.

English and Bangla. Android 8.0+ (minSdk 26). No SMS, location or accessibility permissions, so
it can be installed outside the Play Store without the strictest Play Protect checks.

## Screens

A dark theme with a purple glow, set in Manrope. A floating tab bar switches between four tabs:

- **Home**: a power button inside a ring of moving dots. The ring's colour and speed follow the
  guard (grey off, purple protected, amber warning, red siren). Also shows the status, time
  armed, unlock delay, last alarm and how many setup steps are left.
- **Triggers**: every alarm and auto-arm switch, the earbud picker and intruder photos.
- **History**: past alarms with their intruder photos.
- **Settings**: unlock delay, test mode, the setup checklist, a live sensor check, language and
  the how-to-test list.

CI takes a screenshot of every screen on the emulator and attaches them to each release.

## How it works

1. **Armed**: waiting for the phone to go into a pocket (proximity sensor covered for 1.5 s).
2. **Protected**: a short buzz confirms it, once the screen is off or the phone locked.
3. **Taken out while locked**: proximity uncovered for 0.8 s, plus evidence that it really left
   the pocket:
   - **Light**, when fresh readings arrive from both inside and outside the pocket, and outside
     is brighter.
   - **Motion** otherwise: a jolt over 2.5 m/s² plus the sensor staying uncovered for 1.5 s.
     Many phones stop reporting light while the screen is off, so this is the usual path.

   Late readings still count for 2.5 s. A motion-only warning cancels itself if the sensor is
   covered again within 1 s (pocket fabric). A light-confirmed one doesn't, since that's a
   thief's hand.
4. **Warning**: beeps and vibration. Unlock within the grace period (default 5 s, 0-15 s) and
   nothing else happens. With 0 there's no warning: the removal is confirmed faster (0.25 s
   uncovered, 0.5 s on motion alone) and the siren starts at once. A motion-only siren still
   stops by itself if the sensor is covered again within 1 s.
5. **Siren**: alarm stream at full volume (through silent mode, and through "Total silence" Do Not
   Disturb when allowed), volume buttons locked, torch strobe, "THIS PHONE IS STOLEN" over the lock
   screen, intruder photos.
6. **Unlock** with PIN, pattern, password, fingerprint or face: everything stops and the guard
   re-arms by itself.

Unlocking only counts as the owner if the phone was credential-locked when the alarm began. If
Smart Lock or a screen-lock delay kept it unlocked, the alarm screen says to tap DISARM in the
app instead. The app refuses to arm without a PIN, pattern or password.

## Setup requirement: the phone must lock immediately

A phone pocketed with the screen just turned off, but not yet locked, would let a thief pass as
the owner. So the phone's own settings must lock it at once:

- **Lock after screen timeout**: Immediately
- **Power button instantly locks**: on
- **Password needed to power off**: on, if the phone has it (Xiaomi, Realme, Oppo, newer Samsung).
  This replaces a power-off alarm.

Android hides those settings from apps, so Pocket Guard tests them. Every time the screen turns
off while armed, it checks 1.5 s later that the phone is locked. If not, it shows a warning
notification and marks the checklist row red. The power button locks at once even with a lock
delay, so a later pass doesn't clear the failure: tapping **Fix** (or the notification) starts the
test over. To test, let the screen turn off by itself once.

## Triggers

| Trigger | What happens | Default | Needs |
|---|---|---|---|
| Taken out of pocket or bag while locked | Warning, then siren | Always on | Proximity sensor |
| Airplane mode turned on while locked | Siren at once | On | |
| Charger unplugged while locked (wired, still unplugged after 2 s) | Warning, then siren | On | |
| Earbuds or watch disconnect while out of pocket and locked | Warning, then siren (in pocket: buzz only) | Off | Nearby devices permission |
| Phone restarted or app killed during an alarm | Alarm resumes, before the first unlock too | Always on | |

**Auto-arm** (both off by default) when chosen earbuds or a watch connect, or when Google
activity detection sees you board a bus, train or car. Auto-armed sessions turn off after 4 hours.
Car stereos and speakers never count as earbuds, and you can pick the exact devices that count.

Calls are ignored while the audio is at the earpiece, because the ear covers and uncovers the
proximity sensor.

## Evidence

- **Intruder photos**: 2 silent front-camera photos per siren, then the torch strobe starts.
- **Theft history**: trigger, outcome and photos. Warning-only entries are kept 7 days, alarms 90.

Both stay on the phone, for when it's recovered. An internet alert to another device is the
planned replacement for the SMS alerts of earlier builds.

## Test mode

Test mode plays a quiet siren. It is off by default and switches itself off after an hour, so a
forgotten test mode can't weaken a real alarm. The in-app "How to test" list walks through every
trigger.

## Install

Every push to `main` runs the CI pipeline below. When it passes, the signed APK is published
under [Releases](../../releases) as `pocket-guard-build-N`, with screenshots of every screen.
Open the release on the phone, download the APK and allow installs from your browser. Official
builds share one signing key, so a new build installs over the old one and keeps your settings.

After installing, work through the in-app setup checklist: notifications, battery optimization,
lock-screen alarms, a screen lock that locks immediately, Do Not Disturb access and autostart
(Xiaomi, Realme, Oppo, Vivo).

## Architecture

| File | Role |
|---|---|
| `GuardEngine.kt` | Every decision as a pure state machine (no Android types). Unit tested. |
| `GuardService.kt` | Foreground service: feeds sensors and system broadcasts to the engine, carries out its side effects, runs the lock check. Direct-boot aware. |
| `AlarmPlayer.kt` | Generated beeps and siren, volume lock, Do Not Disturb override, torch, vibration. |
| `IncidentRecorder.kt`, `IntruderCamera.kt`, `History.kt` | Evidence: CameraX photos, JSON history. |
| `BluetoothReceiver.kt`, `TransitRecognition.kt` | Earbud and transit auto-arm; boot and update re-arm. |
| `AlarmActivity.kt` | Alarm screen shown over the lock screen. |
| `HomeScreen.kt`, `TriggersScreen.kt`, `HistoryScreen.kt`, `SettingsScreen.kt`, `OnboardingScreen.kt` | Compose UI: four tabs and the guide. |
| `Components.kt`, `Theme.kt` | Shared UI pieces (cards, rows, floating tab bar, particle ring) and the dark purple theme with Manrope. |
| `Prefs.kt` | Settings, in device-protected storage so they can be read before the first unlock. |

## CI pipeline

`.github/workflows/ci.yml`, on every push and pull request:

1. **Unit tests**: `GuardEngine` tests covering the ladder, every trigger, walking, dark pockets,
   a light sensor that freezes with the screen off, late readings, flicker recovery, calls,
   no-delay mode and restarts.
2. **Android lint** with `abortOnError`. The full report is printed on failure.
3. **Emulator end-to-end test** (`e2e/run.sh`, API 34). It sets a PIN, writes the app's settings
   with `run-as`, and fakes the cameras and the proximity, light and motion sensors. It then
   checks the app log, stored history, photo files, notifications and screen state for:
   - pickpocket: siren, intruder photos, history entry
   - pull-out with a frozen light sensor
   - the owner's own pull-out and unlock within grace
   - sensor flicker and walking ignored
   - airplane mode and charger triggers
   - the lock check: a lock delay caught on screen timeout, kept after a power-button lock,
     cleared by Fix and passing once the lock is immediate
   - the alarm resuming after a reboot, before the first unlock
   - every screen opened and screenshotted, and monkey crash fuzzing

   Screenshots, the app log and keyguard state are uploaded for each failure.
4. **Release**: only for `main`, only after all of the above pass, and only when the build was
   signed with the official key.

## Known limits

- No app can block power-off or a forced restart (power + volume). After a forced restart the
  guard re-arms and resumes the alarm; use the phone's own "password to power off" where it exists,
  and a 0 s grace period to give a thief no time.
- The photos and history stay on the stolen phone; nobody is notified remotely yet.
- Some phones only report proximity while the screen is on. Use the in-app **Sensor check** with
  the screen off in a pocket to find out.
- iPhone isn't possible: iOS gives apps no background sensor access.

## Build it yourself

You need JDK 17 and the Android SDK (Android Studio works).

```sh
git clone https://github.com/taiyebnirjhar/pocket-guard.git
cd pocket-guard
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Without the official key the build is signed with your local debug key, so it can't install over an
official release (uninstall that first).

**Signing official builds.** CI signs with a key stored in two repository secrets:
`SIGNING_KEYSTORE_BASE64` (the keystore, base64) and `SIGNING_PASSWORD`. A fork can make its own
with `keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias pocketguard -keyalg RSA
-keysize 4096 -validity 10000` and `base64 -w0 release.jks`.

## Contributing

Issues and pull requests are welcome. Pull requests run the same unit tests, lint and emulator
test as `main`. When you change detection behaviour, add a case to `GuardEngineTest` and, if an
emulator can fake it, to `e2e/run.sh`. Strings live in `res/values/strings.xml` and
`res/values-bn/strings.xml`; please add both (or ask for help with the Bangla).

## License

[MIT](LICENSE) © 2026 Taiyeb Nirjhor. The Manrope font is under the
[SIL Open Font License](licenses/Manrope-OFL.txt).
