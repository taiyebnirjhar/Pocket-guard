# Field reports

Bugs and problems the owner reported from real use on his phone. Oldest first. Bugs caught only
in CI are listed at the end.

| # | Date | Report | Evidence | Root cause | Fix | Version |
|---|---|---|---|---|---|---|
| 1 | ≤2026-10-16 | Install blocked: "App blocked", no "Install anyway" | Screenshot | Play Protect blocks sideloads with SMS, location and Accessibility | Single version with those features cut | pre-v1.0 |
| 2 | ≤2026-10-16 | "Motion detector did not work" ("release 8") | Phone test | A stale light reading blocked detection | Fresh-reading light logic plus motion fallback | pre-v1.0 |
| 3 | 2026-10-16 | "Even though the delay is none… it take some time" (1.5–2 s) | Phone test | Normal confirm times applied with 0 grace | Fast path: 0.25 s on light, 0.5 s on motion | build-2 / v1.0.0 |
| 4 | 2026-10-16 | Pocketed unlocked with screen on: "Protected" but never alarmed | Phone test | Protection confirmed before the phone was locked | "Protected" buzz only once screen off or locked | build-20 |
| 5 | 2026-10-16 | "What's autostart, it shows Xiaomi" | Question | Brand-specific setting | Explained; manual steps for Xiaomi, Realme/Oppo, Vivo | — |
| 6 | 2026-10-16 | Fresh install: arming did nothing | Phone test | Required setup was hidden | First-run "Get it ready" | v1.1.0 |
| 7 | 2026-10-16 | "Optional" text cropped | Screenshot, large font | Fixed 16 dp height | Removed the fixed height | v1.1.1 |
| 8 | 2026-10-16 | Face-down phone moved gently on a table: nothing; only sirened when lifted to look | Phone test | No table mode; gentle pulls give no jolt | Table mode and gentle-pull rules | v1.2.0 |
| 9 | 2026-10-17 | v1.2.0 "didnt trigger any siren", no buzz at all | Phone test; no log sent | Unconfirmed (likely not locked, HyperOS freezing sensors, or vibration off) | Detection log (v1.2.1), sensitive once armed (v1.3.0), golden rule (v1.4.0) | v1.2.1–v1.4.0 |
| 10 | 2026-10-17 | "The 10 sec wait and beep might not work"; he presses power and expects protection at once | Owner habit | Table entry waited for a long rest test | Locked + black screen = protected at once | v1.4.0 |
| 11 | 2026-10-17 | v1.5.0 install hidden/blocked ("App blocked to protect your device") | Screenshot | Play Protect enhanced fraud protection blocks sideloaded apps with Accessibility | Workarounds: pause scanning, `adb install`, Play internal test. Not an app bug | open (Play) |
| 12 | 2026-10-17 | SOS volume buttons didn't work | Detection log: "SOS on, but the key watcher is off" | Android 13+ "restricted setting" greyed out the Accessibility switch | "Allow restricted settings" steps; in-app guide; guided setup | v2.0.1, v2.1.0 |
| 13 | 2026-10-17 | Fingerprint on the power button unlocks the phone, so "power then hold" is awkward | Owner | Gesture design | Clarified the hold works unlocked; added volume-up hold 5 s | v2.1.0 (later replaced by 4 taps) |
| 14 | 2026-10-17 | "System was auto lowering the sound" | Owner | Unconfirmed: test mode (30% on purpose) or system safety volume | Volume and test-mode logging added | v2.0.1; **unresolved**, needs a new log |
| 15 | 2026-10-17 | No server or email features seen | Log: "SOS alerts: off (no approved contacts)" | No approved contact; online features run only during SOS with one | Explained how to test | — |
| 16 | 2026-10-17 | "Configuration is bit hecktik" | Owner | Permissions spread over settings | Guided setup, all permissions in the first-run tour | v2.1.0 |
| 17 | 2026-10-17 | (Positive) pocket sirens fired (UNCOVERED, LIGHT); SOS from the app worked | Detection log | — | — | v2.0.x |
| 18 | 2026-10-18 | SOS by volume key takes too long | Owner | 5 s hold too slow in an emergency | Tap volume up 4 times in 2 s | v2.2.0 |
| 19 | 2026-10-18 | Where are photos and videos saved? Need download and "open with" | Owner | Media only in private storage | History open/save/share | v2.2.0 |
| 20 | 2026-10-18 | A harassed woman grabbing her own armed phone gets the theft siren and needs SOS | Owner scenario | No path from theft alarm to SOS | Taps count during our alarm; 🆘 button | v2.2.0 |
| 21 | 2026-10-18 | "Lock immediately" step feels unnecessary | Owner | Required step even when the phone already locks at once | Shown only when a late lock is caught | v2.2.1 |
| 22 | 2026-10-18 | Phone still said "hold volume up 5 s" | Screenshot | Old build installed | Install v2.2.0; owner then confirmed 4-tap SOS works | — |
| 23 | 2026-10-18 | A screen flashed for a split second after unlocking to stop SOS | Owner | Theft alarm screen (with 🆘) briefly shown | Fixed | v2.2.2 |
| 24 | 2026-10-19 | Notifications and vibrations on a table set off the siren | Log: TABLE_SLIDE at 15:50:42 and 16:38:48, nothing touching | Motor vibration integrated into a fake slide | Smoothing plus buzz detector; table rules wait during a buzz | v2.3.1 |
| 25 | 2026-10-20 | False sirens: locked in the hand, sliding into the pocket, sitting down | Log: POCKET_REMOVAL with UNCOVERED / JOLT / LIGHT | Finger over sensor read as a pocket; uncover while sliding in; sit-down turn let light in | In-hand wait, 2 s settle, 2.5 s re-cover recovery with posture shift; 15 tests built from the log | v2.4.0 |

## Problems found in setup, not the app

- Supabase project first created in Sydney by default → recreated in Singapore (2026-10-17).
- The owner merged PRs #10, #13 and #14 before CI finished. Release-time tests on `main` still
  gate publishing, but wait for green.

## Bugs caught in CI before release

- Duplicate `action_open` string resource (v2.1.0 work, commit 3b5863b).
- Crash reading SMS templates without arguments broke the approval check; 33 e2e failures (fix e5c3332).
- e2e pickpocket scenario failed on the slow private runner because the screen auto-locked (e411a2b).
- e2e proximity race at lock: the test now waits 1 s after covering (PR #7, and again in v2.2.0).
- A keyboard Enter pressed the 🆘 button on the scrollable alarm screen → touch only (v2.3.0).
- Intruder photo timeout 8 s → 12 s for slow runners (v2.3.0).
- Private-runner disk space for the emulator (v2.1.0).
- e2e key events: `input keyevent` is invisible to accessibility filters → root `sendevent` (v1.5.0).

## 2026-10-23 · Three phones (v2.4.0)

- **OnePlus 11R:** theft protection works (table uncover, pocket removals with no delay). SOS untested.
- **Tecno Camon 40 Pro 4G:** "not worked at all". The log shows the phone reporting **unlocked in the
  same second as the pocket uncover** (21:28:55), so the guard took it for the owner, and the motion
  sensor slowed to **10 samples/s**. Needs this phone's log with device details.
- **Redmi Note 14 Pro+ 5G:** theft and SOS work, theft "not accurate"; alarms all follow uncovers, so
  which were false needs labels.
- **Setup:** giving every permission takes too long; finding the Accessibility switch is hard on
  some phones; banking apps may refuse to run while an Accessibility service is on.
- **Response:** online detection logs with device details and alarm labels (v2.6.0), to study each
  phone before changing the rules. Setup and Accessibility are next.

