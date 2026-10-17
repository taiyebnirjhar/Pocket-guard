# Pocket Guard

https://github.com/user-attachments/assets/5b038718-6057-4d9d-8a62-5170a73fcfbd

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
   - **Jolt**: a push or pull over 2.5 m/s² (measured as |a| away from 1 g, so turning the phone
     can't fake it; a sideways pull, which barely changes |a|, counts when the phone ends up
     facing the way it was) plus the sensor staying uncovered for 1.5 s. While walking, every
     step is a jolt, so then only a jolt after the steps stop counts, and it takes 3 s uncovered
     after they stop.
   - **Turned to look**: no jolt (a careful, gentle pull), but the phone turns 45° or more away
     from its pocket posture and the screen wakes as it turns.
   - **Into another pocket**: out for at least a second, turned 60° or more, then covered again
     at a new angle (30° or more from where it was). Sitting down or bending turns the phone one
     way in one go and doesn't count; while walking, or after it rested somewhere, this also
     needs the screen or light.

   Without any of these it doesn't give up: it keeps watching how the phone turns, where it goes
   and whether the screen wakes, until it goes back into the pocket or you unlock. A jolt-only
   warning cancels itself if the sensor is covered again within 1 s (pocket fabric), though if
   the phone was turned far meanwhile it is judged as going into another pocket; the others
   don't cancel, since they mean a hand.
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

## On a table

Face-down on a table the proximity sensor stays covered, so pocket rules can't see a theft. Lay
the phone face-down while it's locked and keep it still: after about 10 s two short buzzes mean
it is guarding the table (the phone must be flat, still and on something hard; a lap, sofa, bag
or a body never counts, because they rock, sink or breathe). Then:

| What happens | Result |
|---|---|
| Tilted 20° or more, turned over to look | Warning |
| Lifted 3 cm or more, even level | Warning |
| Picked up slowly and held (never still again, tilted 4°+) | Warning after 3 s |
| Comes to rest at a new angle (8°+), like on a hand or a book | Warning |
| Pushed off the edge (falling, without the kick of a bump) | Warning |
| Sensor uncovered together with any lift-like motion | Warning |
| Slid across the table (10 cm or more) | One beep; repeated slides beep for 3 s |
| Knocked, bumped, a cup put down, its own vibration | Nothing |
| A car pulling away with it on the seat | Leaves table mode silently, once road or engine vibration (or a 5 s push) shows it's a vehicle |
| Picked up to answer a call, even in silent mode | Your pick-up: back to armed, silently |

Table warnings and "turned to look" warnings stay silent for their first 1.5 s, so picking up your
own phone and unlocking it with a fingerprint makes no sound. They beep on the alarm stream (heard
in silent mode) without vibrating, so a phone put back down can tell it is still; a table siren
starts vibrating after 10 s. Put it back down flat and still within the grace period and the
warning stops by itself (3 times per 10 min at most). With no
delay set, a table pick-up still gets these 1.5 s before the siren. Table mode can be switched
off under Triggers.

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

Download the latest `PocketGuard-vX.Y.Z.apk` from [Releases](../../releases), along with
screenshots of every screen.
Open the release on the phone, download the APK and allow installs from your browser. Official
builds share one signing key, so a new build installs over the old one and keeps your settings.

On first launch, after the short guide, **Get it ready** walks through what Android blocks by
default, one tap each: a screen lock that locks immediately, notifications, running in the
background, the alarm over the lock screen, autostart on brands that need it (Xiaomi, Realme,
Oppo, Vivo…), and optionally the camera and Do Not Disturb. Tapping the power button while a
required step is missing brings it back, and arms the guard once it's done.

## Architecture

| File | Role |
|---|---|
| `GuardEngine.kt` | Every decision as a pure state machine (no Android types). Unit tested. |
| `MotionProcessor.kt`, `TableMonitor.kt` | Raw accelerometer to gravity direction, stillness, jolts, falls and walking; table placement and the table rules. Pure Kotlin, unit tested at 25-200 Hz with noise and sensor offsets. |
| `GuardService.kt` | Foreground service: feeds sensors and system broadcasts to the engine, carries out its side effects, runs the lock check. Direct-boot aware. |
| `AlarmPlayer.kt` | Generated beeps and siren, volume lock, Do Not Disturb override, torch, vibration. |
| `IncidentRecorder.kt`, `IntruderCamera.kt`, `History.kt` | Evidence: CameraX photos, JSON history. |
| `BluetoothReceiver.kt`, `TransitRecognition.kt` | Earbud and transit auto-arm; boot and update re-arm. |
| `AlarmActivity.kt` | Alarm screen shown over the lock screen. |
| `HomeScreen.kt`, `TriggersScreen.kt`, `HistoryScreen.kt`, `SettingsScreen.kt`, `OnboardingScreen.kt`, `SetupScreen.kt` | Compose UI: four tabs, the guide and the first-run setup. |
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
   - table: protected when laid face-down, tilt warns, putting it back stops it, picking it up sirens
   - a gentle pull turned to look, and a pocket swap, with no jolt or light
   - pull-out with a frozen light sensor
   - the owner's own pull-out and unlock within grace
   - sensor flicker and walking ignored
   - airplane mode and charger triggers
   - the lock check: a lock delay caught on screen timeout, kept after a power-button lock,
     cleared by Fix and passing once the lock is immediate
   - the alarm resuming after a reboot, before the first unlock
   - every screen opened and screenshotted, and monkey crash fuzzing

   Screenshots, the app log and keyguard state are uploaded for each failure.
4. **Release**: only for `main`, only after all of the above pass, only when the build was
   signed with the official key, and only for a new version.

## Releasing

The version is in [`VERSION`](VERSION) (`major.minor.patch`). To ship one, bump it in the same
pull request or commit as the change: patch (`1.0.1`) for fixes, minor (`1.1.0`) for new
features, major (`2.0.0`) for big changes. When it reaches `main` and CI passes, the release is
tagged `v1.0.1` with notes generated from the merged pull requests. Pushes that keep the same
version are tested but not released.

## Known limits

- No app can block power-off or a forced restart (power + volume). After a forced restart the
  guard re-arms and resumes the alarm; use the phone's own "password to power off" where it exists,
  and a 0 s grace period to give a thief no time.
- The photos and history stay on the stolen phone; nobody is notified remotely yet.
- Some phones only report proximity while the screen is on. Use the in-app **Sensor check** with
  the screen off in a pocket to find out.
- iPhone isn't possible: iOS gives apps no background sensor access.
- Motion can't tell the owner from a thief until someone unlocks. A thief who pulls the phone out
  gently, never wakes the screen and keeps it at the pocket's angle (or slips it upright into
  their own pocket) isn't caught by motion; the light, jolt and tamper triggers still apply.
- Without a gyroscope (budget phones) slow, level slides under about 10 cm/s aren't measured;
  any lift or tilt that follows is.
- A phone laid face-down on a lap while sitting very still may be taken for a table. Wait for
  the double buzz: if you feel it in your lap, keep table mode in mind when you stand up.

The full detection design, its threat model and what is planned next are in
[docs/detection-plan.md](docs/detection-plan.md).

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
