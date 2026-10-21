# Detection decisions

How the theft guard decides. Oldest first. The full design and threat model is in
[../plans/detection-plan.md](../plans/detection-plan.md); current behaviour is in the root
[README](../../README.md).

### Guard logic as a pure state machine
- **Date / version:** before 2026-10-16
- **Decision:** `GuardEngine` is pure Kotlin with no Android types, unit-tested on the JVM.
  `GuardService` is its Host. Phases OFF, WAITING_FOR_POCKET, IN_POCKET, ON_TABLE (v1.2.0),
  WARNING, SIREN.
- **Why:** Detection must be testable without a phone; the sandbox has no Android SDK.
- **Status:** current.

### Owner priority: never miss a real theft
- **Date / version:** 2026-10-17
- **Decision:** When false alarms and missed thefts trade off, prefer catching the theft. Cut false
  alarms through arming conditions, not by making an armed guard less sensitive.
- **Why:** Owner: "not working on correct time is more dangerous"; "dont ignore real alarm".
- **Status:** current. Guides every detection decision below.

### Light needs fresh readings; motion as fallback
- **Date / version:** before 2026-10-16
- **Decision:** Light counts only when fresh readings arrive both while covered and after
  uncovering (a covered reading over 50 lux is stale). Otherwise a jolt over 2.5 m/s² in 3 s plus
  1.5 s uncovered decides. A motion-only warning recovers if re-covered within 1 s.
- **Why:** Field bug "motion detector did not work": a stale light reading blocked detection.
- **Status:** superseded by "sensitive once armed" in v1.3.0 (any uncover warns).

### "Protected" buzz only once screen off or locked
- **Date / version:** 2026-10-16
- **Decision:** Don't confirm protection while the phone is unlocked with the screen on.
- **Why:** It said "Protected" but would never alarm in that state.
- **Status:** current.

### Lock check after screen off
- **Date / version:** 2026-10-16
- **Decision:** 1.5 s after SCREEN_OFF, check `isDeviceLocked`. A failure sticks until the user taps
  Fix; the warning notification is posted only when the result changes.
- **Why:** A power press always locks at once, so only a screen timeout shows a lock delay. A
  pocketed phone not yet locked would let a thief pass as the owner.
- **Status:** current.

### No-delay fast path
- **Date / version:** 2026-10-16
- **Decision:** With 0 s grace, confirm a pull-out after 0.25 s (light) or 0.5 s (motion); a
  motion-only siren stops itself if re-covered within 1 s.
- **Why:** Field report: with no delay it still took 1.5–2 s.
- **Status:** current. The README states 0.25 s to confirm and a stop if covered again within 1 s.

### Table mode and gentle-pull detection
- **Date / version:** 2026-10-17, v1.2.0
- **Decision:** Add `MotionProcessor` (50 Hz raw accelerometer, timestamp filters, gated/fast/slow
  gravity, stillness, jolts, free fall, gait) and `TableMonitor` (placement latch, hard-surface
  test, TILT, LIFT, HELD, POSE, FALL, UNCOVER rules, vehicle-suspect state). Pocket rules WATCH,
  TILT_OUT and OUT_AND_IN; put-back recovery ≤ 3 per 10 min; 1.5 s silent start where the owner is
  likely looking. Planned by three planners, merged, attacked by red teams, revised.
- **Why:** Field bug: a face-down phone moved gently on a table did nothing; a gentle pull was missed.
- **Rejected alternatives:** A gated sideways branch (without a gyroscope it took a knee dip for a
  pull). Letting a vehicle exit table mode silently (a thief could fake it).
- **Status:** current, with thresholds changed in v1.3.0 and v1.4.0.

### Detection log on the phone
- **Date / version:** 2026-10-17, v1.2.1
- **Decision:** Settings → Detection log: 400-entry in-memory ring with Copy and Clear;
  accelerometer health logged every 10 s.
- **Why:** The owner's v1.2.0 test fired nothing and the cause couldn't be seen.
- **Status:** current. Most later fixes were built from pasted logs.

### Sensitive once armed
- **Date / version:** 2026-10-17, v1.3.0
- **Decision:** In a pocket, any uncover of 0.8 s while locked warns, with no jolt, light or turn
  needed. First 1.5 s silent; fabric flicker under 1 s ignored. Table: a slide ≥ 5 cm, tilt ≥ 10°,
  lift or pick-up warns then sirens; knocks ignored; table rest 3 s. Only one-way pushes ≥ 1.5 s
  count as a vehicle.
- **Why:** Owner answers: pocket = "Any uncover", table = "Warn on any move".
- **Rejected alternatives:** Requiring evidence (jolt/light/turn) for a pocket warning. A careful
  thief gives none.
- **Status:** current. Supersedes the v1.2.0 pocket evidence rules (P-JOLT, P-TILT_OUT, WATCH).
  Accepted trade-off: every uncover warns; the owner silences it by unlocking within 1.5 s.

### Golden rule: locked and black screen = protected at once
- **Date / version:** 2026-10-17, v1.4.0
- **Decision:** Pocket confirmed 0.5 s after covered and locked; pocketed first and locked after =
  protected the instant the screen goes black. Locked, still, black screen, uncovered → ON_TABLE
  within about 1 s (face-up or on a seat). Face-down = pocket at once, full table after about 4 s.
  Table entry needs the keyguard, not a credential lock; Smart Lock no longer blocks it. Rocking
  test measures swing to swing per axis.
- **Why:** Owner: "once phone is lock. And black sceeen it should activate immediately". He
  disliked the 10 s table wait.
- **Rejected alternatives:** Shortening the table rest test below 3 s (failed the seated-pocket test).
- **Status:** current.

### Review fixes to the engine
- **Date / version:** 2026-10-17, v2.0.0
- **Decision:**
  - Flicker recovery of a jolt only if the phone barely turned (gated-gravity turn ≤ 45°, turn ≤ 15°).
  - A ringing call alone doesn't disarm the table guard; only an answered call counts as the owner.
  - Covering the phone on a table counts as IN_POCKET.
  - Uncovered table entry needs the phone lying flat (≤ 15°).
  - SOS overrides a theft siren.
  - OUT_AND_IN minimum out time 1000 → 200 ms (old branch was dead code). NaN guard on accelerometer.
- **Why:** A thief could yank and cover within 1 s; an accomplice could call the phone; a phone on a
  lap or upright in a pocket became ON_TABLE and false-alarmed.
- **Status:** current.

### Calls at the earpiece are ignored
- **Date / version:** by v1.2.0
- **Decision:** While call audio is at the earpiece, proximity changes don't count.
- **Why:** The ear covers and uncovers the sensor.
- **Status:** current.

### Screen off but not locked means the owner
- **Date / version:** by v2.2.0
- **Decision:** If the screen is off but the phone isn't locked (lock delay, Smart Lock), the guard
  treats it as the owner's phone. The lock check warns about it.
- **Why:** The unlock is the only owner signal; without a lock there is none.
- **Status:** current.

### Notification and call vibration on a table is not a theft
- **Date / version:** 2026-10-19, v2.3.1 (PR #18)
- **Decision:** Smooth motion (200 ms + 100 ms moving means) before integrating slide and lift.
  Detect a motor buzz (jitter RMS ≥ 0.6 over 120 ms, mean near base, 4 samples in a row) and hold
  table rules while it buzzes and 300 ms after. FALL is still checked first.
- **Why:** Owner log: TABLE_SLIDE with nothing touching the phone.
- **Rejected alternatives:** Pausing the guard while a ringtone or notification plays (an
  accomplice could call to blind it). Smoothed tilt (broke the idling-car test; reverted).
- **Status:** current. Tested on 48 buzz cases.

### Fewer false pocket alarms
- **Date / version:** 2026-10-20, v2.4.0 (PR #22)
- **Decision:**
  1. In-hand: covered at the same angle it was just used at (finger over the sensor) waits.
     Protection starts when it turns into a pocket, walking starts, or after 1 minute.
  2. Settle: an uncover within 2 s of going in is still sliding in, no warning.
  3. Re-cover recovery 1 s → 2.5 s, and a smooth sit-down or stand-up turn counts as a pocket
     shift even if light got in.
- **Why:** Owner log: false sirens locked in the hand, sliding into the pocket, sitting down.
- **Rejected alternatives:** None noted. A thief still triggers it by turning the phone while
  pulling it out, moving it to another pocket, or looking at the screen.
- **Status:** current. Known limit: a phone left peeking out of the pocket after sitting still warns.

### Known limits accepted
- **Date / version:** v1.2.0 onward
- **Decision:** Accept and document: a hand kept over the sensor the whole way out isn't seen by the
  pocket rule; no gyroscope, so slow level slides under about 10 cm/s aren't measured; a quiet
  electric car pulling away hard can warn; a still lap can look like a table; some phones stop
  sensors with the screen off.
- **Why:** Sensor and Android limits.
- **Status:** current. Listed in the README "Known limits".
