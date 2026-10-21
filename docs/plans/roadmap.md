# Pocket Guard roadmap and work breakdown

Each work package (WP) ships as one pull request. CI builds and tests it, the owner merges it,
and the merge to `main` publishes the release (APK on GitHub; AAB for Play from WP2 on).

Owner: **C** = Claude writes it · **T** = Taiyeb does it (accounts, consoles, phone tests).

**Status (2026-10-21):** WP1–WP5 are done (v1.5.0–v1.9.0). Work after WP5 (v2.0.0–v2.4.0:
reviews, the schedule, the 4-tap SOS, the website, false-alarm fixes) is in
[../history/releases.md](../history/releases.md). WP6 (Play Store) is still open; its live task
list is in [open-items.md](open-items.md). The tables below are the original plan and are kept
for the record.

## WP1 · SOS: hold + and − with recording (v1.5.0)

Superseded in v2.2.0: SOS is now volume up tapped 4 times quickly (see the detection plan).

| ID | Task | Owner | Done when |
|---|---|---|---|
| 1.1 | Gesture: press power, then hold volume + and − for 3 s (screen on or lock screen) | C | Key watcher fires SOS; e2e passes |
| 1.2 | Remove the silent-audio fallback (Play policy: device interference) | C | No AudioTrack or volume resets left |
| 1.3 | Disclosure and "I agree" before opening Accessibility settings | C | Dialog in Triggers; strings EN/BN |
| 1.4 | e2e: wake the screen, hold both keys; a short squeeze does nothing | C | Scenario 7e green |
| 1.5 | Help texts, README, How to test | C | Updated |
| 1.6 | Merge PR #3 | T | v1.5.0 released |
| 1.7 | Phone test: arm, lock, press power, hold + and −; check the recording in History | T | Works on your phone |

## WP2 · Play readiness (v1.6.0)

| ID | Task | Owner | Done when |
|---|---|---|---|
| 2.1 | Build flavours `play` and `full` (only the SMS permission differs) | C | CI builds both; AAB for `play` |
| 2.2 | SOS action on the lock-screen notification | C | Tap fires SOS while locked |
| 2.3 | SOS Quick Settings tile | C | Tile fires SOS from the lock screen |
| 2.4 | SOS button in the app | C | Fires SOS |
| 2.5 | Full-screen alarm: check the permission and ask in setup when not granted | C | Setup row on Android 14+ |
| 2.6 | Replace the battery-optimisation permission with a settings link | C | Permission removed |
| 2.7 | Prominent disclosure before every runtime permission | C | Camera, mic, location, activity, Bluetooth, accessibility |
| 2.8 | Privacy policy page (Cloudflare Pages or GitHub Pages) and in-app link | C (+T hosting) | Public URL |
| 2.9 | Target SDK 36, retest | C | e2e green |
| 2.10 | Setup tip: Android's own Emergency SOS (power ×5) | C | Shown in setup |

## WP3 · Emergency contacts and alerts (v1.7.0)

| ID | Task | Owner | Done when |
|---|---|---|---|
| 3.1 | Contacts screen: up to 5, from the contact picker (no contacts permission) or typed | C | Saved locally |
| 3.2 | Approve-the-alert screen: exact message, recipients, "Send test" | C | Required before alerts are on |
| 3.3 | SOS countdown: 5 s with Cancel before messages; alarm and recording start at once | C | Unit and e2e tests |
| 3.4 | Location during SOS only (foreground service location type, while in use) | C | Stops when SOS ends |
| 3.5 | SMS with GPS: automatic in `full`, one tap in `play` | C | Emulator receives it |
| 3.6 | Follow-ups on move (> 200 m, every 5 min at most, 3 times); "False alarm, I'm safe" | C | Tests |
| 3.7 | "Share on WhatsApp" button | C | Opens WhatsApp ready to send |
| 3.8 | Apply for Play's SMS exception (physical safety) | T | Approved or refused |

## WP4 · Account, live location, email (v1.8.0)

| ID | Task | Owner | Done when |
|---|---|---|---|
| 4.0 | Create Supabase, Cloudflare and Brevo accounts; add the keys to GitHub Secrets | T | Secrets in place (guide in [docs/setup/backend.md](../setup/backend.md)) |
| 4.1 | Database schema and row-level security (profiles, contacts, incidents, locations, media) | C | Migrations deploy from CI |
| 4.2 | Edge functions: start incident, notify (Brevo), viewer token | C | Deployed from CI |
| 4.3 | Anonymous sign-in per install (no email or password); alerts still work offline | C | e2e against a local backend |
| 4.4 | "Delete my online data" in the app; by request for people without the app | C | Deletes all rows |
| 4.5 | Live page: map, trail, battery, last seen; no login | C | Cloudflare Pages (`site/live/`) |
| 4.6 | Location upload every 15 s during SOS (queued offline) | C | Tests |
| 4.7 | Alert emails under the same confirm rules | C | Received in a test inbox |
| 4.8 | Demo account for Play reviewers | C+T | Works |

## WP5 · Media and retention (v1.9.0)

| ID | Task | Owner | Done when |
|---|---|---|---|
| 5.1 | Signed R2 upload URLs from an edge function (no keys in the app) | C | Deployed |
| 5.2 | Upload queue: location first, 480p video pieces on their own thread; retry when offline | C | Tests |
| 5.3 | Media on the live page through short-lived links | C | Works |
| 5.4 | Retention: R2 lifecycle 90 days; database cleanup; per-user cap | C | Daily job |
| 5.5 | Daily GitHub job: Supabase keep-alive and usage report (cleanup runs in the database) | C | Scheduled workflow |

## WP6 · Play Store submission

| ID | Task | Owner | Done when |
|---|---|---|---|
| 6.1 | Play developer account ($25), package name registration | T | Account ready |
| 6.2 | Store listing text (EN/BN), screenshots, feature graphic | C (+T upload) | Draft ready |
| 6.3 | Declarations: foreground service types, accessibility, full-screen intent, Data safety, content rating, privacy policy | C drafts, T submits | Submitted |
| 6.4 | Closed test: 12 testers for 14 days | T | Done |
| 6.5 | Production release | T | Live |

## Order and parallel work

WP1 → WP2 → WP3 → WP4 → WP5 → WP6. Things Taiyeb can do at any time, so nothing waits:
4.0 (accounts and secrets), 6.1 (Play account), and the phone tests after each release.
