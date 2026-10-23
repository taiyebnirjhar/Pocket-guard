# Releases

Dates are UTC (merge or release day). Releases are at
https://github.com/taiyebnirjhar/Pocket-guard/releases/tag/vX.Y.Z (private repo). Since v1.6.0
each release carries `PocketGuard-vX.Y.Z.apk` and `PocketGuard-vX.Y.Z-play.aab`.

## Before v1.0.0

Built in the owner's `taiyebnirjhar/Random` repo (folder `pocket-guard/`) on 2026-10-16:
v0.1–v0.4 (core alarm, tamper/charger/earbud/transit triggers, evidence, fake shutdown, onboarding,
Bangla), Random PR #1 (0 s delay), prereleases `pocket-guard-build-N` ("release 8" was blocked by
Play Protect and had the motion bug), the single-version cut, build-20 (Cryptonix redesign, lock
check, PIN to disarm) and build-21 (e2e timing fix). Random PR #2 removed the app from that repo.
In the new repo, `pocket-guard-build-2` was the first signed prerelease (no-delay fast path,
screenshots).

## v1.0.0 onward

| Version | Date | PR | What changed |
|---|---|---|---|
| v1.0.0 | 2026-10-16 | direct to main | Own repo `Pocket-guard` (MIT). Versions from the `VERSION` file. Same app as build-2. |
| v1.1.0 | 2026-10-16 | direct | First-run "Get it ready" setup that asks for every permission. |
| v1.1.1 | 2026-10-16 | direct | "Optional" label no longer clipped at large font sizes. |
| v1.2.0 | 2026-10-17 | direct; #2 (merge only) | Table mode and gentle-pull detection: `MotionProcessor`, `TableMonitor`, pocket WATCH / TILT_OUT / OUT_AND_IN, put-back recovery. 123 unit tests, e2e 54/54. |
| v1.2.1 | 2026-10-17 | direct | Detection log in Settings (400 entries, Copy, Clear). |
| v1.3.0 | 2026-10-17 | direct | Sensitive once armed: any uncover or table move warns, 1.5 s silent start. Auto-arm becomes a tap-to-arm reminder. Car false-alarm fixes. |
| v1.4.0 | 2026-10-17 | direct | Golden rule: locked + black screen = protected at once. Face-up table guard. Smart Lock no longer blocks the table. SOS v1 code shipped hidden. |
| v1.5.0 | 2026-10-17 | #3 | SOS: power, then hold + and − for 3 s (Accessibility, with disclosure). Whistle + voice alarm, video with sound, History playback and share. Silent-audio fallback removed. Roadmap added. |
| v1.6.0 | 2026-10-17 | #4 | Play readiness: `full` and `play` builds + AAB. SOS from notification, QS tile and Home. Permission disclosures. Privacy page. Emergency SOS tip. Target SDK 36. |
| v1.7.0 | 2026-10-17 | #5 | Emergency contacts: approved SMS with location after a 5 s countdown, follow-ups, false-alarm SMS, Share on WhatsApp. Backend setup guide. |
| v1.8.0 | 2026-10-17 | #6 | Live location link and alert emails (Supabase + Brevo). Anonymous account. Settings → Online, Delete my online data. First real backend deploy. Secrets check workflow. |
| — | 2026-10-17 | #7 | Test-only: e2e pocket timing race; deploy smoke test waits for settings. No release. |
| v1.9.0 | 2026-10-17 | #8 | SOS video uploaded to R2 in 1-minute 480p pieces, viewable on the live page through 10-minute links. Limits. Daily keep-alive and usage report. |
| v2.0.0 | 2026-10-17 | #9 | Full five-reviewer audit, about 55 fixes: engine, platform (restarts, permissions, storage caps), SOS client retries, backend limits and hardening, UI and Play policy. Backend deploys only after CI. |
| v2.0.1 | 2026-10-17 | #10 | In-app steps for Android 13+ restricted settings. SOS wording fixed (works unlocked too). Alarm volume and test mode logged. |
| v2.1.0 | 2026-10-17 | #11 | SOS by holding volume up 5 s. Guided Accessibility setup. First run asks for everything. SMS in English letters (English or Banglish). CI fits private-repo runners. |
| v2.2.0 | 2026-10-18 | #12 | Arming schedule. SOS = tap volume up 4 times. Theft alarm → SOS (taps or 🆘 button). History open/save/share. Website moved to Cloudflare Pages. |
| v2.2.1 | 2026-10-18 | #13 | "Lock immediately" shown only when a late lock is caught. |
| v2.2.2 | 2026-10-18 | #14 | No theft-screen flash after SOS stops. |
| v2.3.0 | 2026-10-18 | #16 | Second full review: theft alarm could be stopped without the PIN (SOS → "I'm the owner", QS tile, Skip today, other apps); public APK is now the release build; SOS alert state survives restarts; handover unlock doesn't end SOS; camera conflict; schedule fixes; backend email fixes; CI hardening. 156 unit tests. |
| site | 2026-10-18 | #15 | Landing website with a download button serving the latest APK. |
| site | 2026-10-19 | #17 | Real phone screenshots, product video, gender-neutral copy, final headline. |
| v2.3.1 | 2026-10-19 | #18 | A notification or call vibrating a phone on a table no longer sets it off. |
| site | 2026-10-19 | #19 | "Sadia" in the example SMS. |
| site | 2026-10-19 | #20 | Pages always deploys `main`, so a release can't restore an older page. |
| site | 2026-10-19 | #21 | Website and README fact-checked against the code (three passes). |
| v2.4.0 | 2026-10-20 | #22 | Fewer false pocket alarms: finger over the sensor in the hand, 2 s settle after going in, 2.5 s re-cover recovery including the sit-down posture shift. Separate Theft alarm switch ("SOS ready" mode). 174 unit tests. |
| v2.6.0 | (pending merge) | #24 | Tabs Home · Theft · SOS · History · Settings. SOS tab: readiness line, "On SOS, Pocket Guard will" checklist with separate switches for live map, video upload and email; contacts moved there. Home warns when SOS has no contacts. Plan: [../plans/sos-ux-plan.md](../plans/sos-ux-plan.md). **Silent SOS: volume down tapped 4 times.** No siren, voice, flash or alarm screen; plain notification; 30 s video pieces; unlocking doesn't end it (Home: Silent SOS is on, cancel alerts, send by SMS app, I'm safe: stop). Volume up × 4 makes it loud; loud can't be made silent. (Steps 1 and 2 of the plan shipped together; there was no v2.5.0 release.) |

Website PRs don't bump `VERSION`; they deploy through `pages.yml` on merge and shipped in the next
app release's notes (#15, #17 with v2.3.1; #19–#21 with v2.4.0).
