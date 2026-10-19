# Pocket Guard

https://github.com/user-attachments/assets/5b038718-6057-4d9d-8a62-5170a73fcfbd

Android anti-pickpocket alarm. Arm it before you get into a crowd. If the phone leaves your
pocket or bag without your unlock, it beeps (in your earbuds if connected), then sounds a
full-volume siren the thief can't silence, and the front camera photographs whoever is holding it.

English and Bangla. Android 8.0+ (minSdk 26).

## Screens

A dark theme with a purple glow, set in Manrope. A floating tab bar switches between four tabs:

- **Home**: a power button inside a ring of moving dots. The ring's colour and speed follow the
  guard (grey off, lavender armed, purple protected, amber warning, red siren). Also shows the status, time
  armed, unlock delay, last alarm, how many setup steps are left, and an SOS card while armed.
- **Triggers**: every alarm and arming-reminder switch, the earbud picker, intruder photos, SOS,
  emergency contacts and the arming schedule.
- **History**: past alarms with their intruder photos and SOS recordings.
- **Settings**: unlock delay, test mode, the setup checklist, a live sensor check, the detection
  log, language (Android 13+), Online (live link and deleting your online data; only with the backend built in), the guide, the privacy policy
  and the how-to-test list.

CI takes a screenshot of every screen on the emulator and attaches them to each release.

## How it works

1. **Armed**: waiting for you to lock it. Locking is the signal: press power and it is protected
   straight away once it is in a pocket or lying still.
2. **Protected**: in a pocket (proximity covered for 0.5 s, screen off or locked), a short buzz
   confirms it; put in the pocket first and locked after, it is protected the moment the screen
   goes black. Lying still with the screen black (face-up on a table, on a seat), two short buzzes
   mean table protection is on at once (unless table mode is off under Triggers).
3. **Taken out while locked**: the proximity sensor uncovered for 0.8 s is enough. Being armed
   is your choice, so once armed it asks for no further proof: a careful thief gives none (no
   jolt, no light, no turn). Fabric flickering over the sensor re-covers it within a second, and
   a warning that is covered again within 1 s stops by itself, unless bright light showed it
   really was out. Covered again at a new angle right after, it is judged as going into another
   pocket and warns again.
4. **Warning**: beeps and vibration. A plain uncover (or a table pick-up) stays silent for its
   first 1.5 s, so taking out your own phone and unlocking it with a fingerprint makes no sound;
   a yank or bright light beeps at once. Unlock within the grace period (default 5 s, 0-15 s) and
   nothing else happens. With 0 there's no warning: the uncover is confirmed after 0.25 s and the
   siren starts at once, and still stops by itself if covered again within 1 s.
5. **Siren**: alarm stream at full volume (through silent mode, and on Android 14 and older through "Total silence" Do Not
   Disturb when allowed), volume set back to full if turned down, torch strobe, "THIS PHONE IS STOLEN" over the lock
   screen, intruder photos.
6. **Unlock** with PIN, pattern, password, fingerprint or face: everything stops and the guard
   re-arms by itself.

Unlocking only counts as the owner if the phone was credential-locked when the alarm began. If
Smart Lock or a screen-lock delay kept it unlocked, only the owner's credential in the app stops it:
"I'm the owner" on the alarm screen opens the app, where the power button asks for the PIN first.
The Quick Settings tile, the schedule's pause buttons and switching it to SOS don't stop it either.
The app refuses to arm without a PIN, pattern or password.

## On a table

Face-up (sensor uncovered): lock it and it guards the table as soon as it lies still, about 1 s.
Face-down the proximity sensor stays covered, so it is first protected like a pocket (half a
second: lifting it uncovers the sensor and warns), and after about 4 s still on something hard
two short buzzes mean full table protection, which also catches a slide (a lap, sofa, bag or a
body never counts, because they rock, sink or breathe). Once it guards the table, any move that
could be someone taking it warns:

| What happens | Result |
|---|---|
| Slid along the table (5 cm or more) | Warning |
| Tilted 10° or more, turned over to look | Warning |
| Lifted 3 cm or more, even level | Warning |
| Picked up slowly and held (never still again, tilted 6°+) | Warning after 3 s |
| Comes to rest at a new angle (8°+), like on a hand or a book | Warning |
| Pushed off the edge (falling, without the kick of a bump) | Warning |
| Sensor uncovered together with any lift-like motion | Warning |
| Knocked, bumped, a cup put down, its own vibration | Nothing |
| Another app's notification or a call buzzing it | Nothing: a vibration motor shakes the phone fast around where it lies, a hand moves it away. The slide and lift sums smooth that shaking out, and while it buzzes (and 0.3 s after) the table rules wait. Picked up mid-buzz, the hand shows straight after (held, tilted, at a new angle) |
| Laid on a car seat, then driving | Stays guarded and quiet: an idling engine or the drive pauses the slow pick-up rules until it has been parked (still) for 30 s; picking it up afterwards warns like any table pick-up |
| Picked up to answer a call, even in silent mode | Your pick-up: back to armed, silently |

Table warnings stay silent for their first 1.5 s, so picking up your own phone and unlocking it
with a fingerprint makes no sound. They beep on the alarm stream (heard in silent mode) without
vibrating, so a phone put back down can tell it is still; a table siren starts vibrating once it
can no longer be put back (10 s after the pick-up). Lifted or tilted and put back down flat and
still within the grace period, the warning stops by itself (3 times per 10 min at most); a slide
warns until you unlock. With no delay set, a table pick-up still gets these 1.5 s before the
siren. Table mode can be switched off under Triggers.

## SOS: a call for help

For harassment in a crowd: while the guard is armed, **wake the screen (power, or a fingerprint
unlock) and tap volume up 4 times quickly** (one button, by feel; in a pocket works). A buzz confirms it,
and at once:

- the SOS alarm: a shrill whistle and, in its pauses, a spoken "Help! Stop it!" (in Bangla when
  the app shows Bangla and the phone has a Bangla voice), at full volume through silent mode, with
  "I NEED HELP" and "● Recording" on the screen;
- video with sound from the back camera (in a pocket the sound is the evidence), saved in
  one-minute pieces so a grabbed phone still keeps everything up to then, for up to 15 minutes (it
  stops early with under 300 MB free, and records nothing before the first unlock after a restart).

Unlock to stop both. The recordings are in History, to play, save to the gallery or share; until
you do, they stay in the app's private storage. Recording people without consent is against the law in
some places.

**Emergency contacts** (Triggers → Emergency contacts): up to 5 people, picked from your contacts
or typed in, and the name the alert uses. You see the exact messages (the alert, the update
and "false alarm") and approve them with the list; any change asks again, including a new
wording or switching the live link, and Home says when alerts are off for that reason. A number
needs at least 6 digits. The email follows the app's language; the SMS doesn't (below). When SOS starts, a 5-second countdown with **Cancel** runs on the alarm
screen (the alarm and recording don't wait), then each contact gets an SMS: *"Taiyeb needs help!
SOS from Pocket Guard at 14:05. Location: https://maps.google.com/?q=… (within 10 m)"*. The SMS is
always in English letters, English or Banglish (*"Taiyeb er shahajjo dorkar!…"*, picked on the
contacts card), whatever language the app shows: one Bangla letter makes an SMS Unicode, which
holds 70 characters a part instead of 160, so the alert with its two links would take 4 SMS
instead of 2. The
location is read only while SOS runs; an update follows when you move 200 m (at most every 5 min,
3 times), or as soon as a location is known if the alert had none, and stopping SOS within 15 s of the alert sends "false alarm, I'm safe". Locations older than two minutes are never sent. The GitHub build
sends the SMS itself and resends one that didn't go (no signal), up to 5 tries; the Play build fills in the SMS app and you tap Send (Play allows sending
SMS on its own only with an approved exception). Each tap (and "Share on WhatsApp") carries the whole alert with the latest location and link, and the unlock it needs doesn't end the SOS: stop it in the app when you're safe.
If every text fails (no signal or SIM), the alarm screen says so and opens the SMS app instead. A
restart during SOS carries on: an alert already sent isn't sent again, and one you cancelled stays
cancelled. "False alarm" goes only to whoever the alert reached, and a retry still waiting never
follows it.

**Live location link and alert emails** (Settings → Online → Live location link, on by default):
the SMS also carries a private link to a live map (`https://pocket-guard.pages.dev/live/#t=<token>`), and
contacts with an email address get the alert by email, then "false alarm" if you stop right away.
With the live link on, the SOS video is recorded at 480p (about 12 MB a minute; 720p, about 30 MB,
without it) and each one-minute piece is uploaded to
R2 as it's saved, through a link the server signs for that piece; the live page lists the
recordings and plays one through a 10-minute link. Pieces still uploading when SOS ends carry on
for up to 30 minutes.
The phone signs in anonymously (no email or password) when you approve the alert, so SOS
doesn't wait for it. During SOS it opens an SOS record and uploads its
location (read about every 30 s) every 15 s while SOS runs; cancelling during the countdown
uploads nothing more and just ends the record; the page refreshes every 10 s and stops working a day after
SOS ends, and the server deletes everything after 90 days. Each step retries until it gets
through, and ending the SOS (and the false-alarm email) keeps trying after it stops. Email
addresses aren't stored on the server: only a one-way hash, to cap the alerts any address gets. "Delete my online data" removes the
account and all of it at once. Approving the alert covers this too, so switching it on or off
asks for approval again. Without the backend built in (forks, local builds) the app is SMS-only.

Other ways to start SOS while armed, none of which needs the Accessibility setting: the **SOS**
button on the Pocket Guard notification (on the lock screen, no unlock), the **SOS** tile in
Quick Settings (swipe down on the lock screen and tap), and the SOS card on Home. Started from an
unlocked phone, "I'm the owner" on the alarm screen stops it, or lock and unlock.

Setup (asked in the first-run checklist, and in Triggers → SOS): allow Camera and Microphone, and
turn on **Pocket Guard SOS buttons** in Accessibility (the app explains what it sees and asks
first, then opens the switch itself). Installed outside the Play Store, Android 13+ greys that
switch out ("restricted setting"); the app then shows the three steps, each with a button to the
right screen: try the switch once, App info → ⋮ → Allow restricted settings, then turn it on. It watches the volume keys and
nothing else, and every key still works as usual. Why wake the screen first: with the screen off,
Android shows the volume keys to no app at all, accessibility included; the lock screen is
enough. One button on purpose: the power key is taken by the phone's own gestures (camera on two
presses, Emergency SOS on five on many phones), and two-button holds are hard in a hurry. Taps don't count while music, a video or a call is
playing (that's someone turning the volume up), fewer than 4 or slower than 2 s don't count, and
volume down starts the count over.

Harassed with the guard armed: grabbing the phone from the pocket starts the theft warning as
usual. Wake the screen and tap volume up 4 times, or tap **🆘 I need help** on the alarm screen
(no unlock needed): the theft alarm becomes SOS, with the alerts, location and recording. The taps
count while the alarm sounds, even though its beeps play like music. If a thief taps the button,
the owner's contacts are told and the recording starts, which only helps her; and if the theft
alarm began on an unlocked phone, the SOS keeps its rule, so it still ends only with the owner's
credential. Taps don't count while the phone rings either (that's someone silencing it).

## Setup requirement: the phone must lock immediately

A phone pocketed with the screen just turned off, but not yet locked, would let a thief pass as
the owner. So the phone's own settings must lock it at once:

- **Lock after screen timeout**: Immediately
- **Power button instantly locks**: on
- **Password needed to power off**: on, if the phone has it (Xiaomi, Realme, Oppo, newer Samsung).
  This replaces a power-off alarm.

Android hides those settings from apps, so Pocket Guard tests them. Every time the screen turns
off while armed, it checks 1.5 s later that the phone is locked. If not, it shows a warning
notification, and "Lock immediately" appears in the "Get it ready" list, which leaves it out
until a test catches a delay (most phones with a screen lock already lock at once). The power button locks at once even with a lock
delay, so a later pass doesn't clear the failure: tapping **Fix** (or the notification) starts the
test over. To test, let the screen turn off by itself once.

## Triggers

| Trigger | What happens | Default | Needs |
|---|---|---|---|
| Taken out of pocket or bag while locked | Warning, then siren | Always on | Proximity sensor |
| Airplane mode turned on while locked | Siren at once | On | |
| Charger unplugged while locked (wired, still unplugged after 2 s) | Warning, then siren | On | |
| Moved or picked up on a table while locked | Warning, then siren | On | Motion sensor |
| Earbuds or watch disconnect while out of pocket and locked | Warning, then siren (in a pocket or on a table: buzz only) | Off | Nearby devices permission |
| Phone restarted or app killed during an alarm | Alarm resumes, before the first unlock too | Always on | |

**Arming reminders** (both off by default): when chosen earbuds or a watch connect, or when Google
activity detection sees you board a bus, train or car, a notification asks whether to arm; one tap
arms it. It never arms by itself on a guess, since that set off alarms nobody expected.
Car stereos and speakers never count as earbuds, and you can pick the exact devices that count.

**Schedule** (Triggers → Schedule, off by default): arm by itself at set times, like a Focus mode
schedule. Add up to 5 times, each with days and a start and end (*every day 09:00–18:00*, or
*Fri 22:00–02:00*, which runs past midnight; equal start and end means the whole day). At the
start it arms and says until when; at the end it turns off, unless you had armed it yourself
before the time began. Turning it off by hand inside a scheduled time pauses it for 15 minutes,
then it arms again; Home and the notification offer **Arm now**, **+15 min** and **Skip today**
(off until that time ends, not the days chained after it; on the lock screen those last two ask
for your unlock first, so on Android 11 and older they're on Home only). None of them shows during
an alarm. Switching the schedule off turns off a guard it armed. It follows the phone's clock and
time zone, survives restarts (a time that ended while the phone was off turns the guard off at
start-up), and catches up when the app is opened. The GitHub build starts on the minute once
"Alarms & reminders" is allowed. Otherwise, or in the Play build without "Run in the
background", Android may start it late or refuse, and a notification asks you to tap to arm.
Armed by the schedule while the app is closed, Android withholds the camera, microphone and
location until you open the app once (a notification says so).

Calls are ignored while the audio is at the earpiece, because the ear covers and uncovers the
proximity sensor.

## Evidence

- **Intruder photos**: 2 silent front-camera photos per siren (not before the first unlock after a restart), then the torch strobe starts.
- **Theft history**: trigger, outcome and photos. Warning-only entries are kept 7 days, alarms 90, at most 50 entries; SOS recordings past 1 GB in all
  are deleted, oldest first. Photos and
  recordings stay in the app's private storage unless you save or share them (intruder photos never leave the phone; SOS videos
  are also uploaded only for the live link). Tap one to open it full screen in an app you choose;
  **Save to phone** copies them to the gallery (Pictures and Movies, "Pocket Guard" albums), and
  **Share** sends them anywhere.

Both stay on the phone, for when it's recovered.

## Test mode

Test mode plays the siren and the SOS alarm quietly; SOS texts during it are marked [TEST] and the
email is a test one. It is off by default and switches itself off after an hour, so a
forgotten test mode can't weaken a real alarm. The in-app "How to test" list walks through the
main triggers and SOS.

## Install

Download it from **https://pocket-guard.pages.dev** on the phone (people with access to this private
repository can also take `PocketGuard-vX.Y.Z.apk` from [Releases](../../releases), along with
screenshots of every screen).
The website's download button (`/download/PocketGuard.apk`, with `/download/latest.json` for the
version) serves the latest release's APK: `.github/workflows/pages.yml` fetches it on every deploy (a push
to `main` that changes `site/`, a manual run, or CI's `site` job right after a new release) and
always deploys the site as it is on `main`; it fails if the APK is 25 MiB or more. The APK is never
committed.
To show the Google Play badge on the site once the listing is live, follow the `GOOGLE PLAY`
comment in `site/index.html`.
Open the downloaded APK on the phone and allow installs from your browser. Official
builds share one signing key, so a new build installs over the old one and keeps your settings.

On first launch, after the short guide, **Get it ready** walks through what Android blocks by
default, one tap each: a screen lock that locks immediately, notifications, running in the
background, the alarm over the lock screen (Android 14+), autostart on brands that need it (Xiaomi,
Realme, Oppo, Vivo…), and optionally the camera, the SOS buttons and recording, emergency contacts
and (Android 14 and older) Do Not Disturb. Tapping the power button while a
required step is missing brings it back, and arms the guard once it's done.

## Architecture

| File | Role |
|---|---|
| `GuardEngine.kt` | Every decision as a pure state machine (no Android types). Unit tested. |
| `MotionProcessor.kt`, `TableMonitor.kt` | Raw accelerometer to gravity direction, stillness, jolts, falls and walking; table placement and the table rules. Pure Kotlin, unit tested at 25-200 Hz with noise and sensor offsets. |
| `GuardService.kt` | Foreground service: feeds sensors and system broadcasts to the engine, carries out its side effects, runs the lock check. Direct-boot aware. |
| `AlarmPlayer.kt` | Generated beeps and siren, volume lock, Do Not Disturb override, torch, vibration. |
| `IncidentRecorder.kt`, `IntruderCamera.kt`, `SosVideo.kt`, `History.kt`, `MediaActions.kt` | Evidence: CameraX photos and SOS video, JSON history; open, save and share. |
| `SosTaps.kt`, `SosAccessibilityService.kt`, `SosKeysSetup.kt`, `SosTileService.kt`, `GuardTileService.kt` | The SOS gesture (unit tested), its Accessibility setup, and the Quick Settings tiles. |
| `Notifications.kt`, `Setup.kt`, `Disclosure.kt`, `DetectionLog.kt` | Notifications, the setup checks, permission disclosures and the on-phone detection log. |
| `BluetoothReceiver.kt`, `TransitRecognition.kt` | Earbud and transit arming reminders; boot and update re-arm. |
| `ArmSchedule.kt`, `ArmScheduler.kt`, `ScheduleUi.kt` | The arming schedule: its time windows (unit-tested), arming, pausing and the next alarm, and its screens. |
| `AlarmActivity.kt` | Alarm screen shown over the lock screen. |
| `HomeScreen.kt`, `TriggersScreen.kt`, `HistoryScreen.kt`, `SettingsScreen.kt`, `OnboardingScreen.kt`, `SetupScreen.kt` | Compose UI: four tabs, the guide and the first-run setup. |
| `Components.kt`, `Theme.kt` | Shared UI pieces (cards, rows, floating tab bar, particle ring) and the dark purple theme with Manrope. |
| `Prefs.kt` | Settings, in device-protected storage so they can be read before the first unlock. |
| `SosAlerts.kt`, `AlertPlan.kt`, `EmergencyContacts.kt`, `EmergencyContactsCard.kt` | SOS alerts: countdown, SMS, follow-ups, false alarm; the approved contacts and wording. |
| `Backend.kt`, `LiveShare.kt` | The online part: anonymous sign-in, the SOS record with its live location and video, alert emails. |
| `supabase/` | The backend: database and its access rules (`migrations/`), the email, recordings (R2 signed links) and account-deletion functions (`functions/`), tests (`tests/`). Deployed by `.github/workflows/backend.yml`. |
| `site/` | Cloudflare Pages (pocket-guard.pages.dev), deployed by `.github/workflows/pages.yml`: the landing page (`index.html`, plain HTML/CSS/JS, no build step), the privacy policy, the live location page (`site/live/`) and `download/` (the latest release's APK and `latest.json`, added at deploy, never committed). |

## CI pipeline

`.github/workflows/ci.yml`, on every push to `main` and every pull request (not for Markdown-only changes):

1. **Unit tests** (both builds): `GuardEngine` and table-rule tests covering the ladder, every
   trigger, walking, dark pockets, a light sensor that freezes with the screen off, late readings,
   flicker recovery, calls, no-delay mode and restarts, plus the motion processor, the SOS taps,
   the schedule and the alert plan.
2. **Android lint** on both builds with `abortOnError`. The full report is printed on failure.
3. **Emulator end-to-end test** (`e2e/run.sh`, API 34), on the debug build (the same code, but
   debuggable, so the test can write its settings with `run-as`; the release APK is not). It sets
   a PIN, and fakes the cameras and the proximity, light and motion sensors. It then
   checks the app log, stored history, photo files, notifications and screen state for:
   - pickpocket: siren, intruder photos, history entry
   - table: protected when laid face-down, tilt warns, putting it back stops it, picking it up sirens
   - face-up on a table: protected at once, picked up warns
   - a gentle pull turned to look, with no jolt or light
   - pull-out with a frozen light sensor
   - the owner's own pull-out and unlock within grace
   - sensor flicker and walking ignored
   - airplane mode and charger triggers
   - the lock check: a lock delay caught on screen timeout, kept after a power-button lock,
     cleared by Fix and passing once the lock is immediate
   - the alarm resuming after a reboot, before the first unlock
   - SOS with real volume-key presses: 2 taps do nothing, 4 sound SOS, also over a theft warning;
     SOS from the notification
   - the arming schedule: a pause holds it off, an unpaused window arms it
   - SOS alerts against a local backend: the SMS with the live link, the email (to a stand-in for
     Brevo), the uploaded trail and video, and the false alarm, then the live page rendered in Chrome
   - every screen opened and screenshotted, and monkey crash fuzzing

   Screenshots, the app log and keyguard state are uploaded for each failure.
4. **Backend tests** (`supabase/tests/`, against a local Supabase): who can read and write what,
   the live page's view, the email function's limits and wording, recordings (signed uploads,
   limits, playback), account deletion, the daily cleanup jobs and the deployment check, after
   `deno fmt`/`deno lint`.
5. **Release**: only for `main`, only after all of the above pass, only when the build was
   signed with the official key, and only for a new version. It publishes the release APK
   (`assembleFullRelease`) and the Play bundle, not the debug build the emulator test ran.
6. **Site**: once a new release is out, runs `.github/workflows/pages.yml` so the website's
   download button serves its APK.

`.github/workflows/backend.yml` deploys `supabase/` to the real project after each CI run on `main` that passes (or by hand),
then checks the deployment with a throwaway account (including an upload to R2 and its removal).
`.github/workflows/daily.yml` keeps the free Supabase project awake and posts a usage report. `.github/workflows/check-backend.yml` checks
that every backend secret still works (run it from the Actions tab after replacing a key).

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
- A theft alarm notifies nobody remotely: its photos and history stay on the phone. Only SOS
  alerts your contacts.
- Some phones only report proximity while the screen is on. Use the in-app **Sensor check** with
  the screen off in a pocket to find out.
- Some phones (MIUI/HyperOS and other battery savers) stop a background app's sensors when the
  screen is off. Settings → **Detection log** shows what the guard saw and decided during a test,
  including whether the motion sensor kept delivering; tap Copy to send it with a bug report.
- iPhone isn't possible: iOS gives apps no background sensor access.
- Nothing can tell the owner from a thief until someone unlocks, so armed it warns on every
  uncover and every move on a table: arm it when you want that (or use the arming reminders), and
  unlock within the silent start when it is you.
- A thief who keeps a hand over the proximity sensor the whole time it comes out of the pocket
  isn't seen by the pocket rule; the table, light and tamper triggers still apply.
- The app reads only the accelerometer (no gyroscope yet), so slow, level slides under about
  10 cm/s aren't measured; any lift or tilt that follows is. For the same reason a car's
  acceleration can look like a tilt: an idling engine or a recognised push pauses the slow
  pick-up rules, but a very quiet (electric) car pulling away hard can occasionally warn. Turn
  table mode off under Triggers if you leave the phone face-down on a seat while driving.
- On a table that keeps shaking (a bouncing knee, a running engine) the slow pick-up rules pause
  until it is still again; uncovering it, turning it over or pocketing it still warns.
- A phone laid face-down on a lap while sitting very still may be taken for a table. Wait for
  the double buzz: if you feel it in your lap, keep table mode in mind when you stand up.

The full detection design, its threat model and what is planned next are in
[docs/detection-plan.md](docs/detection-plan.md).

## Build it yourself

You need JDK 17 and the Android SDK (Android Studio works). The repository is private: you need
access to clone it.

```sh
git clone https://github.com/taiyebnirjhar/pocket-guard.git
cd pocket-guard
./gradlew testFullDebugUnitTest lintFullDebug assembleFullDebug
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

Without the official key the build is signed with your local debug key, so it can't install over an
official release (uninstall that first).

There are two builds of the same code. **full** is what GitHub releases ship. **play** is for
Google Play and leaves out what Play restricts for this kind of app: it doesn't declare the
battery-optimisation, SMS-sending or exact-alarm permissions (setup opens the battery list, SOS
fills in the SMS app, and the schedule may start a few minutes late). CI builds the Play bundle
with `./gradlew bundlePlayRelease` and attaches it to each release as `PocketGuard-vX.Y.Z-play.aab`.
The privacy policy is `site/privacy.html`, published to Cloudflare Pages (https://pocket-guard.pages.dev/privacy.html) by `.github/workflows/pages.yml`.

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
