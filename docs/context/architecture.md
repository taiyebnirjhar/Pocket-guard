# Architecture

Everything is managed or serverless. There is no VPS. Keys live only in GitHub Secrets and
Supabase's own secret store.

```
Phone app ──(anonymous auth, REST, edge functions)──> Supabase (Singapore)
   │                                                     ├─ Postgres + RLS + pg_cron
   │                                                     ├─ notify ──> Brevo (alert email)
   │                                                     └─ media  ──> signed R2 URLs
   ├──(PUT video pieces via signed URL)──> Cloudflare R2 bucket (private, 90-day lifecycle)
   └──(SMS with link)──> contact ──> pocket-guard.pages.dev/live/#t=<token> (Cloudflare Pages)
```

## App

- **`GuardEngine`**: pure-Kotlin state machine, no Android types, unit tested on the JVM.
  Phases OFF → WAITING_FOR_POCKET → IN_POCKET / ON_TABLE → WARNING → SIREN. Triggers include
  POCKET_REMOVAL, TABLE_MOVED, AIRPLANE_MODE, CHARGER_UNPLUGGED, EARBUDS_DISCONNECTED, RESTARTED
  and SOS. All callbacks run on the main looper using `elapsedRealtime`.
- **`GuardService`**: a `LifecycleService` foreground service and the engine's Host. Registers
  sensors (proximity, light, accelerometer) and broadcasts, carries out side effects, runs the
  lock check 1.5 s after screen off. Direct-boot aware. Holds camera, microphone and location
  service types while armed, because Android grants them only to a service started while the
  app is on screen.
- **`MotionProcessor`**: raw accelerometer at about 50 Hz with timestamp-based filters (gated,
  fast and slow gravity), stillness with a learned noise floor, jolts, free fall, gait.
- **`TableMonitor`**: table placement test and table rules (slide ≥ 5 cm, tilt ≥ 10°, lift
  ≥ 3 cm, held, new pose ≥ 8°, fall, uncover with lift). Smooths motion before integrating, and
  ignores a vibration-motor buzz (v2.3.1).
- **`AlarmPlayer` / `AlarmActivity`**: siren on the alarm stream at full volume (re-asserted
  every 200 ms), torch, vibration, full-screen alarm over the lock screen.
- **SOS gesture**: `SosAccessibilityService` (opt-in, "Pocket Guard SOS buttons") watches the
  volume keys only; `SosTaps` counts 4 volume-up taps in 2 s. Taps don't count while audio is
  busy (music, call, ringing), except during Pocket Guard's own alarm.
- **`SosAlerts` / `AlertPlan`**: 5 s countdown, SMS per contact, follow-ups (200 m, ≥ 5 min,
  max 3), false-alarm message within 15 s. Alert stage is persisted in `Prefs`
  (NONE/COUNTING/CANCELLED/SENT) so a restart doesn't re-alert.
- **`Backend` / `LiveShare`**: anonymous Supabase sign-in at approval time, the SOS incident,
  location every 15 s, video pieces to R2, alert emails. Retries with backoff while SOS runs.
- **Evidence**: `IntruderCamera` (2 front photos per siren), `SosVideo` (CameraX, 1-minute
  pieces, 480p when the live link is on, up to 15 min, 1 GB local cap), `History`.
- **`ArmScheduler`**: exact alarms for schedule windows; survives reboot and clock changes.
- **`.OwnerAction`**: a non-exported activity alias the app's own notifications and tile use to
  arm or fix the lock. Other apps can't reach it.
- **`Prefs`**: device-protected storage so settings work before the first unlock.

## Backend (`supabase/`)

- **Supabase**, free plan, Singapore region. Anonymous auth, Postgres with RLS from the start,
  `SECURITY DEFINER` functions with `search_path=''`, pg_cron.
- **Migrations** (`supabase/migrations/`): `20261017130000_live_location.sql`,
  `20261017150000_media.sql`, `20261017170000_hardening.sql`, `20261018200000_email_claims.sql`.
  Rules: never edit an applied migration; stay backward-compatible; CI applies all migrations to
  a clean database.
- **Edge functions** (Deno, `verify_jwt=false`, each checks the user itself):
  - `notify`: alert, false-alarm and test emails through Brevo.
  - `media`: signs R2 upload and 10-minute view URLs. SigV4 is hand-written in `_shared/s3.ts`.
  - `delete-account`: deletes the user's R2 prefix and rows; refuses to finish if a delete fails.
- **Stored**: anonymous ID, incident (alert name, battery, start/end, status), locations, email
  **hashes** only, media index. Live link works until 1 day after SOS ends. Data deleted after
  90 days by a daily cron; R2 also has a 90-day lifecycle rule.
- **Limits**: 20 SOS/day per phone; 2,000 location points per SOS; 40 media per incident,
  600 MB/day per user, 8 GB global (then 507); email once per kind per SOS, 6 per address per
  day, 10 addresses per month per phone, 250/day globally (Brevo free is 300). Enforced with
  advisory locks and claim RPCs.
- **Brevo** sends email from `alerts@pocketguard.taiyebnirjhor.com`. Supabase Auth SMTP is also
  set to Brevo by CI.
- **Cloudflare R2** bucket `pocket-guard-media`: private, APAC hint, 90-day lifecycle.

## Website (`site/`)

- Hosted on **Cloudflare Pages** at https://pocket-guard.pages.dev since v2.2.0 (GitHub Pages
  stopped working when the repo went private).
- `index.html`: landing page, plain HTML/CSS/JS, no build step. Hidden Google Play badge slot
  (see the `GOOGLE PLAY` comment).
- `privacy.html`: privacy policy. `CONTACT_EMAIL` repo variable is injected at deploy.
- `live/`: live location map (Leaflet 1.9.4 with SRI, OpenStreetMap), EN/BN, token in `#t=`.
- `download/PocketGuard.apk` and `download/latest.json`: added at deploy from the latest release,
  never committed. Deploy fails if the APK is 25 MiB or more.

## CI/CD (`.github/workflows/`)

| Workflow | When | What |
|---|---|---|
| `ci.yml` | Every push to `main` and every PR (not Markdown-only) | Unit tests (both flavours), lint (`abortOnError`), backend tests, emulator e2e, release, then site deploy |
| `backend.yml` | After CI passes on `main` (`workflow_run`), or by hand | `supabase db push` (dry run first), deploy functions, smoke test with a throwaway account. Skips commits that are no longer `main`'s tip |
| `pages.yml` | Push to `main` touching `site/`, manual, or called by CI after a release | Deploys `site/` as on `main` with wrangler, plus the latest APK |
| `daily.yml` | Daily cron | Keeps the free Supabase project awake; usage report |
| `check-backend.yml` | Manual | Checks every backend secret works without printing it |

## Release process

1. Claude pushes a branch and opens a PR. CI tests it with the debug key and never publishes.
2. Wait for full CI, including e2e, to go green.
3. The owner merges (usually from his phone).
4. On `main`, CI runs again. If `VERSION` is new and the build is signed with the official key,
   it tags `vX.Y.Z` and publishes `PocketGuard-vX.Y.Z.apk` (signed `fullRelease`) and
   `PocketGuard-vX.Y.Z-play.aab`, plus screenshots. Release takes about 20 min after the merge.
5. CI then runs `pages.yml` so the website serves the new APK, and `backend.yml` deploys the backend.

Version bumps: patch for a fix, minor for a feature, major for a big change. Same `VERSION` =
tested, not released. Wait for one release to finish before merging the next.

## End-to-end tests (`e2e/`)

- Android emulator API 34 (`reactivecircus/android-emulator-runner`) on the `fullDebug` build.
- Fake sensors via the emulator console, fake cameras, a PIN set by the test.
- Key presses via root `sendevent` (adb `input keyevent` is invisible to accessibility filters).
- Local Supabase (`e2e/backend.sh`), Brevo stand-in (`e2e/mock_brevo.py`), s3rver for R2.
  `10.0.2.2/32` added on loopback so the host can follow presigned URLs.
- Covers pickpocket, table, face-up, gentle pull, frozen light sensor, owner unlock, flicker,
  walking, airplane/charger, lock check, reboot, SOS taps (2 do nothing, 4 fire, also over a
  theft warning), SOS from the notification, the schedule, SOS alerts against the local backend
  and the live page in headless Chrome, every screen screenshotted, monkey fuzzing.
- Private-repo runners are small (2 cores): CI frees disk and uses a 3 GB emulator partition
  and a 15 min boot timeout. A full run takes about 25–30 min.

## Local development (Claude side)

- No Android SDK in Claude's sandbox. Engine unit tests run in a scratch JVM harness with
  compile-check stubs. Screens, notifications and camera code compile only in CI.
- Maven via `repo1.maven.org`.
