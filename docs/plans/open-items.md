# Open items

Everything still pending as of 2026-10-21 (v2.4.0). **C** = Claude, **T** = Taiyeb (owner).
Remove an item, or move it to "Done" with its version, when it closes.

## Awaiting the owner's answer

| Item | Owner | Notes |
|---|---|---|
| Optional power-menu blocker during the siren | T decides, C builds | GitHub build only, off by default, through Accessibility. Offered 2026-10-19, no answer yet. No app can block a forced restart. |

## Play Store submission (WP6)

| Item | Owner | Notes |
|---|---|---|
| Play developer account ($25), ID verification, package name | T | Personal account. |
| Store listing EN/BN, screenshots, feature graphic | C drafts, T uploads | CI already makes screenshots. No "100% accurate" claims. |
| Declarations: FGS types (with videos), Accessibility, full-screen intent, Data safety, content rating, privacy policy URL | C drafts, T submits | Privacy policy is live at pocket-guard.pages.dev/privacy.html. |
| Confirm whether the Play build can keep the SOS Accessibility key watcher | C + T | Play allows Accessibility mainly for disability tools. Fallback: notification, tile, Home and alarm-screen SOS. |
| SMS emergency-alert exception (from WP3 item 3.8) | T | Until granted, the Play build is tap-to-send. |
| Demo account / instructions for reviewers (from WP4 item 4.8) | C + T | Anonymous accounts, so mainly reviewer notes. |
| Internal test, then closed test: 12+ testers for 14 days | T | Required for a new personal account. Internal testing also avoids the sideload blocks. |
| Production release | T | Upload `PocketGuard-vX.Y.Z-play.aab` from the latest release. |
| Play Integrity on anonymous sign-up | C | Needs the Play account. Fixes throwaway-account email abuse (below). |
| Turn on the Google Play badge on the website | C | Follow the `GOOGLE PLAY` comment in `site/index.html`. After the listing is live. |

## Not fixed from the PR #16 full review

These need a decision or a bigger change.

| Item | Notes |
|---|---|
| Dual-SIM "ask every time" | SMS sending may fail or prompt when no default SIM is set. |
| Secret pepper for email hashes | Hashes are SHA-256 with a fixed public prefix; a secret pepper would stop someone with the database from checking guessed addresses. |
| "False alarm" notification for the Play build | Play build can't send SMS by itself, so the false-alarm message needs a prompt. |
| SHA-pinning of GitHub Actions | Actions are pinned by major tag (e.g. `@v4`), not commit SHA. |
| Deleting the account during an SOS upload | A piece still uploading could land after deletion (cleanup gap). |
| Email abuse through throwaway anonymous accounts | Server caps limit it; Play Integrity is the real fix. |

## Phone tests owed by the owner

| Test | Since |
|---|---|
| Arming schedule on a real phone (arm, pause, +15 min, Skip today, reboot) | v2.2.0 |
| A notification or call buzzing a phone on the table no longer sets it off | v2.3.1 |
| No false alarm locked in the hand, sliding into the pocket, sitting down | v2.4.0 |
| Theft alarm off ("SOS ready" mode) | v2.4.0 |
| Volume lowering during the siren: send a detection log and say if test mode was on | v2.0.1 (unresolved) |
| Whether Play Protect lets the current APK install without pausing scanning | open since v1.5.0 |

Already confirmed by the owner: pocket sirens fire (field log 2026-10-17); SOS from the app works;
4-tap SOS works (v2.2.0).

## Other open items

| Item | Owner | Notes |
|---|---|---|
| Schedule on Android 14+: service started by an alarm lacks camera, mic and location until the app opens | C | A notification tells the user. Check whether more can be done. |
| UI copy: rename "SOS buttons" to "SOS volume key" | C | Optional. |
| Old alerts sent before v2.2.0 link to the dead github.io site | none | Accepted while the repo is private. |
| Delete merged `wip/*` branches on GitHub | T | `wip/backend-check`, `wip/contacts`, `wip/detection-v1.2.0`, `wip/e2e-pocket-race`, `wip/live-location`, `wip/media`, `wip/play-ready`, `wip/sos`. Claude's proxy can't delete remote branches. Also the old `pocket-guard-build-2` prerelease (optional). |
| Brevo housekeeping | T | Optional: remove the unused old Gmail sender; rotate the Brevo API key (part of it appeared in a screenshot). |
| DND on Android 15+ | none | The siren can't override the user's own DND there. Shown in setup. Accepted. |

## Done (closed from earlier lists)

- Website offline while private → moved to Cloudflare Pages (v2.2.0).
- Re-approve contacts after wording changes → owner re-approved after v1.8.0, v1.9.0, v2.0.0, v2.1.0.
- Screen-off volume-up SOS on Samsung → moot; the gesture needs the screen on (v2.2.0).
- Root cause of the v1.2.0 "no buzz" test → superseded by v1.2.1–v1.4.0; later logs show sirens firing.
- Backend accounts and secrets (WP4 item 4.0) → done 2026-10-17.
- WP1–WP5 → released as v1.5.0–v1.9.0.
