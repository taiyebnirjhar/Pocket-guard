# Product decisions

What the app does and doesn't do, for whom, and how its main flows behave. Oldest first.
Detection details are in [detection.md](detection.md); SOS and alerts in
[sos-and-alerts.md](sos-and-alerts.md).

### Build a native Android anti-pickpocket app
- **Date / version:** before 2026-10-16 (pre-v1.0)
- **Decision:** Build "Pocket Guard", a native Android app that sounds an alarm when the phone is
  taken from a pocket. The idea came from a crowded train.
- **Why:** A real, everyday problem in Bangladesh; Android gives apps the sensors needed.
- **Rejected alternatives:** A time capsule (liked, but dropped), a dead man's switch, a broadcast
  capsule over Brevo. iPhone: iOS gives apps no background sensor access.
- **Status:** current.

### Working model: Claude codes, CI tests, owner tests on the phone
- **Date / version:** before 2026-10-16
- **Decision:** Claude writes all code. GitHub Actions builds, tests and releases. The owner tests
  by hand on his phone and merges.
- **Why:** The owner works from his phone; the sandbox has no Android SDK.
- **Status:** current. See [../memory/working-agreements.md](../memory/working-agreements.md).

### One version with features cut
- **Date / version:** before 2026-10-16 (pre-v1.0)
- **Decision:** Ship one version without SMS, location, power-menu detection through Accessibility,
  the fake power-off screen, and the STEALTH, POWER_MENU and AUTO_LOCK triggers. A secure screen
  lock becomes a setup requirement.
- **Why:** Play Protect hard-blocked the install ("App blocked", no "Install anyway"). Owner: if a
  thief tries to power it off, the alarm has already started.
- **Status:** partly superseded. Accessibility came back for SOS in v1.5.0, SMS and location for
  SOS in v1.7.0, and two builds (full/play) in v1.6.0. Power-off detection and the fake power-off
  screen stay out.

### Unlock delay from 0 to 15 s
- **Date / version:** before 2026-10-16
- **Decision:** Grace slider 0–15 s, default 5 s. 0 means no warning; the siren starts at once.
- **Why:** Owner asked for "no delay at all".
- **Status:** current.

### Disarming an alarm that began on an unlocked phone needs the PIN
- **Date / version:** 2026-10-16
- **Decision:** If the alarm started while the phone was not credential-locked (Smart Lock, lock
  delay), only the owner's credential in the app stops it.
- **Why:** Otherwise a thief can stop it from an unlocked phone.
- **Rejected alternatives:** Asking for the PIN before every disarm.
- **Status:** current (extended in v2.3.0 to the QS tile, Skip today, SOS → "I'm the owner").

### First-run "Get it ready" setup
- **Date / version:** 2026-10-16, v1.1.0
- **Decision:** After the guide, a checklist asks for each needed permission or setting, one big
  button at a time. Required: screen lock, notifications, run in background, alarm over lock screen
  (Android 14+), autostart on brands that need it. Optional: camera, DND. The power button opens
  setup if anything required is missing, then arms when done.
- **Why:** Owner: normal users won't find a hidden checklist.
- **Status:** current; extended in v2.1.0 and v2.2.1 (below).

### Arming on a guess becomes a reminder that asks
- **Date / version:** 2026-10-17, v1.3.0
- **Decision:** Earbud and transit auto-arm only post "Tap to arm Pocket Guard".
- **Why:** Owner policy: reduce false alarms through the arming conditions, but once armed be
  sensitive. Arming on a guess set off alarms nobody expected.
- **Status:** current. The schedule (v2.2.0) is the only thing that arms by itself.

### Things deliberately left out
- **Date / version:** 2026-10-17 (Play policy review)
- **Decision:** No screen-off button SOS, no automatic WhatsApp, no remote tracking started by
  contacts, no background location, no recording during calls, no hidden mode, no remote
  activation.
- **Why:** Stalkerware and Play policy risk; Android limits; cost (WhatsApp Business API is paid).
- **Status:** current.

### No sign-up: one anonymous account per phone
- **Date / version:** 2026-10-17, v1.8.0
- **Decision:** The app silently creates an anonymous Supabase account. No email or password.
- **Why:** Simpler for users, nothing to remember, enough for alerts.
- **Rejected alternatives:** Email + password with Brevo-sent confirmation; phone OTP (paid).
- **Status:** current. Supersedes the "login with credentials" plan.

### Online features can be turned off and deleted
- **Date / version:** 2026-10-17, v1.8.0
- **Decision:** Settings → Online has a live-link switch (off = SMS only) and "Delete my online data".
- **Why:** User control; Play account-deletion rule.
- **Status:** current.

### Production-ready review release
- **Date / version:** 2026-10-17, v2.0.0
- **Decision:** A full line-by-line review by five parallel reviewers (engine, platform, SOS/online,
  backend security, UI/Play policy). About 55 findings, all fixed in PR #9.
- **Why:** Owner asked for a "final production ready" release.
- **Status:** done. A second full review followed in v2.3.0 (PR #16).

### Keep Bangla in the app
- **Date / version:** 2026-10-17, v2.1.0
- **Decision:** App screens, email and live page in English and Bangla. SOS SMS in English letters
  only (see [sos-and-alerts.md](sos-and-alerts.md)).
- **Why:** Many users in Bangladesh prefer Bangla; it costs nothing; a Bangla listing stands out.
- **Status:** current.

### Setup guides the user instead of making them hunt
- **Date / version:** 2026-10-17, v2.1.0
- **Decision:** Guided Accessibility setup with a direct action, a 3-step restricted-settings guide
  with buttons, and the first-run checklist also covers SOS keys, camera and mic for SOS, and who to
  alert (optional, not blocking arming).
- **Why:** Owner: "configuration is bit hecktik… All needed permissions should be asked on the app
  fresh tour".
- **Status:** current.

### Test mode
- **Date / version:** by v2.0.0
- **Decision:** Siren and SOS alarm at 30% volume, SMS prefixed "[TEST]", a TEST email (never a
  real SOS email, no incident), switches itself off after 1 h.
- **Why:** Let people test without scaring contacts; a forgotten test mode must not weaken a real alarm.
- **Status:** current.

### Arming schedule, like Focus mode
- **Date / version:** 2026-10-18, v2.2.0 (PR #12)
- **Decision:** Up to 5 windows with days, From and Until; overnight windows; equal start and end =
  whole day; week starts Saturday. Arms at the start, disarms at the end (unless armed by hand
  before). Turning it off in a window pauses for 15 min. Arm now, +15 min, Skip today (the last two
  need the unlock on the lock screen). Never ends a running alarm. Exact timing needs "Alarms &
  reminders", in the full build only.
- **Why:** Owner request.
- **Rejected alternatives:** "1 h" and "Off until tomorrow" pause options (proposed, not built).
- **Status:** current.

### History: open, save and share media
- **Date / version:** 2026-10-18, v2.2.0
- **Decision:** Tap to open full screen through an "Open with" chooser; "Save to phone" into
  "Pocket Guard" gallery albums; Share. Intruder photos are never uploaded. SOS videos are uploaded
  only for the live link, and the R2 copy is deleted after 90 days.
- **Why:** Owner asked where media is and how to get it out.
- **Status:** current.

### "Lock immediately" step only when needed
- **Date / version:** 2026-10-18, v2.2.1 (PR #13)
- **Decision:** Drop "Lock immediately" from required setup. The app tests the lock delay every
  time the screen turns off while armed and shows the step only if a late lock is caught.
- **Why:** Owner: with a password it is basically on already. Samsung/Xiaomi defaults (5–30 s) still
  get caught by the test.
- **Status:** current. Supersedes the v1.1.0 required step.

### Power-off during the siren can't be blocked
- **Date / version:** 2026-10-19
- **Decision:** Don't claim to block power-off. Rely on the alarm resuming after reboot (before
  first unlock), airplane mode triggering the siren, and phone settings: "Require password to
  power off", Google Theft Detection Lock / Offline Device Lock.
- **Why:** A forced restart is hardware-level; apps can't intercept a long power press.
- **Rejected alternatives:** None final. An optional power-menu blocker (GitHub build only, off by
  default, via Accessibility) was offered.
- **Status:** **awaiting owner's answer** on the optional blocker.

### Theft alarm and SOS as separate switches
- **Date / version:** 2026-10-20, v2.4.0 (PR #22)
- **Decision:** Triggers has a **Theft alarm** switch next to **SOS**. Theft off: arming only listens
  for SOS, and Home and the notification say "SOS ready". Both can't be off.
- **Why:** Owner: "we might not need theft and sos togather".
- **Status:** current.

### Theft and SOS tabs; online steps made visible
- **Date / version:** 2026-10-23, v2.5.0
- **Decision:** Five tabs (Home, Theft, SOS, History, Settings). The SOS tab says whether an SOS
  reaches anyone and lists what it does, with separate switches for the live map link, video upload
  and email (the last two need the live map). Supersedes the single "Live location link" switch under
  Settings → Online (v1.8.0) and the mixed Triggers screen.
- **Why:** Owner: "I didnt saw anything online / online feature… Its not clarified on the ui", and
  "user may turn on / off: video upload online".
- **Status:** current. Next: silent SOS (v2.6.0) and the signal-lost alert (v2.7.0), see
  [../plans/sos-ux-plan.md](../plans/sos-ux-plan.md).

### Silent SOS on volume down × 4
- **Date / version:** 2026-10-23, planned for v2.6.0
- **Decision:** Volume up × 4 = loud SOS, volume down × 4 = silent SOS (no siren, plain screen and
  notification; alerts, live map and recording as usual).
- **Why:** A siren tells an attacker to switch the phone off or smash it; live sharing should be
  stealthy. A gesture each lets the user choose in the moment.
- **Rejected:** a loud/silent setting (one fixed choice for every situation).
- **Status:** planned.

### Signal-lost alert after 60 s
- **Date / version:** 2026-10-23, planned for v2.7.0
- **Decision:** During SOS, if the server hears nothing for 60 s, it emails the contacts the last
  position and the live page shows "Offline since".
- **Why:** Power-off can't be blocked, so being cut off is made an alert itself. Owner wanted it
  shorter than 2 minutes; 60 s is 4 missed 15 s updates, so a lift or tunnel doesn't set it off.
- **Status:** planned.

