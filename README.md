# Pocket Guard

Android anti-pickpocket alarm. Arm it before you get into a crowd. If the phone leaves your
pocket or bag without your unlock, it beeps (in your earbuds if connected), then sounds a
full-volume siren the thief can't silence. Your emergency contacts get an SMS with the phone's
location and the front camera photographs whoever is holding it.

English and Bangla. Android 8.0+ (minSdk 26). Built and tested entirely in CI.

## How it works

1. **Armed**: waiting for the phone to go into a pocket (proximity sensor covered for 1.5 s).
2. **Protected**: a short buzz confirms it.
3. **Taken out while locked**: proximity uncovered for 0.8 s, plus evidence that it really left
   the pocket:
   - **Light**, when fresh readings arrive from both inside and outside the pocket, and outside
     is brighter.
   - **Motion** otherwise: a jolt over 2.5 m/s² plus the sensor staying uncovered for 1.5 s.
     Many phones stop reporting light while the screen is off, so this is the usual path.

   Late readings still count for 2.5 s. A motion-only warning cancels itself if the sensor is
   covered again within 1 s (pocket fabric). A light-confirmed one doesn't, since that's a
   thief's hand.
4. **Warning**: beeps and vibration. Unlock within the grace period (default 5 s, 1-15 s) and
   nothing else happens.
5. **Siren**: alarm stream at full volume (through silent mode, and through "Total silence" Do Not
   Disturb when allowed), volume buttons locked, torch strobe, "THIS PHONE IS STOLEN" over the lock
   screen, SMS to contacts, intruder photos.
6. **Unlock** with PIN, pattern, password, fingerprint or face: everything stops, contacts get an
   "all clear" if they were alerted, and the guard re-arms by itself.

Unlocking only counts as the owner if the phone was credential-locked when the alarm began. If
Smart Lock or the screen-lock delay kept it unlocked, the alarm screen says to tap DISARM in the
app instead. The app refuses to arm without a PIN, pattern or password.

## Triggers

| Trigger | What happens | Default | Needs |
|---|---|---|---|
| Taken out of pocket or bag while locked | Warning, then siren | Always on | Proximity sensor |
| Power menu opened while locked | Menu closed, siren at once (or fake shutdown) | On | Accessibility service |
| Airplane mode turned on while locked | Siren at once | On | |
| Charger unplugged while locked (wired, still unplugged after 2 s) | Warning, then siren | On | |
| Earbuds or watch disconnect while out of pocket and locked | Warning, then siren (in pocket: buzz only) | Off | Nearby devices permission |
| Phone restarted or app killed during an alarm | Alarm resumes, before the first unlock too | Always on | |

**Auto-arm** (both off by default) when chosen earbuds or a watch connect, or when Google
activity detection sees you board a bus, train or car. Auto-armed sessions turn off after 4 hours.
Car stereos and speakers never count as earbuds. You can also pick the exact devices that count.

Calls are ignored while the audio is at the earpiece, because the ear covers and uncovers the
proximity sensor. A phone pocketed unlocked is locked automatically, and so is one whose screen
turns off while armed (both need the accessibility service).

## Evidence

- **Emergency SMS** from the phone's own SIM (works without mobile data) to up to 3 contacts.
  The first alert goes out immediately with the last known location, then fresh fixes follow,
  with an update every 5 minutes (6 updates). Delivery is confirmed per message. Failed sends retry
  every minute, and at once when airplane mode turns off. At most 3 alerts an hour. Old locations
  are labelled with their age.
- **Intruder photos**: 2 silent front-camera photos per siren, then the torch strobe starts.
  Skipped in fake shutdown, because the camera privacy dot would give it away.
- **Theft history**: trigger, outcome, SMS count, last location (opens in maps) and photos.
  Stored only on the phone. Warning-only entries are kept 7 days, alarms 90 days.
- **Fake shutdown** (optional): opening the power menu shows "Shutting down…", then a black
  screen while location keeps going out. The owner holds the screen for 3 s to get the unlock
  prompt.

## Test mode

Test mode plays a quiet siren and marks SMS as `[TEST]`. It is off by default and switches itself
off after an hour, so a forgotten test mode can't weaken a real alarm. The in-app "How to test"
list walks through every trigger.

## Install

Every push to `pocket-guard/` runs the CI pipeline below. When it passes, the APK is published
under [Releases](../../releases) as `pocket-guard-build-N`. Open the release on the phone,
download the APK and allow installs from your browser. Builds share one signing key, so a new
build installs over the old one and keeps your settings.

After installing, work through the in-app setup checklist: notifications, battery optimization,
lock-screen alarms, screen lock, power-off protection (accessibility), Do Not Disturb access and
autostart (Xiaomi, Realme, Oppo, Vivo).

On Android 13+, the accessibility switch is greyed out for sideloaded apps until you open App
info, tap ⋮ and choose "Allow restricted settings".

## Architecture

| File | Role |
|---|---|
| `GuardEngine.kt` | Every decision as a pure state machine (no Android types). Unit tested. |
| `GuardService.kt` | Foreground service: feeds sensors and system broadcasts to the engine and carries out its side effects. Direct-boot aware. |
| `AlarmPlayer.kt` | Generated beeps and siren, volume lock, Do Not Disturb override, torch, vibration. |
| `IncidentRecorder.kt`, `Sms.kt`, `Locator.kt`, `IntruderCamera.kt`, `History.kt` | Evidence: SMS with delivery tracking, fused location, CameraX, JSON history. |
| `PowerMenuWatcher.kt` | Accessibility service: power-menu detection, screen locking. |
| `BluetoothReceiver.kt`, `TransitRecognition.kt` | Earbud and transit auto-arm; boot and update re-arm. |
| `AlarmActivity.kt`, `FakeOffActivity.kt` | Screens shown over the lock screen. |
| `MainScreen.kt`, `OnboardingScreen.kt`, `HistoryScreen.kt` | Compose UI. |
| `Prefs.kt` | Settings, in device-protected storage so they can be read before the first unlock. |

## CI pipeline

`.github/workflows/pocket-guard.yml`:

1. **Unit tests**: 47 `GuardEngine` tests covering the ladder, every trigger, walking, dark pockets,
   a light sensor that freezes with the screen off, late readings, flicker recovery, calls and
   restarts.
2. **Android lint** with `abortOnError`. The full report is printed on failure.
3. **Emulator end-to-end test** (`e2e/run.sh`, API 34, 46 checks). It sets a PIN, writes the
   app's settings with `run-as` (an emergency contact pointing at the emulator's own number), enables
   the accessibility service, and fakes GPS, the cameras, and the proximity, light and motion
   sensors. It then checks the app log, stored history, photo files and screen state for:
   - pickpocket: siren, SMS accepted by the network, location in the alert, intruder photos,
     history entry, all-clear after unlock
   - pull-out with a frozen light sensor
   - the owner's own pull-out and unlock within grace
   - sensor flicker and walking ignored
   - power menu, airplane mode and charger triggers
   - auto-lock of a phone pocketed unlocked
   - fake shutdown
   - the alarm resuming after a reboot, before the first unlock
   - monkey crash fuzzing

   Screenshots, the app log and keyguard state are uploaded for each failure.
4. **Release**: only after all of the above pass.

## Known limits

- No app can block power-off or a forced restart (power + volume). The power-menu alarm catches
  the normal attempt. After a forced restart the guard re-arms and resumes the alarm, but the
  seconds before the reboot can't be covered.
- A Faraday bag or removing the SIM stops SMS and location. The siren still plays.
- Some phones only report proximity while the screen is on. Use the in-app **Sensor check** with
  the screen off in a pocket to find out.
- Power-menu detection depends on the phone maker's labels (English and Bangla are matched).
- The Play Store restricts accessibility services, so these test builds are sideloaded. Play
  Protect may block the install outright ("App blocked to protect your device") because the app
  requests accessibility and SMS. Turn off "Scan apps with Play Protect" in the Play Store's
  Play Protect settings for the install, then turn it back on.
- iPhone isn't possible: iOS gives apps no background sensor access.
