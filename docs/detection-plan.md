# Pocket Guard: theft-detection plan

This is the design behind Pocket Guard's detection: its threat model, the rules, the signal
processing, the tests, and what is planned. It was drafted by three independent planners
(thefts first, false alarms first, sensors and Android first), merged, attacked by three red
teams (missed thefts, false alarms, platform pitfalls) and revised against every finding.

## Status

**Shipped in v1.2.0** (plan steps 4-6, simplified where noted):
- Raw accelerometer at 50 Hz with timestamp-based filters (`MotionProcessor`): gated gravity,
  fast and slow gravity, one-second stillness with a learned noise floor, jolts by |a| away from
  1 g, free fall vs hop, walking. (The gated sideways branch was tried and dropped: without a
  gyroscope it took a seated knee dip or weight shift for a pull.)
- Table mode (`TableMonitor`, `Phase.ON_TABLE`): placement latch at the cover edge, settle check,
  8 s hard-surface test (creep and rocking on one-second means), credential lock required.
  T-TILT (angle-scaled gate, no gyro), T-LIFT (along resting gravity, 1 s window), T-HELD,
  a new resting angle (T-POSE, separated from acceleration by |a|), T-FALL vs hop, T-UNCOVER with
  lift-like motion and a stalled-sensor fail-safe, proximity glitch distrust, slides chirp,
  vehicleSuspect (from a long push, or engine vibration at a steady pose; a push whose |a|
  excess has gone is cancelled; lifts must out-rise a slope). Unlike the plan, a vehicle never
  exits table mode: a hand can look like one and a silent exit drops protection. A confirmed
  vehicle (a 2 s push and then vibration, or a 5 s push) keeps the slow-hand rules paused until
  30 s of stillness, then re-anchors the pose. T-HELD at 6 degrees from both the last rest and
  the placement, put-back
  recovery (3 per 10 min; table warnings don't vibrate), calls treated as the owner (ringing, or
  answered in silent mode), 1.5 s no-delay window.
- Pocket: no give-up after the evidence window (WATCH); P-TILT_OUT with a display cue (excluding
  charger and ringing), P-OUT_AND_IN with the posture-event veto, loose-pocket and walking
  contexts; walking makes P-JOLT wait for the steps to stop and ignore the steps' own jolts (a
  pull made while standing still counts, even mid-walk, once the phone is turned 60 degrees into
  a hand within 3 s); a recovered jolt warning still gets the out-and-in decision.
- A 1.5 s silent start for warnings where the owner is likely looking (table, turned to look).
- Tests: `MotionProcessorTest`, `MotionRulesTest` (including sweeps over 5 seeds, 25/50/100 Hz,
  noise and +-0.3 m/s² offsets) and e2e scenarios 7b-7d.

**Changed in v1.3.0** (field feedback: false alarms came from arming on a guess, while missed
thefts are the greater danger): arming is the user's decision, and auto-arm became a reminder
that asks. Once armed it is sensitive. In a pocket any uncover past the 0.8 s flicker filter
warns, with no light, jolt or turn required (the P-JOLT/P-TILT_OUT/WATCH evidence rules are gone;
flicker recovery and the out-and-in decision after it remain). On a table a slide of 5 cm warns,
T-TILT needs 10°, and the hard-surface test takes 3 s. A plain uncover gets the 1.5 s silent start.

**Changed in v1.4.0** (field feedback: the owner presses power and expects protection at once):
locking is the signal. Covered and locked, the pocket is confirmed after 0.5 s, and pressing power
on a phone already covered confirms it within that 0.5 s instead of on the next 2 s retry. Lying
still, uncovered, locked with the screen black (face-up on a table, on a seat), it enters
ON_TABLE straight away with the full T-rules, in any orientation. Table entry needs the keyguard,
not a credential lock (an alarm that began without one already ignores the unlock). The rocking
test now measures swing to swing on each axis, so a test that starts mid-swing no longer halves
the breathing it sees. SOS stays hidden in v1.4.0 while it moves to a two-key hold with recording.

**Added in v1.5.0**: SOS is power pressed, then volume + and − held together for 3 s, seen by
an opt-in accessibility key filter. With the screen off Android gives volume keys to no app:
the remote-volume media session gets none on Android 13+, accessibility filters only see keys
that would reach the screen, and reading volume steps over silent audio was dropped (it plays
hidden audio and resets the volume, and could not time a hold). The SOS alarm records video with sound in one-minute pieces until the unlock.

**Not yet** (later steps): the gyroscope, the edge-based owner check and display-state
DisplayListener ranks, the call-state pause and charger watch, the hand (H) rules in shadow mode,
traces and the detection lab, the watchdog and heartbeat, resampling and rate tiers, and the
`emuctl` e2e daemon. Where v1.2.0 differs from the text below, the code and its tests decide.

"Critique 1", "2" and "3" below refer to the red-team reports: missed thefts, false alarms, and
platform and implementation pitfalls.

---

**What "bulletproof" means here.** No sensor can tell the owner from a thief until someone unlocks the phone. So the plan has three goals:
- **Class B, a hand handles the locked, armed phone:** it must warn, for every way of taking the phone that the sensors can physically see.
- **Class A, no hand on the phone:** it must stay silent.
- **The owner's own handling:** it ends with an unlock, and usually makes no sound.

Section 6 lists honestly what falls outside these goals.

**What the red team changed in the merged plan:**
1. **Owner detection.** "Owner present" is now an *edge*: the phone was seen credential-locked, and then it is seen unlocked. Trust agents and lock delays no longer count as the owner. Table mode needs a credential lock.
2. **Table entry.** Entering ON_TABLE needs a *placement latch* plus a *hard-surface test*: rigid rest for 8 s, no tilt creep, no breathing or body oscillation, and no pocket-insertion movement. The test runs on every way into ON_TABLE. The arm-time exception is gone.
3. **Table rules rebuilt.**
   - T-LIFT ignores rotation.
   - T-SLIDE integrates only short bursts.
   - Without a gyro, T-TILT needs 20°.
   - T-FALL ignores hops.
   - T-UNCOVER needs a lift-like motion.
   - The new T-HELD catches slow lifts.
   - A lifted phone that is put back at rest recovers by itself (provisional for 30 s).
   - **Slides and yaw now chirp; they don't alarm.** Taking a phone off a table always needs a lift, tilt or fall, and each of those has its own rule.
4. **Jolts ignore rotation:** a jolt is \|\|a\| − G0\| ≥ 2.5 m/s², or a gated sideways branch.
5. **Pocket rules decide only after the posture window closes.** Every orientation rule needs a second cue that isn't orientation (display, light, or later a hand), except P-OUT_AND_IN when the owner is standing still and upright.
6. **Display cue.** It comes from `DisplayListener`, not from `SCREEN_ON`. It never counts on its own and has exclusion windows.
7. **Calls.**
   - Telephony state plus VoIP ring detection.
   - Pause cap of 5 min; the pause ends early on trouser-pocket gait.
   - Grace of at least 10 s after the call or ring ends.
   - The call is ended at the cap if the permission is granted.
8. **Charger.** Silent when covered, walking or in a vehicle. Watch only from rigid rest. Plugging in or a vehicle ends the watch.
9. **Platform.**
   - Every sensor uses event timestamps, with watermarks and resampling to 50 Hz.
   - Timers split into phase timers and background timers.
   - Watchdog alarm, and auto-disarm survives a restart.
   - Table warnings play on the alarm stream.
   - History and I/O fixes.
10. **e2e.** A noisy `emuctl` daemon, `reset_state`, three CI jobs, and range checks.

---

## 0. Key decisions

| # | Topic | Decision | Why |
|---|---|---|---|
| 1 | Motion source | Raw `TYPE_ACCELEROMETER`, requested at 50 Hz, resampled to a fixed 50 Hz grid, for the whole time it's armed | Every phone has one. The rules can then be tested at any delivered rate. |
| 2 | Gyroscope | On from the moment a placement latch forms, through ON_TABLE and CHARGER_WATCH. Off in pockets. Fake or virtual gyros are treated as absent. | Gives real tilt on tables. Pocket logic is the same with or without a gyro. Saves the always-on gyro that today's fused `LINEAR_ACCELERATION` runs. |
| 3 | Table model | New `Phase.ON_TABLE` with sub-states `DOWN` / `UP`, `noProx`, `provisional`, `vehicleSuspect`. Pocket sub-states `COVERED` / `EPISODE` / `WATCH` stay internal. | UI, notification and e2e need the phase. Pocket regexes stay valid. |
| 4 | Owner | Edge-based (§4.1). `isLocked()` stays as it is for entering protection and for "unlocked at removal". | Closes the trust-agent and lock-delay hole without regressions. |
| 5 | Table entry | Placement latch plus hard-surface test (§2.3) on every entry path. No arm-time exception. | Stops ON_TABLE inside pockets, bags, laps, beds and sofas. |
| 6 | Slides and yaw | Chirp, never an alarm. 4 or more in 120 s give a 3 s beep burst. | Not a removal by themselves. They were the main false-siren source on busy tables and in vehicles. |
| 7 | Lift-type table warnings | Recover when the phone is put back at rigid rest (§2.7) | Glancing at a face-down phone was the field report's daily false siren. |
| 8 | Jolt | \|\|a\| − G0_pose\| ≥ 2.5 m/s², or the gated sideways branch (§3.4) | A raw accelerometer can't separate rotation from push without a gyro. |
| 9 | Pocket orientation rules | Decide after the posture window. Need a display, light or hand cue. P-OUT_AND_IN may fire alone only in the quiet-upright context. | Orientation plus steadiness can't separate a thigh from a hand. |
| 10 | Hand detection (H) | Shadow mode. Tremor stays shadow on 50 Hz streams indefinitely. | Needs real traces, and motor or engine vibration aliases into the tremor band. |
| 11 | Calls | Ringing-origin pause, cap 5 min, early end on gait, grace ≥ 10 s afterwards, `endCall()` at the cap if granted | Compromise between "no cap" (Critique 2) and "60 s plus end the call" (Critique 3). Trade-off in §6. |
| 12 | Test hooks | No test receiver. Hidden prefs that only `run-as` can write: `ignore_gyro`, `face_up_guard`, `e2e_call_cap_s`. | CI releases the debug APK. Prefs need adb on an unlocked phone. |
| 13 | Rollout | Each step is a PR gated by tests. Table rules and orientation-plus-cue pocket rules go live. H rules, P-LONG, P-FLIP and P-COVERED_HANDLING ship in shadow first. | Lowest risk first. |

---

## 1. Threats and everyday use

### 1a. Thefts (all should warn; the siren comes at warning time plus grace)

| # | Scenario | Signals (screen off, locked) | Rule (live unless marked) | Time to WARNING |
|---|---|---|---|---|
| T1 | Fast pull from a pocket | <ul><li>Translational jolt ≥ 2.5 m/s²</li><li>Uncovered</li><li>Light, if the sensor is awake</li></ul> | P-LIGHT; P-JOLT | <ul><li>0.8 s with light</li><li>1.5–3.0 s with jolt (includes the posture hold)</li></ul> |
| T2 | **Gentle pull in the dark, turned to look** (gap 1) | <ul><li>Uncovered</li><li>45–180° turn</li><li>The display wakes because the thief looks</li></ul> | P-TILT_OUT with the display cue | 2.0–3.0 s |
| T2′ | Same, but the display is never woken | Uncovered and turned; no light, no display | <ul><li>P-OUT_AND_IN once it goes into a pocket or bag at a new angle</li><li>Otherwise WATCH keeps watching</li><li>P-HANDLED / P-LONG (shadow)</li></ul> | At re-cover + ≤ 2 s. **Otherwise not caught until H is live** (§6). |
| T3 | Gentle pull, kept at the same angle, held | Uncovered, hand wobble | <ul><li>Live: only with light, or with display plus a ≥ 45° turn</li><li>Shadow: P-HANDLED, P-LONG</li></ul> | Shadow |
| T4 | Out of the pocket and into another pocket or bag (gap 3) | Uncovered ≥ 1 s, ≥ 60° excursion, covered again | P-OUT_AND_IN. It fires alone if the final angle is ≥ 30° from u_pre in the quiet-upright context; otherwise it needs display or light. | At re-cover + ≤ 2 s |
| T4′ | Same, but covered again at about the same angle | — | Shadow H branch | §6 limit 4 |
| T5 | Hand kept over the sensor throughout (gap 3) | Covered, posture change, flip | P-COVERED_HANDLING / P-FLIP (shadow) | Shadow |
| T6 | **Face-down on a table, slid** (gap 2, field report) | Covered, flat, horizontal burst | T-SLIDE → **chirp**, then the lift, tilt or fall that removes it warns | Chirp ≤ 0.4 s after it stops |
| T7 | Face-down, lifted level | Uncovered at 3–5 cm, rise | T-UNCOVER / T-LIFT; a slow lift → T-HELD | 0.3–1.0 s; slow ≤ 3 s |
| T8 | Face-down, turned over to look (field report) | Real rotation, uncover | T-TILT / T-UNCOVER | 0.2–0.5 s |
| T9 | Face-down, lifted with a palm over the screen | Covered, tilt or rise | T-TILT / T-LIFT / T-HELD | 0.3–3 s |
| T10 | Pushed off the table edge into a lap or bag | Free fall ≥ 100 ms with no kick | T-FALL | 0.1–0.4 s |
| T11 | Turned on the spot | Yaw only | T-YAW → chirp (gyro); any later lift warns | Chirp |
| T12 | Face-up on a table, picked up | Tilt, rise | <ul><li>Face-up guard on, or after a rest recovery: T-rules</li><li>Otherwise WATCH pocket rules if it came out of a pocket</li><li>Never pocketed: **unprotected**</li></ul> | 0.3–3 s, or — |
| T13 | Charger unplugged and phone taken | Unplug plus handling, or unplug then pick-up | CHARGER_UNPLUGGED, or CHARGER_WATCH followed by a T-rule | 2 s debounce, or at the pick-up |
| T14 | Airplane mode from the lock screen | Broadcast | Existing | Siren at once |
| T15 | Accomplice calls; thief pulls the ringing phone and answers | Ringing-origin warning | Call pause ≤ 5 min. It ends early on trouser-pocket gait. Siren at the cap. | Warning at once; siren ≤ 5 min |
| T16–T18 | Bag carried off with the phone inside; snatched while unlocked; powered off | — | — | **Not detected** (§6) |

### 1b. Everyday use, no hand on the phone (class A: must be silent)

| # | Situation | What could look like theft | What keeps it silent |
|---|---|---|---|
| E1 | Walking, trouser pocket | Steps, ±25° swing, fabric uncovering | <ul><li>Gait: P-JOLT needs 3 s uncovered with gait ended ≥ 1 s</li><li>Swing < 45°</li><li>P-LIGHT in gait needs 1.5 s plus a cue</li></ul> |
| E2 | Running, stairs | Free fall every stride, ±35° swing | Free falls aren't used in pockets; swing < 45° |
| E3 | Cycling | Pedalling swing, vibration | Gait/vehicle context; tremor in shadow |
| E4 | Standing in a bus | Sway 5–10°, braking 2–5 m/s² | <ul><li>Vehicle: P-JOLT needs an edge-correlated jolt plus 3 s uncovered</li><li>Orientation rules need a cue</li><li>Gravity gated at 0.3</li></ul> |
| E5 | Seated in a vehicle, gaping pocket | Potholes, vibration | As E4, plus jolt-only flicker recovery |
| E6 | Rickshaw, CNG, motorbike | 3–10 m/s² constant | Severe vehicle: only light and display rules stay on |
| E7 | Sitting or standing with the pocket covered | 70–90° one-way turn | No episode; no latch (covered while upright) |
| E8 | Sitting down with an open pocket | Uncovered, 90° turn, seat jolt | Decided after the posture window; posture veto; TILT_OUT needs display or light |
| E9 | Squatting, open pocket | Out-and-back turn | Same final posture → P-OUT_AND_IN silent; the second posture event → postureCycling |
| E10 | Loose pocket shifting | ≥ 3 cover cycles in 30 s | Loose-pocket context: 3 s uncovered or a cue |
| E11 | Bag swinging, set down, picked up | Pendulum, one-way turns | No latch (covered while not flat or with insertion); pendulum check |
| E12 | Rolling over in bed, phone in a pocket | 150–180° roll | Posture event (no upper cap); lying posture → orientation rules need a cue |
| E13 | Jacket hung up, pocket open | Uncovered, still | WATCH needs a cue; pendulum → WATCH |
| E14 | Table: nudged, bumped, cup put down | < 10 cm, spike < 150 ms | T-BUMP ignored; < 10 cm is silent; ≥ 10 cm is only a chirp |
| E15 | Vibrating on the table (message, ring, alarm, adhan app) | Oscillation, creep | Vibration detector blanks it; slide and yaw thresholds doubled; off the edge → T-FALL (wanted) |
| E16 | Car console at a stop, then driving off | Sustained one-way acceleration | Vehicle context blocks entry for up to 10 min; drive-off → vehicleSuspect → silent exit |
| E17 | Slips out of the pocket onto a seat | Uncovered, small drop | WATCH (no table unless the face-up guard is on or it is noProx face-down) |
| E18 | Ignition cuts USB power; power cut | Unplugged, nobody touching | Vehicle → log only; at rest → silent watch; replug ends it |
| E19 | Earbuds put in their case | Disconnect | Buzz only in IN_POCKET and ON_TABLE |
| E20 | Proximity glitch | Uncovered, no lift-like motion | T-UNCOVER needs a lift-like motion; 2 glitches → proximity distrusted |
| E21 | The app's own tick or vibration | Spike | Blanked for 300 ms; table warnings make no vibration |
| E22 | Face-down on a lap, sofa or bed | Flat, nearly still | Hard-surface test (creep, body, sinking). **Residual risk, field-tested.** |
| E23 | Pocketing while seated (thigh 0–15°) | Covered nearly flat, then still | Insertion test (≥ 4 cm along the long axis) blocks the latch; body test; **residual risk**, but the double tick tells you |
| E24 | Phone flat in a handbag on a table; rummaging | Flat, covered, then tilt or slide | Settle/creep test usually blocks entry; slides only chirp |
| E25 | Jacket with the phone laid flat on a chair or bed | WATCH, then rest | WATCH → ON_TABLE only with the face-up guard on, or noProx face-down, plus the hard-surface test |
| E26 | Typing, eating or leaning on a café table | Not at rest for minutes; 0.1–2° lean | Burst-only integration; leans < 4° never make T-HELD; T-BODY needs a body signature |
| E27 | Power bank in a pocket or bag cuts out | Unplug while covered or walking | No charger warning; logged |
| E28 | Car crank or start-stop | Unplug in a vehicle | Logged; the watch ends on vehicle or replug |
| E29 | Ferry or launch cabin | Slow 2–8° roll | Body or vessel oscillation blocks entry, or T-BODY exits |
| E30 | Salah, exercise, repeated sit/stand | Posture cycles with uncovers | Posture events → postureCycling for 3 min → orientation rules need a cue |
| E31 | Crossing legs, turning around | Fast rotation | Rotation is not a jolt (magnitude rule) |
| E32 | Lane change in a CNG or bus | Fake 17° tilt and back | Vehicle: θ and H use gravity gated at 0.3 |
| E33 | Fist on the table, child jumping on the bed | 5–10 mm hop | T-FALL needs no kick and a landing away from g_ref |
| E34 | Notification wakes the screen in a gaping pocket | Display on | Display cue needs a timing match plus a ≥ 45° turn; exclusions |
| E35 | Engine idling with the phone face-down on the dashboard | Steady vibration | Blocks entry; exits ON_TABLE silently |

### 1c. Someone handles the phone (class B: warns, ends with an unlock)

| # | Situation | Result |
|---|---|---|
| B1 | Owner picks it up from the table with a side fingerprint or face | Unlock edge before the rule confirms, or within the 2 s silent window. **Usually no sound.** |
| B2 | Owner flips it to glance and puts it back without unlocking (field report) | Warning; put-back recovery after 2.5 s rigid rest; beeps held if the display woke. **No siren.** |
| B3 | Under-display fingerprint (face-down, needs a flip) | T-TILT, then the silent window covers the flip plus the touch; else beeps, no siren |
| B4 | Face unlock waiting for a swipe | The edge poll sees `isDeviceLocked` = false within 250 ms (verify on One UI and HyperOS) |
| B5 | **Owner pulls out a ringing phone and answers on the lock screen** | Call pause, no siren for calls ≤ 5 min; a tone every 15 s reminds them to unlock |
| B6 | Owner looks at the caller, declines, puts it back | Ringing-origin: siren no earlier than 10 s after the ring ends, with beeps; unlock |
| B7 | Lock-screen glance or music controls from the pocket | **Warns, by design** (§4.11) |
| B8 | Takes it out and lays it on a desk without unlocking | Warning → rest recovery → ON_TABLE (provisional) |
| B9 | Unplugging your own charger | Handled → warns; unlock |
| B10 | Friend, child or waiter picks it up from the table | Warns; a slide alone only chirps |
| B11 | Phone flat in a hard bag on a table, the bag lifted | May warn if the bag passed the hard-surface test. **Stated limit**; the double tick shows table mode is on. |
| B12 | Owner with Smart Lock or a lock delay | No table mode (needs a credential lock). Pocket alarms are DISARM-only, as today. |

---

## 2. State machine (extension of `GuardEngine`)

### 2.1 Phases, sub-states and transitions

```
OFF ─arm─► WAITING_FOR_POCKET
WAITING ─covered 1.5 s, isLocked(), !callAtEar()──────────────────────────► IN_POCKET{COVERED}  (tick)
WAITING ─face-down, prox far, placement segment + table test (§2.3)───────► ON_TABLE{DOWN,noProx} (double tick)
WAITING ─face-up guard on, face-up, uncovered, table test (10 s), display OFF ≥ 10 s─► ON_TABLE{UP} (double tick)
WAITING ─unplug, not handled, rigid rest 3 s before, uncovered, no gait 5 s, no vehicle─► WAITING + CHARGER_WATCH
IN_POCKET{COVERED} ─placement latch + table test (§2.3)───────────────────► ON_TABLE{DOWN}  (double tick on 1st entry per latch)
IN_POCKET{COVERED} ─uncovered─────────────────────────────────────────────► IN_POCKET{EPISODE}
IN_POCKET{EPISODE} ─P-rule, decided after the posture window──────────────► WARNING(POCKET_REMOVAL, evidence)
IN_POCKET{EPISODE} ─covered again, no rule────────────────────────────────► IN_POCKET{COVERED} (memory 5 s; P-OUT_AND_IN decides ≤ 2 s)
IN_POCKET{EPISODE} ─uncovered 2.5 s, no rule──────────────────────────────► IN_POCKET{WATCH}   (no timeout)
IN_POCKET{WATCH}   ─P-rule─► WARNING;  ─covered ≤ 30° from u_pre, no rule─► IN_POCKET{COVERED}
IN_POCKET{WATCH}   ─(face-up guard & face-up) or (face-down & noProx), table test─► ON_TABLE
ON_TABLE ─lift-type T-rule─────────────────────────────────────────────────► WARNING(TABLE_MOVED, evidence)
ON_TABLE ─slide-type T-rule────────────────────────────────────────────────► chirp, stays ON_TABLE
ON_TABLE ─T-BODY─► IN_POCKET{COVERED|WATCH} silently, latch cleared
ON_TABLE ─drive-off─► ON_TABLE{vehicleSuspect} 10 s ─vehicle confirmed─► IN_POCKET silently (vehicle flag) │ not confirmed ─► ON_TABLE
ON_TABLE ─steady engine vibration 3 s─► IN_POCKET silently (vehicle flag, latch cleared)
WARNING ─grace elapsed (deadline frozen only during a ringing-origin call pause, cap 5 min)─► SIREN
WARNING(lift-type table | pocket-origin) ─rigid rest 2.5 s, flat ≤ 10°, ≤ 3 per 10 min─► ON_TABLE{provisional 30 s}
WARNING(jolt-only pocket) ─covered ≤ 1 s, same posture, no H─► IN_POCKET{COVERED}
WARNING(light-only pocket, gait unbroken) ─covered ≤ 1 s, same posture─► IN_POCKET{COVERED}
ON_TABLE{provisional} ─T-BODY or any lift-type T-rule─► WARNING (new grace)
any armed phase ─owner unlock edge, or USER_PRESENT when it counts─► WAITING_FOR_POCKET  (existing)
```

**Code changes this needs:**
- `Phase.ON_TABLE`, plus `val isProtected get() = this == IN_POCKET || this == ON_TABLE`.
- `Trigger.TABLE_MOVED`.
- `enum Evidence`: LIGHT, JOLT, TILT_OUT, OUT_AND_IN, PROXIMITY_ONLY, LONG, HANDLED, COVERED_HANDLING, FLIP, CARRY_CHANGE, UNCOVER, TILT, LIFT, HELD, FALL, SIGMO, CHARGER_HANDLED, CHARGER_WATCH, MOTION_UNKNOWN.
- New branches in the exhaustive `when` blocks: `Texts.kt` (titleRes, shortRes, hintRes: "Protected · on the table"), `Theme.kt` (ON_TABLE colour Purple), `Components.kt` (ring speed and wobble as IN_POCKET), `Notifications.status`, `GuardService.onTransition` (ON_TABLE handled like IN_POCKET).
- `IncidentRecorder.Outcome.RECOVERED` for a warning that ends in ON_TABLE.

**Fixes to the existing plumbing:**
- `warn()` accepts `phase == WAITING_FOR_POCKET || phase.isProtected`.
- `onEarbudsDisconnected` handles `IN_POCKET, ON_TABLE -> host.noticeBuzz()`.
- `cancelAll()` cancels only phase timers.
- `disarm()` cancels every timer, so `disarmCancelsEverything` still holds.

**Order inside each 100 ms tick:**
1. Update the contexts (vibration, gait, posture, vehicle, body).
2. Exits (T-BODY, drive-off, engine vibration).
3. Recoveries.
4. Lift-type triggers.
5. Slide-type triggers.
6. Shadow rules.

`exitBeatsTriggerOnSameTick` tests the boundary.

**Log format.** Existing lines keep their prefix; fields are appended, so `phase=X ` still matches. Examples:
- `phase=WARNING from=ON_TABLE trigger=TABLE_MOVED evidence=TILT deg=24 gyro=true`
- `phase=ON_TABLE from=WARNING trigger=TABLE_MOVED evidence=TILT recovered=putback n=1`
- `table entry face=DOWN latch=prox restMs=8000 creepDeg=0.04 body=false`
- `table slide d=23cm ms=1180`, `table exit reason=VEHICLE|BODY|ENGINE`, `table proximity glitch n=1`
- `WOULD_WARN rule=HANDLED` (shadow, at most one per rule per 30 s)

### 2.2 Engine interface and plumbing

```kotlin
data class SensorCaps(
    val hasAccel: Boolean, val hasLight: Boolean, val hasProximity: Boolean,
    val hasGyro: Boolean,          // false if absent, fake (§3.6) or pref ignore_gyro
    val accelResolution: Float,    // Sensor.getResolution(); replaced by the measured step (§3.4)
)
class GuardEngine(host: Host, caps: SensorCaps)

// Inputs. Sensor times come from event.timestamp, converted to the elapsedRealtimeNanos clock (§3.2).
fun arm(); fun disarm(); fun resumeAlarm()
fun onProximity(tNs: Long, near: Boolean)
fun onLight(tNs: Long, lux: Float)
fun onAccel(tNs: Long, x: Float, y: Float, z: Float)        // replaces onMotion(strength)
fun onGyro(tNs: Long, x: Float, y: Float, z: Float)
fun onDisplay(tNs: Long, state: DisplayState)               // OFF, DOZE_SUSPEND, DOZE, ON (rank 0..3)
fun onCallState(tMs: Long, state: CallState)                // IDLE, RINGING, OFFHOOK (§4.4)
fun onPowerDisconnected(tMs: Long)                          // immediate; the engine stores t_unplug
fun onChargerUnplugged()                                    // after the existing 2 s debounce
fun onPowerConnected(tMs: Long)
fun onAirplaneModeOn(); fun onEarbudsDisconnected(); fun onOwnerUnlocked() // USER_PRESENT
fun onSignificantMotion(tNs: Long)
fun onOwnVibration(startNs: Long, durationMs: Long)        // every pulse, tick and chirp from AlarmPlayer
fun onVehicleHint(tMs: Long, hint: VehicleHint)            // AR_ENTER, AR_EXIT, CAR_AUDIO_ON, CAR_AUDIO_OFF
fun onTimer(timer: Timer)

interface Host {
    fun now(): Long; fun uptimeMs(): Long
    fun isLocked(): Boolean            // unchanged: isKeyguardLocked || !isInteractive
    fun credentialLocked(): Boolean    // KeyguardManager.isDeviceLocked
    fun interactive(): Boolean         // PowerManager.isInteractive
    fun callAtEar(): Boolean           // was inCall(); mode set extended (§4.4)
    fun config(): Config               // cached in GuardService, refreshed by OnSharedPreferenceChangeListener
    fun schedule(timer: Timer, delayMs: Long); fun cancel(timer: Timer)
    fun onTransition(from: Phase, to: Phase, trigger: Trigger?, evidence: Evidence?, detail: String)
    fun confirmBuzz(); fun noticeBuzz(); fun tableTick(); fun tableChirp(burst: Boolean)
    fun warningOutput(mode: WarnOutput) // HELD (silent), POCKET, TABLE, CALL_REMINDER
    fun sirenStyle(ramp: Boolean)       // called just before a SIREN transition
    fun setGyro(on: Boolean); fun requestSignificantMotion(on: Boolean)
    fun endCall(): Boolean              // TelecomManager.endCall(); false if not permitted or failed
    fun sensorHealth(health: Health, detail: String); fun log(line: String)
}
enum class Timer(val background: Boolean) {
    CONFIRM_POCKET(false), CHECK_REMOVAL(false), SIREN(false), SILENT_WINDOW(false),
    CALL_CAP(false), DECIDE(false),
    HEALTH(true), OWNER_POLL(true), CHARGER_WATCH(true),
}
```

**`Config` gains these fields:**
- `tableAuto` (default true), `faceUpGuard` (false), `slideChirp` (true)
- `pocketHandling` (STANDARD / STRICT)
- `callCapMs`: 300,000; the hidden pref `e2e_call_cap_s` may set 10–300 s

**How the pieces fit:**
- **MotionProcessor:** a pure class owned by the engine (§3.4). It emits one feature frame per 100 ms of *sample* time. Rules that use motion run per frame.
- **Proximity, light and broadcasts:** they schedule phase timers as today. When a timer needs motion up to time t, it waits for the accelerometer watermark (§3.2).
- **HEALTH:** a background timer, every 1 s while armed. It runs time-based checks when samples stop, and detects CPU suspend (Δelapsed − Δuptime > 200 ms → log `cpu suspended ms=N`; that span counts as UNKNOWN).
- **Look-back buffer:** 60 s of resampled 50 Hz samples (also the incident trace), plus 0.5 s block means. They serve the latch, charger and u_pre look-backs.
- **No accelerometer:** behaviour stays exactly as today. Proximity alone triggers, charger unplug warns, and there's no table mode.
- **Accelerometer present but no samples yet:** motion is UNKNOWN (§3.2). The stall health check starts only after the first sample.

### 2.3 Entering ON_TABLE (every path)

**The placement latch** (face-down with working proximity) forms at the far→near edge when all of these hold:

| Condition | Value | Why |
|---|---|---|
| Orientation at the edge | Fast gravity within **20°** of ±Z | A phone being lowered face-down is nearly flat when the table comes within 3–5 cm |
| No pocket insertion | Movement along the phone's long axis (y) ≤ **4 cm** in the 1.5 s after the edge (burst integration, zero-speed corrected) | A pocket is entered along y over 5–15 cm; a table is approached along z |
| Clean settle | Rest begins ≤ **1.5 s** after the edge | Pocketing is followed by a hand pulling out and jostling the thigh |
| Persistence | Kept while covered and within **10°** of the placement pose, **with no time limit** (it survives the screen timing out and unlocks) | Face-down while unlocked, then a 30 s–2 min timeout, must still enter (Critique 3 #3) |
| Cleared by | Uncover, a pose change > 10°, T-BODY, a vehicle or engine exit | Each means the placement is over |

If a placement isn't latched (placed and then slid, set down roughly), there's no double tick. The owner sets it down again. The README and the checklist say "wait for the double tick".

**noProx latch** (face-down, proximity reads far: black matte table or dead sensor):
- A motion segment ending face-down within 10° of flat.
- Long-axis travel ≤ 4 cm in its last 1.5 s.
- T-UNCOVER is off for that session.

**Face-up (only with the face-up guard on):**
- Face-up within 10° of flat, uncovered.
- The table test runs for 10 s instead of 8 s, and the display must be OFF for ≥ 10 s.

**Table test** (all paths, including WATCH → ON_TABLE). Every condition is evaluated over the most recent window:

| Condition | Value | Why |
|---|---|---|
| Flat | Within **10°** of ±Z | Tables are level to 1–3°; a camera bump adds ≤ 2.5° |
| Rigid rest | **8 s** of consecutive rest windows (§3.4) | Long enough to see breathing, creep and sinking |
| No creep | Net angle between the first and last 0.5 s block means ≤ **0.15°** | Foam, mattresses, bags and bodies keep settling; a table drifts < 0.05° |
| No body or vessel oscillation | On 2 Hz block means, detrended: peak-to-peak ≥ max(**0.3°**, 8·σ_block), with ≥ 2 reversals spaced 0.8–10 s → reject | <ul><li>Breathing is 0.1–0.6 Hz; vessels 0.05–0.5 Hz</li><li>Block noise is ≈ 0.012°, so 0.3° is ≥ 3× the noise margin</li></ul> |
| No soft sinking | No damped 2–8 Hz oscillation above 3× the noise floor lasting > 300 ms after the settle | Sinking into foam or fabric |
| State | <ul><li>`credentialLocked()`</li><li>Not ringing, no call (OFFHOOK)</li><li>No vehicle, vessel or steady-vibration context</li><li>Not vibrating</li></ul> | Smart Lock and lock delay mean an unlock proves nothing (Critique 2 #25). The other states fake movement. |
| Setting | `tableAuto` | Off brings back today's behaviour |

**On entry:**
- `tableTick()` (40 ms, 80 ms gap, 40 ms) on the **first entry per latch only**; re-entries are silent.
- Ignore 300 ms, then take `g_ref` again over 0.5 s.
- If the tick moved it by > 1°, cancel and test again (8 s).
- The gyro has been on since the latch formed, so its bias is already estimated (§3.4).

**`g_ref`:**
- The mean of the last still second.
- It follows slow change with τ = 30 s while rigidly still, capped at 5° in total from entry.
- It is taken again after each slide or rock.

**No arm-time exception.** If armed or restarted while already face-down, the phone sits in IN_POCKET{COVERED} until the next placement. This is logged as `table not entered: no placement`.

### 2.4 Table rules (ON_TABLE; the lift-type subset also in CHARGER_WATCH)

**Lift-type** rules → WARNING(TABLE_MOVED), with put-back recovery. **Slide-type** rules → chirp only.

| Rule | Type | Condition | Why this value |
|---|---|---|---|
| **T-UNCOVER** (DOWN, proximity trusted) | Lift | <ul><li>Near→far, far held ≥ **300 ms**</li><li>Within [edge − 1 s, edge + 1 s], a lift-like motion: tilt ≥ **3°** held 200 ms (gyro, or 100 ms means through the 0.3 gate), or vertical ≥ **1 cm** (T-LIFT integrator) within 1 s of leaving rest, where rest ended ≤ 3 s before the edge</li><li>Not vibrating, not a T-BUMP</li><li>Decided when the accelerometer watermark ≥ edge + 1 s; at edge + 1.5 s without data → **MOTION_UNKNOWN → warn** (fail-safe)</li></ul> | <ul><li>A face-down phone can't uncover itself.</li><li>A lift reaches 3–5 cm in 0.25–0.5 s.</li><li>A buzz or bump plus a glitch has no 3° tilt and no 1 cm rise.</li></ul> |
| T-UNCOVER glitch | — | Far ≥ 300 ms with no lift-like motion → log `table proximity glitch`. ≥ 2 in 10 min → `proxTrusted=false` for this latch; T-UNCOVER off. | Virtual or IR glitches repeat. The orientation rules still cover lifts. |
| **T-TILT**, gyro | Lift | <ul><li>Bias-corrected rotation away from `g_ref` ≥ **10°** within 1.5 s</li><li>Accelerometer tilt (fast g) ≥ **5°**</li><li>The accelerometer left rest within ±0.5 s</li></ul> | <ul><li>A lift to look turns the phone 20–180°.</li><li>Braking makes no rotation.</li><li>Leftover bias gives < 1° in 1.5 s.</li></ul> |
| **T-TILT**, no gyro | Lift | <ul><li>Fast-g tilt ≥ **20°** held 300 ms</li><li>For ≥ 200 ms of the next 600 ms, \|\|a\| − G0_pose\| ≤ **0.3 m/s²**</li></ul> | <ul><li>A fake 20° tilt needs a_h ≥ 3.57 m/s², so \|a\| − g = 0.63.</li><li>With a worst-case 0.3 m/s² opposing offset it is still 0.54 > 0.3.</li><li>15° had no margin (Critique 3 #17).</li></ul> |
| **T-LIFT** | Lift | <ul><li>a_v = \|a\| − G0_pose (rotation-free), integrated twice from leaving rest, for **≤ 1.0 s**</li><li>Upward displacement ≥ **3 cm**</li><li>Suspended while vibrating</li></ul> | <ul><li>Error in 1 s: noise ≈ 0.2 cm, plus a pose-dependent offset ≤ 0.026 m/s² ≈ 1.3 cm.</li><li>The old 2 s projection on `g_ref` was swamped by tilt.</li></ul> |
| **T-HELD** (new) | Lift | <ul><li>Left rest and not back at rest within **3 s**</li><li>Real tilt ≥ **4°** from `g_ref` (gyro, or gated block means)</li><li>Not vibrating, no body signature</li></ul> | <ul><li>Catches slow, level lifts that T-LIFT misses: a hand can't hold within 4° for 3 s.</li><li>A forearm lean on a table is ≤ 2°.</li></ul> |
| **T-FALL** | Lift | <ul><li>\|a\| < **3 m/s²** for ≥ **100 ms**</li><li>No upward kick (\|a\| − G0 ≥ +5) in the 60 ms before</li><li>No other free fall in the past 2 s</li><li>Landing **not** both at rest within 700 ms **and** within 3° of `g_ref`</li></ul> | <ul><li>A push off the edge has no kick and lands elsewhere after 200–390 ms.</li><li>A hop from a fist or a dropped bag has a kick and lands back.</li></ul> |
| **T-SIGMO** | Lift | Significant motion fires while there has been no accelerometer sample for > 2 s | Fail-safe while the accelerometer is stalled; just logged when the stream is healthy |
| **T-SLIDE** | Slide | <ul><li>**Burst** start: horizontal \|a_h\| (with gyro, a − gyro-tracked g; without, a − `g_ref`) ≥ **0.2** (gyro) / **0.4** (no gyro) m/s², sustained ≥ 150 ms in one direction (±45°) on 50 ms means</li><li>Bias = mean of the 1 s rest before; end = rest ≥ 300 ms; cap **3 s**</li><li>Zero-speed correction at both ends, and a tilt ramp from `g_ref` to the gravity after</li><li>d ≥ **10 cm** → slide (≥ 20 cm while vibrating or ringing)</li><li>Burst capped at 3 s with rough d ≥ 10 cm → slide; otherwise a "surface vibration" segment: no event, `g_ref` re-taken at the next rest</li></ul> | <ul><li>Integration error grows with T², so short bursts only (Critique 3 #4).</li><li>A lean rises slowly and stays < 0.3 m/s².</li><li>Gyro-less phones miss slides slower than about 10 cm/s (§6).</li></ul> |
| Slide outcome | — | <ul><li>`tableChirp(false)` (100 ms, alarm stream, 40%) plus a log line; `g_ref` re-taken</li><li>The 4th slide within 120 s → `tableChirp(true)` (3 s beep burst)</li><li>No phase change, no History entry</li><li>Setting `slideChirp` off → log only</li></ul> | A slide is never the last step of a theft (Critique 2 #17) |
| **T-YAW** (gyro) | Slide | <ul><li>Bias-corrected rotation about `g_ref` ≥ **20°** in 3 s (40° while vibrating or ringing)</li><li>Inside a segment that isn't at rest, with the accelerometer leaving rest within ±0.5 s</li><li>→ chirp</li></ul> | Turning on the spot isn't a removal; integrating only during motion stops bias building up |
| T-BUMP (ignored) | — | Spike < 150 ms, at rest again within 500 ms, tilt < 2°, slide d < 3 cm | A knock settles within 300 ms |
| T-ROCK (ignored) | — | Tilt ≤ 3°, at rest again within 400 ms → re-take `g_ref` (inside the 5° cap) | A camera bump wobbling |
| **T-BODY** (exit) | — | Body signature (§2.3 oscillation test, sliding 8 s window) → silent exit to IN_POCKET{COVERED} (covered) or {WATCH}, latch cleared. **While provisional → resume WARNING.** | A lap or a body. Typing or vibration alone never exits (Critique 2 #10, Critique 3 #3). |
| **Drive-off** (exit) | — | <ul><li>Horizontal acceleration ≥ **0.2 m/s²** in one direction (±30°) for ≥ **2.0 s** with no reversal (no 100 ms mean opposite and > 0.1)</li><li>No real tilt ≥ 3°, no uncover, no lift</li><li>→ `vehicleSuspect` for 10 s: slides, T-HELD, T-LIFT and T-YAW paused; T-TILT, T-UNCOVER and T-FALL stay armed</li><li>Vehicle confirmed (road or engine vibration ≥ 0.12 m/s², 2–25 Hz, for 3 s, or a second sustained acceleration or braking) → silent exit with the vehicle flag</li><li>Not confirmed → back to ON_TABLE</li></ul> | A hand's movement is accelerate-then-decelerate; a vehicle accelerates one way for seconds. A long drag by a thief then returns to full rules (no silent hole). |
| **Engine vibration** (exit) | — | For 3 s, every 250 ms window has a first-difference RMS of 0.06–2 m/s² that changes < 50% between windows → silent exit, vehicle flag, latch cleared | Engines vibrate steadily; typing and eating come in bursts |
| Vibration (blanking) | — | While the vibration detector (§3.4) is on, plus 300 ms: rest, slide, yaw, T-HELD and T-LIFT decisions are paused; T-TILT, T-UNCOVER and T-FALL keep running | A buzzing phone being lifted must still warn |

**No-delay mode (grace 0):**
- A lift-type table trigger → WARNING for **1.5 s** (owner window: beeps held while the display is up, if the edge check is possible), then SIREN.
- Put-back recovery is allowed from that warning, or from the siren, if rigid rest is reached ≤ 10 s after the trigger.

### 2.5 Pocket inputs: posture, cues and contexts

**Reference posture and angle:**
- `u_pre` = u_slow (τ 2 s) 0.5 s before the uncover.
- θ in EPISODE = angle(u_g, u_pre), where u_g is the gated gravity (§3.4); in a vehicle it is gated at 0.3.
- θ in WATCH = the largest angle(u_g(t), u_g(s)) for s in [t − 5 s, t] (a bounded window, Critique 2 #13).

**Posture window and decision hold:**
- The window is open while the u_g turning rate is ≥ 15°/s. It closes after 1.0 s below 15°/s.
- A P-rule that would fire while the window is open is held (timer DECIDE). It is decided when the window closes, or at most **1.5 s** after its earliest firing time.
- A posture event confirmed during the hold vetoes it, as the rule table says.

**Posture event:**
- A one-way turn (path ≤ 1.5 × net) of ≥ **45°** lasting 0.4–3 s, **no upper cap**.
- Followed by 1.0 s "steady" (u_g rate < 10°/s and no wander), or the start of walking.
- Effect: re-take u_pre, and veto jolt and H cues for 3 s.

**Cues (not orientation):**

| Cue | Definition | Exclusions |
|---|---|---|
| **display** | `DisplayState` rank goes up (OFF < DOZE_SUSPEND < DOZE < ON), to DOZE or higher, at a time within [edge − 0.5 s, edge + 1.5 s] **or** ≤ 1.5 s after the end of a rotation ≥ 20° within 1 s, or an \|l\| burst ≥ 1.5 m/s², during the episode or WATCH | Within 2 s after any of: POWER_CONNECTED/DISCONNECTED, the ring starting, the app's own AlarmActivity or notification, a foreign-vibration onset, the app's own tick, chirp or buzz. It never stands in for an uncover. |
| **light** | P-LIGHT's brighter-outside test. With the display rank ≥ DOZE at the reading: outside ≥ max(3 × inside, inside + 30), and the reading arrives ≥ 300 ms after the display came on. | Stale-covered rule as today |
| **H** (shadow) | <ul><li>Wander: u_g travels ≥ 15° in 2 s with net ≤ 0.6 × path, not periodic (25° in a vehicle, with gravity gated at 0.3)</li><li>Tremor (shadow, kept so): 7–13 Hz RMS ≥ max(0.04, 3 × pre-episode), ≥ 1 s, on the 50 Hz grid, not vibrating, not in a vehicle</li></ul> | Posture event within 3 s; own or foreign vibration |
| **jolt** | Translational (§3.4) | — |

**Contexts:**

| Context | Definition |
|---|---|
| `gait` | §3.4 gait detector; `recentGait` = within the last 10 s |
| `looseP` | ≥ 3 cover/uncover cycles in 30 s |
| `vehicle` | Starts on any of: 2–25 Hz motion ≥ 0.12 m/s² in ≥ 20 of the last 30 s with no gait; AR ENTER IN_VEHICLE; CAR_AUDIO Bluetooth connected. Ends on any of: ≥ 10 steps detected; AR EXIT IN_VEHICLE; 10 min with no vehicle vibration and no sustained acceleration. |
| `severeVehicle` | ≥ 1.0 m/s² RMS over 10 s |
| `rest` | ≥ 10 min with no gait and no H |
| `lying` | u_pre within 35° of horizontal (seated thigh, lying, kneeling) |
| `postureCycling` | ≥ 2 posture events in 60 s, then lasts 3 min |
| `quietUpright` | Not recentGait, looseP, vehicle, rest, lying or postureCycling, and \|u_pre · ŷ\| ≥ 0.7 |

### 2.6 Pocket rules (EPISODE and WATCH)

| Rule | Status | Condition | Vetoes | Why |
|---|---|---|---|---|
| **P-LIGHT** | Live | Today's test after 0.8 s (0.25 s no-delay), with the display adjustment above. In recentGait, looseP or vehicle context it also needs uncovered ≥ **1.5 s** continuously **and** (θ ≥ 30°, or display, or H). | — | Strong when the light sensor works. A gaping pocket in daylight while walking fakes it (Critique 2 #14). |
| **P-JOLT** | Live | Translational jolt within 3 s before the decision, plus uncovered ≥ **1.5 s** (0.5 s no-delay) | <ul><li>recentGait (within 2 s): needs 3 s uncovered and gait ended ≥ 1 s</li><li>Posture event within 3 s: off</li><li>looseP: needs 3 s uncovered</li><li>vehicle: the jolt must fall within [edge − 0.5 s, edge + 0.3 s] **and** uncovered ≥ 3 s</li><li>severeVehicle: off</li></ul> | Today's threshold, now independent of rotation. In a bus the pull causes the uncover, while potholes are mostly unrelated to it. |
| **P-TILT_OUT** | Live | <ul><li>θ ≥ **45°** held 0.5 s, uncovered ≥ **1.2 s** in total</li><li>**And** a display or light cue (H when it goes live)</li><li>Decided after the posture window</li></ul> | <ul><li>Posture event vetoes an H cue only; display and light override it</li><li>severeVehicle: off</li></ul> | "Turned to look" means the display is on. Sitting down, rolling over and bending never turn it on (Critique 2 #6, #9). |
| **P-OUT_AND_IN** | Live (orientation branch); hand branch shadow | <ul><li>Covered again after ≥ **1.0 s** uncovered</li><li>Largest θ ≥ **60°** held 300 ms</li><li>Final posture ≥ **30°** from u_pre (or H ≥ 1 s while out: shadow)</li><li>Outside `quietUpright`, or from WATCH: also needs display, light or H</li><li>Decided at re-cover + 2 s</li></ul> | <ul><li>Posture event or postureCycling</li><li>A pendulum after the cover (decaying 0.5–1.2 Hz, 3–20°) → WATCH, no warning</li><li>Covering again never cancels it</li></ul> | Pickpocketing a person standing still. Loose pockets while walking, jackets coming off and salah need a cue (Critique 2 #21, #9). |
| **P-LONG** | **Shadow** | Uncovered ≥ 3.0 s continuously, plus H ≥ 1 s from the moment the posture settles to 2 s after | Same vetoes as P-TILT_OUT; recentGait; lit but not brighter | Its old handling measure fired on every 90° turn (Critique 2 #7) |
| **P-HANDLED** | Shadow | H ≥ 1.5 s of the last 3 s, plus uncovered ≥ 1.0 s (no screen-on substitute) | Lit but not brighter; posture event; severeVehicle; vibration; rest context needs 2 s uncovered | Needs two independent channels |
| **P-COVERED_HANDLING** | Shadow | Covered throughout, H ≥ 1.5 s, posture change ≥ 40° held 1 s | Posture event, gait, vehicle | Gap 3 with the sensor covered |
| **P-FLIP** | Shadow | Before and after both upright (\|u_y\| ≥ 0.85), ≥ 150° apart, new posture held 3 s | — | Can't turn end over end inside a pocket |
| **P-CARRY_CHANGE** | Shadow | While walking: covered steadily ≥ 30 s, then uncovered ≥ 4 s while walking continues | looseP | A change of carry state |

**WATCH:**
- No timeout while the sensor stays uncovered.
- `u_pre` is re-taken whenever u_slow has stayed within ±10° for 10 s, walking or not.
- Covered again within 30° of u_pre with no rule → COVERED, silently.
- Motion UNKNOWN never warns in a pocket. Rules are decided once late samples arrive in timestamp order.

**`pocketHandling = STRICT`** turns the shadow rules on, and turns H on as a cue.

### 2.7 Recoveries

| Warning origin | Recovery | Conditions | Limits |
|---|---|---|---|
| Pocket, jolt-only | Flicker → IN_POCKET{COVERED} | Covered ≤ 1 s after the warning; θ(u, u_pre) < 25°; largest θ < 45°; no H | Also for no-delay jolt-only sirens (existing) |
| Pocket, light-only | Flicker | Same, **and** gait ran unbroken from before the uncover until the cover | Otherwise a light-confirmed warning never recovers (existing tests) |
| Pocket, any | Rest → ON_TABLE{face as found, provisional} | Rigid rest **2.5 s** (rest windows, block-mean creep ≤ 0.1°, no sinking); flat ≤ 10°. Face-down with trusted proximity must read near. Face-up allowed. | ≤ 3 recoveries per 10 min, shared with put-back; the 4th never recovers |
| Table lift-type (UNCOVER, TILT, LIFT, HELD, FALL, SIGMO) | Put-back → ON_TABLE{provisional} | Same as above; new `g_ref` | Same; no-delay as in §2.4 |
| CHARGER_UNPLUGGED, EARBUDS, AIRPLANE, RESTARTED | None | — | — |

**Provisional (30 s after any recovery):**
- T-BODY → resume WARNING with a new grace.
- Any lift-type rule → WARNING with a new grace.
- Each recovery is logged and kept in History as `recovered`.

The thief gains nothing: any later handling warns again, and repeated cycles stop after 3.

---

## 3. Sensors and signal processing

### 3.1 Registration (`GuardService.startMonitoring`)

| Sensor | When | Period / latency | Notes |
|---|---|---|---|
| `TYPE_ACCELEROMETER`, non-wake-up | Whole time armed | 20,000 µs; `maxReportLatencyUs` 100,000 | Copy `event.values` inside `onSensorChanged` |
| `TYPE_GYROSCOPE` | From the latch forming → ON_TABLE / CHARGER_WATCH; off otherwise or with `ignore_gyro` | 20,000 µs; latency 100,000 | Discard the first 100 ms after it turns on; bias from rest (§3.4) |
| `TYPE_PROXIMITY` (wake-up first) | Always | NORMAL, latency 0 | No event within 1 s of registering → `flush()`; near = unknown (no latch) until a reading |
| `TYPE_LIGHT` | Always | NORMAL | Extra evidence only |
| `TYPE_SIGNIFICANT_MOTION` | ON_TABLE | — | `requestTriggerSensor` after each firing; `cancelTriggerSensor` on exit and disarm |
| Dropped: `TYPE_LINEAR_ACCELERATION` | — | — | Keeps the gyro on; no gravity vector; the emulator fuses it from a zero gyro |

**Receivers:**
- Add `ACTION_SCREEN_ON` (logging only), `ACTION_SHUTDOWN`, `ACL_CONNECTED`.
- `DisplayManager.DisplayListener` on `DEFAULT_DISPLAY` → `onDisplay`.
- `TelephonyCallback.CallStateListener` (API 31+) or `PhoneStateListener` (API 26–30) when `READ_PHONE_STATE` is granted.
- `AudioManager.registerAudioPlaybackCallback` (§4.4).
- Activity transitions: ENTER **and** EXIT for IN_VEHICLE, while table protection is on and the permission is granted.

**Re-registration:** only the stalled sensor (`unregisterListener(this, sensor)`), with backoff 2, 4, 8, 30 s. Only proximity changes the user makes during Sensor check prove proximity works with the screen off; `onFlushCompleted` only proves the HAL path.

### 3.2 Time base, delivery and threads

- **Timestamps.** Every sensor passes `event.timestamp`. Broadcasts and listeners use `elapsedRealtimeNanos()` at receipt.
- **Clock check.** Per sensor, offset = arrival − timestamp, with the minimum tracked over 5 s.
  - Minimum in [−5 ms, 1 s] → same clock (latency or batching only).
  - A stable minimum (std < 20 ms over 30 s) outside that range → another clock: correct by the offset and log `sensor clock offset=`.
  - Arrival time is never substituted for a burst.
- **Watermarks.** Each stream keeps the newest timestamp delivered.
  - A rule combining streams at time t decides once the accelerometer watermark ≥ t + its window, or at t + 1.5 s with motion **UNKNOWN**.
  - UNKNOWN fails safe: lift-type table rules and charger handling → warn; pocket rules → wait in WATCH.
- **Ordering.** Samples are processed in timestamp order, resampled to the 50 Hz grid (§3.3).
- **Logging.** The p95 of (arrival − timestamp) per sensor is logged at every phase change.
- **Thread.** The engine stays on the main thread.
  - `Config` is cached; `Prefs` caches its `SharedPreferences`.
  - History, traces and other I/O run on one background executor; the ring buffer is copied off the main thread.
  - If p95 latency from callback to engine on the shipped APK is > 150 ms, move the sensor listener and engine to one `HandlerThread` (and the Host calls with it).

### 3.3 Delivered rate and degradation

| Measured rate (after resampling) | Behaviour |
|---|---|
| Any input rate | Above 75 Hz: block-average down to 50 Hz. 40–75 Hz: linear interpolation. Drop 2 samples after a rate change. Reset filters only on a gap > 500 ms. |
| ≥ 40 Hz | Everything on (tremor still shadow) |
| 25–40 Hz | Tremor off; T-FALL needs ≥ 120 ms |
| 15–25 Hz | Tremor and T-FALL off; tilt, rest and burst rules kept |
| < 15 Hz, or p95 gap > 500 ms | `sensorHealth(DEGRADED)` plus a notification |
| No sample for > 2 s | Re-register the accelerometer |
| No sample for > 5 s (only after samples had arrived) | Log `sensor stall`; DEGRADED; notice buzz **once per armed session**, then notification only; T-SIGMO armed |
| Samples return | Log `sensor resumed`; OK |

### 3.4 `MotionProcessor` (pure Kotlin, no allocation per sample)

**Sign convention:** face-up (0, 0, +9.81), face-down (0, 0, −9.81), upright (0, +9.81, 0).

**Calibration:**

| Quantity | Definition |
|---|---|
| G0_global | \|a\| averaged over 10 s at rest, limited to 9.0–10.6 (starts at 9.81) |
| **G0_pose** | Mean \|a\| of the last still second. Used if the pose is within 10° of it; otherwise G0_global. Per-axis offsets make \|a\| depend on the pose. |
| Quantisation q | Smallest step in the histogram of \|Δa\| at rest (replaces `getResolution()`) |
| Noise floor | Median vector σ of the quietest 10% of 1 s windows this session (starts at 0.01) |

**Gravity estimates:**

| Estimate | Definition |
|---|---|
| g (gated) | Low-pass with τ 0.4 s, updated only when \|\|a\| − G0_pose\| ≤ 1.5 m/s², otherwise held. **During gait:** a 2 s boxcar mean of the raw accelerometer. **In a vehicle** (for θ and H): gate 0.3. |
| g with gyro | Rotate by −(ω − bias)·dt, then blend in the gated accelerometer with τ 1 s. Used for table tilt, slide and yaw. |
| g_fast | τ 0.15 s; only used through the 0.3 gate |
| u_slow | τ 2 s (posture) |
| Block means | Non-overlapping 0.5 s means of a (2 Hz): creep, body, held tilt |

**Jolt (translational):**
- Main: \|\|a\| − G0_pose\| ≥ **2.5 m/s²**. Rotation leaves \|a\| unchanged.
- Sideways branch, all of:
  - \|a − g_frozen\| ≥ 2.5, where g_frozen is the gated g from 200 ms before the burst;
  - in the same 100 ms, \|\|a\| − G0_pose\| ≥ 0.3;
  - the gated gravity direction is back within 10° of g_frozen within 1 s after the burst;
  - no gait for 5 s.

**Rest and rigid rest:**
- **Rest** (1 s window), all of:
  - vector σ ≤ REST_SIGMA = min(0.10, max(0.04, 1.5·q, 2·noise floor));
  - largest deviation ≤ max(0.15, 4·q);
  - with a gyro, \|ω − bias\| ≤ 0.05 rad/s.
- **Rigid rest N s:** N consecutive rest windows, block-mean creep within the limit, no sinking, and for N ≥ 8 no body signature.

**Displacement:**
- **Burst integration:** as T-SLIDE; also used for the latch insertion test, along y.
- **Vertical:** a_v = \|a\| − G0_pose, double-integrated ≤ 1.0 s from leaving rest.

**Gyro bias and trust:**
- Bias = mean ω over each rigid-rest second (τ 30 s); the first 500 ms after turning on is ignored.
- The gyro is **treated as absent** if its name or vendor matches `(?i)virtual|pseudo|fusion|soft`, or if its rest σ < 0.0002 rad/s with repeated identical values.

**Activity and vibration detectors:**
- **Gait:**
  - Either ≥ 4 magnitude impacts ≥ 1.5 m/s² at 1.3–3.5 Hz with interval variation < 25%,
  - or a regular orientation swing of 0.5–6 Hz, ≥ 8°, for ≥ 4 cycles.
  - It ends 1.5 s after the last periodic event.
  - Trouser-pocket gait = gait with an orientation swing ≥ 15° peak-to-peak.
- **Vibration (own or foreign):** first-difference RMS of \|a\| over 100 ms ≥ 0.2 m/s², a 200 ms block mean that changes < 0.05, and a gravity change < 0.5°. Active plus 300 ms.
- **Own vibration:** reported by `AlarmPlayer` for each pulse, tick and chirp; blanked from the pulse start until 300 ms after it.

### 3.5 Battery

| Item | Today | After |
|---|---|---|
| Partial wake lock (dominant) | 5–40 mA | Unchanged |
| Motion | `LINEAR_ACCELERATION` at about 16 Hz; on gyro phones it runs the gyro (0.4–5 mA) | Raw accelerometer at 50 Hz: 0.1–0.5 mA. With 100 ms batching, callbacks drop about 5×. |
| Gyro | Always on inside the fused sensor | Latch, ON_TABLE and CHARGER_WATCH only |
| Processing | Negligible | ≤ 0.25% of one core (measure on the shipped debuggable APK) |
| Watchdog alarm, heartbeat | — | Every 15 min / 60 s, negligible |

**Target:** within ±0.1 %/h of the current build, and ≤ 1 %/h armed on a 4,500 mAh phone. Where the wake lock alone exceeds that, report it as a platform cost.

**How to measure:**
- Unplugged (wireless adb, or no adb), ≥ 3 h per build per context.
- Read `BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER` (µAh) at the start and end, A/B on the same phone at the same temperature.
- Where it reads 0 or `Integer.MIN_VALUE`, use battery % over 8 h.
- Check the real rates with `dumpsys sensorservice`.
- Do this on 3 phones.

### 3.6 Android and OEM behaviour with the screen off

**What keeps the sensors running:**
- A foreground service plus a partial wake lock keeps continuous sensors at the requested rate.
- Doze honours that wake lock.
- `specialUse` has no timeout on Android 15.
- `postDelayed` counts uptime, so HEALTH watches for suspend (§2.2).

**Sensor quirks:**
- **Virtual proximity** (ultrasonic or under-display): may sleep or glitch when the display wakes. Handled by the glitch counter and the Sensor check.
- **Black surfaces:** read "far". Handled by the noProx latch.
- **Light:** often stale with the screen off. An under-display light sensor reads the screen's own light → the stricter on-display rule.
- **Accelerometer:** some MediaTek and Unisoc phones throttle it with the screen off, and old phones stop it. Handled by the rate tiers and re-registration.
- **Aliasing:** motors (150–240 Hz) and engines alias into the band → tremor stays shadow; the vibration detector covers the rest.
- **Gyro:** missing on Redmi A, Galaxy A0x/A1x, Realme C, Tecno and Infinix. Fake fused gyros are detected.

**OEM battery managers:** the existing setup steps stay (Xiaomi, Samsung, ColorOS, Vivo, Tecno/Infinix). Long still periods with the screen off are exactly when these managers act.

**Safeguards:**
- **Heartbeat:** `lastAlive` (wall clock plus `Settings.Global.BOOT_COUNT`) written every 60 s to device-protected prefs.
- **Watchdog alarm:** while armed, `AlarmManager.setAndAllowWhileIdle` every 15 min. The receiver checks `Prefs.armed` with a heartbeat older than 3 min and no running service, then tries `GuardService.arm(auto = Prefs.autoArmed)`. Battery-exempt apps may start a foreground service from the background; verify on 14 and 15. If refused, it posts "Pocket Guard was stopped by the system — tap to re-arm".
- **Restart:** log `service restarted gap=Ns`. Skip the restart notice if `ACTION_SHUTDOWN` was received in the same boot count.
- **Background restriction:** `isBackgroundRestricted()` = true is a **blocking** setup failure. Arming shows a dialog with Fix, because Android 12+ stops the service.
- **Screen-off Sensor check:** stores `accelHzScreenOff`, `proximityScreenOffOk` (changes the user made with the screen off) and `lightScreenOffFresh`. If proximity is dead with the screen off: "pocket protection unavailable"; noProx table mode and the tamper triggers still work.
- **Stuck audio mode:** `MODE_IN_COMMUNICATION` for > 20 min with no telephony call → notification "a call app is holding the audio; pocket protection is paused".
- **Also recommend:** Android 15 Theft Detection Lock, and "password to power off".

**Emulator facts:**
- `sensor set` overrides one sensor; the gyro reads exactly 0 unless set.
- `rotate` or a posture change clears the overrides.
- The latest value is re-sent at the guest's rate with no noise.
- There is no significant-motion or step sensor.

---

## 4. How the new rules interact with existing ones

### 4.1 Owner (Critique 3 #1, Critique 2 #25)

- **`credentialLockedSeen`.** Set when `credentialLocked()` is true at protection entry, at a screen-off lock check, or at any OWNER_POLL. It is cleared on WAITING.
- **Owner edge.** OWNER_POLL (background, every **250 ms**) runs while any alarm, episode, pending decision or call pause is active. The owner is present if `credentialLockedSeen`, and the previous poll saw it locked, and now `interactive() && !credentialLocked()`.
  - Before a rule confirms → WAITING with no alarm.
  - During an alarm → stop it, the same as `onOwnerUnlocked`.
- **`USER_PRESENT`.** Unchanged, still gated by `unlockStopsAlarm`.
- **Never credential-locked** (Smart Lock, lock delay): no pre-confirm shortcut, no silent window, DISARM-only alarms (today's `lockWatch` path), and no ON_TABLE.
- **Unchanged:** `isLocked()` for pocket entry and "unlocked at removal = owner".
- Verify on One UI and HyperOS that `isDeviceLocked` turns false after face authentication, before the swipe.

### 4.2 Silent owner window (Critique 2 #1b, Critique 3 #9)

- **When:** `credentialLockedSeen` is true, the mode isn't no-delay (except the table owner window), and the display rank rises to DOZE or higher in [trigger − 1 s, trigger + 1.5 s], or the display is ON at the trigger.
- **What:** `warningOutput(HELD)` until trigger + min(**2 s**, grace/2), polling the owner.
- **The siren deadline never moves.**
- **The History entry** is created only when beeps actually start (§4.9).

### 4.3 Grace and no-delay

- **Deadline** = trigger + grace (default 5 s, 0–15 s), never restarted. `secondTriggerDuringWarningDoesNotRestartGrace` still holds.
- **The only exceptions:** the ringing-origin call pause, and the new warning after a provisional recovery.
- **No-delay pocket triggers:** unchanged, straight to SIREN.
- **No-delay table lift triggers:** the 1.5 s owner window.
- **No-delay slides:** chirp.

### 4.4 Calls (Critique 2 #4, #5; Critique 3 #8)

- **Call state:**
  - `RINGING` = telephony RINGING, or audio mode RINGTONE or CALL_SCREENING (API 30), or an active playback with `USAGE_NOTIFICATION_RINGTONE` (from `getActivePlaybackConfigurations()`; attributes are visible to all apps).
  - `OFFHOOK` = telephony OFFHOOK, or mode IN_CALL, IN_COMMUNICATION, CALL_REDIRECT or COMMUNICATION_REDIRECT (API 33).
- **`callAtEar()`** uses the extended mode set. The existing rule stays: while at the ear, no pocket confirm, and a removal counts as the owner.
- **Ringing-origin warning:** the trigger came while RINGING, or ≤ 3 s after the ring ended.
  - **While ringing:** normal output; the deadline is frozen.
  - **When answered (OFFHOOK):** beeps stop; `CALL_REMINDER` (a short tone on `STREAM_VOICE_CALL` every 15 s); **no vibration while `callAtEar`**; the deadline stays frozen.
  - **The pause ends early** after ≥ 10 s of trouser-pocket gait: siren 5 s later.
  - **Ring or call ends without an unlock:** deadline = now + max(remaining grace, **10 s**); beeps plus the alarm screen.
  - **Cap: 5 min** in total. If a call is still active, `endCall()` (ANSWER_PHONE_CALLS, API 28+; verify on 14 and 15), then siren 1 s later. If it's not permitted, siren anyway and log `in_call_siren route=` (`getDevicesForAttributes`, API 33).
  - **A siren after any pause** starts with 2 s of vibration only, then the alarm stream ramps from 30% to 100% over 3 s. `AlarmPlayer` updates `targetAlarmVolume`, so `volumeLock` doesn't fight it.
- **Outgoing and emergency calls** never pause, because only ringing-origin warnings pause.
- **The slide and yaw doubling** follows the vibration detector and RINGING (§2.4), not `MODE_RINGTONE` alone.

### 4.5 Charger (Critique 2 #11)

`onPowerDisconnected(t_unplug)` arrives at once; `onChargerUnplugged()` after the 2 s debounce. Locked only, as today. Then:

| State at t_unplug | Result |
|---|---|
| No accelerometer | Warn (as today) |
| Motion UNKNOWN for [t_unplug − 2 s, t_unplug + 2 s] | Warn (fail-safe; existing tests unchanged) |
| Covered (pocket or bag) | Log `charger unplugged in pocket`; no warning; pocket rules protect it |
| recentGait (5 s) or vehicle | Log; no warning |
| Handled: tilt ≥ 5° (gyro or gated block means), a proximity change, or a translational jolt ≥ 1.5 m/s² within ±2 s | WARNING `CHARGER_UNPLUGGED` evidence=CHARGER_HANDLED` |
| ON_TABLE, not handled | Log only; the T-rules are already armed |
| WAITING or WATCH, not handled, rigid rest ≥ 3 s before t_unplug (any orientation), uncovered | **CHARGER_WATCH** for 10 min. Reference = the pose at the unplug. T-TILT, T-LIFT, T-HELD and T-FALL → WARNING `CHARGER_UNPLUGGED evidence=CHARGER_WATCH`. |
| Not handled and not at rest | Log only |

**CHARGER_WATCH ends silently** on any of: POWER_CONNECTED, a vehicle context or drive-off, entering IN_POCKET, an unlock, or after 10 min. Its timer is a background timer. Lifting the phone off a wireless pad still isn't a charger event; in ON_TABLE it is a T-LIFT.

### 4.6 Warning output and audibility (Critique 3 #16)

| Mode | Sound | Vibration | Used for |
|---|---|---|---|
| POCKET | Beeps on `USAGE_MEDIA`, raised to 60% with a headset (as today) | Pulses, 200 ms on / 800 ms off | Pocket, charger and earbud warnings |
| TABLE | Beeps on `USAGE_ALARM` at 40% of the alarm stream; `overrideDoNotDisturb()` when allowed | None | Table warnings (they get through priority DND and Pixel "Flip to Shhh") |
| HELD | None | None | Silent window |
| CALL_REMINDER | Tone on the voice-call stream every 15 s | None at the ear | Call pause |

`warning audible=false` is logged when the stream's volume is 0 or DND blocks it. Chirps use the TABLE path at 100 ms (3 s for a burst).

### 4.7 Airplane, earbuds, lock check

- **Airplane mode:** siren at once in every armed phase, ON_TABLE included (unchanged).
- **Earbuds:**
  - IN_POCKET / ON_TABLE: buzz only.
  - WAITING and locked: warn.
  - Car kits: only `AUDIO_VIDEO_CAR_AUDIO` counts as a vehicle hint. `BluetoothReceiver`'s HANDSFREE-as-earbuds behaviour is unchanged and noted in §6.
- **Lock check:** unchanged. A failed check also keeps ON_TABLE from being entered (credential lock needed).

### 4.8 Restarts and auto-disarm (Critique 3 #15)

- **`onStartCommand` with a null intent:** `auto = Prefs.autoArmed(this)`, not false.
- **`auto_disarm_at`** (wall clock) is saved when an auto-armed session starts. On a restart, schedule the remaining time, or disarm if it has passed.
- **ON_TABLE isn't saved across restarts;** the guard resumes in IN_POCKET{COVERED} or WAITING.
- **Auto-arm, the 4 h auto-disarm, test mode and alarm resume:** otherwise unchanged.

### 4.9 History and traces (Critique 3 #21)

- **When an entry is created:** when output first becomes audible (after any silent window). An owner unlock during the silent window logs `warning held, owner` and makes no entry.
- **Recovered entries** are tagged `recovered` and kept 7 days.
- **Retention:** escalated entries are capped separately, up to 100 and 90 days. Others: 50 entries, 7 days.
- **Traces:** a 60 s 50 Hz buffer of accelerometer, gyro, proximity, light, display and call events, saved with each audible warning. They live in **device-protected** storage, capped at 20 files / 10 MB, written by the I/O executor. Export is manual (Detection lab).

### 4.10 Own vibration

- `AlarmPlayer` calls `onOwnVibration` for every pattern segment.
- Pocket warning vibration changes to 200 ms on / 800 ms off (from 300/150).
- Table warnings make no vibration.

### 4.11 Using the lock screen from the pocket (Critique 2 #22)

No sensor-based recovery is added: it would reopen the pocket-to-pocket gap. Instead:
- On the first earbud auto-arm, the app shows once: "use your earbuds' controls or unlock; taking the phone out warns".
- History groups repeated pull-and-put-back warnings, with this tip.
- The checklist suggests keeping keys in another pocket.

---

## 5. Test plan

### 5.1 `MotionProcessorTest` (JVM)

**`SignalGen` provides:**
- Seeded noise (σ per axis) and quantisation q.
- Per-axis offsets of ±0.3 m/s² and scale errors of ±2%, randomised per seed. **Every test runs with 5 seeds of offsets and scale.**
- Rate (25, 50, 62.5, 100, 200 Hz), timestamp jitter, latency and bursts.

| Test | Input | Expected |
|---|---|---|
| `gravityConvergesWithin1200ms` | Step from upright to face-up, noise 0.01 | Angle error < 3° at 1.2 s |
| `joltDoesNotMoveGravity` | 6:6:18 for 400 ms | g moves < 1°; jolt true |
| `alongGravityJolt` / `sidewaysPushIsJolt` | 3 m/s² along g / 2.6 m/s² horizontal for 200 ms, then back | Jolt true / true (sideways branch) |
| `fastRotationIsNotJolt` | 90° in 0.6 s at constant \|a\| | Jolt false |
| `walkingSwing25degIsNotJolt` | Gait generator, ±25° at 1 Hz, impacts 1.2 m/s² | Jolt false |
| `sitDown90degIsNotJolt` | 90° in 0.8 s, no seat impact | Jolt false |
| `shuffle20degIrregularIsNotJolt` | ±20° at 0.9 Hz, irregular | Jolt false |
| `stillAtNoise001` / `notStillAt008` | σ 0.01 / 0.08 | Rest true / false |
| `quantizedResolutionStill` | q = 0.038 | Rest true; q measured within 10% |
| `quietTableIsNotBreathing` | σ 0.006 and 0.026, q 0.038, at 25, 50 and 100 Hz, 8 s | Body false |
| `breathingIsBody` / `seatedThighBreathing0p3` | 0.25 Hz ±1° / 0.3° p-p at 0.2 Hz | Body true / true |
| `creepIsSoft` | 0.3° exponential settle over 8 s | Creep > 0.15° |
| `softSinkDetected` | Damped 4 Hz, 600 ms after the settle | Sinking true |
| `brakingFakeTiltRejected` / `realTiltPassesGate` | a_h 4 m/s² for 2 s / 25° turn at constant \|a\| | Gated tilt false / true |
| `evPullAway2p7WithOffset0p3NoGyro` | a_h 2.7 with a 0.3 opposing offset | No 20° gated tilt |
| `gyroConfirmsRotation` | Accelerometer tilt with / without a matching gyro rate | Rotation true / false |
| `gyroTurnOnBias5dps` | Bias 0.087 rad/s at power-on, 1 s rest | Bias estimated within 0.005; no rotation counted |
| `fakeGyroDetected` | Exact zeros, or name "Virtual gyro" | hasGyro false |
| `burstSlide20cm1500ms` / `burstSlide10cm1000msNoGyro` | Minimum-jerk profile | d = 18–22 cm / ≥ 9 cm |
| `slowSlideBelowPushNoGyro` | 10 cm in 2 s, no gyro | No burst (documents the limit) |
| `tiltStepInLongSegmentNoSlide` | 0.3° step during 60 s of σ 0.06 taps | No burst, no slide |
| `lift5cmWith5degTilt` / `lift3cmIn600ms` | — | ≥ 3 cm / ≥ 3 cm |
| `sofaSinkReboundNotLift` | Down 3 cm, up 2.5 cm | < 1.5 cm |
| `freeFallIsolatedVsRunning` | One 150 ms fall / one every 0.7 s | Isolated / periodic |
| `hopHasKick` | Kick +8 then 80 ms airtime | Kick true |
| `gaitWalkRunStairsCycle` | Each generator | Gait within 3 s |
| `postureSitDownVsSquat` / `rollOver180IsPostureEvent` | — | Event / not; event |
| `handWanderVsMonotonicRoll` | Irregular ±8° / 90° roll in 2 s | H / not |
| `laneChangeIsNotHandInVehicle` | 3 m/s² lateral, there and back in 2 s, vehicle flag | H false |
| `aliased160HzMotorIsNotTremor` / `tremorAt50Hz` | — | Not tremor (vibration flag) / tremor (shadow) |
| `vibrationDetectedAndBlanked` | Motor burst σ 0.3 | Vibration true; rest suspended ≤ 300 ms after |
| `engineIdleIsSteadyVibration` / `typingIsBursty` | 25 Hz steady / 6 taps per second at random | Steady true / false |
| `rate200HzSameDecisions` / `rateIndependence` | Same scenarios at 25–200 Hz | Same decisions; 15 Hz degraded |
| `rateJumpAtScreenOnNoTrigger` | 50 → 200 Hz mid-rest | No event |
| `gap600msResets` / `batchedBurstSameFeatures` | — | Reset / identical features |
| `clockOffsetDetected` / `batchingIsNotClockOffset` | Constant +3 s offset / bursts of 2 s | Corrected / timestamps kept |
| `thermalDriftAbsorbed` | 0.1 m/s² over 10 min | No slide, no tilt |
| `insertionAlongYDetected` | 10 cm along y in 0.8 s after the edge | Insertion true |

### 5.2 `GuardEngineTest` additions

**`FakeHost` gains:**
- `credentialLocked`, `interactive`, `display`, `callState`, `endCallOk`, `gyroOn`, `warnOutput`, `health`, `logs`, `chirps`, `tableTicks`.
- `stream(ms, hz, gen, latencyMs = 0, burstMs = 0)`, which interleaves `advance`.

**Table, pocket and interaction tests run at three latencies:** 0, 300 and 1,000 ms, and with 2 s bursts, while proximity is delivered at once.

**Table**

| Test | Input | Expected |
|---|---|---|
| `faceDownPlacementEntersTable` | Placement to face-down, cover at 70%, rest 8 s, locked | ON_TABLE; `tableTicks` = 1 |
| `faceDownUnlockedTimeout60sEntersTable` | Placed while unlocked; 60 s later `credentialLocked`, `isLocked` | ON_TABLE ≤ 8.5 s after the lock |
| `coveredUprightThenFlatNeverTable` | Covered upright, then flat, still 20 s | IN_POCKET |
| `seatedPocketingNeverTable` | Covered at 8° from flat with 10 cm y-insertion; σ 0.01; 0.3° breathing | IN_POCKET; corpus of 20 sit-pocket-stand cycles: 0 ON_TABLE |
| `faceDownInBagOnTableStaysPocket` / `sofaSinkDoesNotEnterTable` | 400 ms soft settle with 0.3° creep / damped sink | IN_POCKET |
| `jacketLaidFlatStaysWatch` | WATCH, flat, rest 20 s, face-up guard off | IN_POCKET (WATCH) |
| `tableNeedsCredentialLock` / `vehicleContextBlocksTable` / `breathingBlocksTable` / `ferryRollBlocksTable` | — | No ON_TABLE |
| `restartWhileLyingResumesPocket` | Armed while already face-down and covered | IN_POCKET, log `no placement` |
| `blackSurfaceFaceDownEntersTable` | Proximity far, face-down placement, rest 8 s | ON_TABLE noProx |
| `ownTickDoesNotBreakSettle` | Entry with the tick flagged | ON_TABLE |
| `reEntryAfterRecoveryNoTick` | Recovery, then re-entry | `tableTicks` stays 1 |
| `tableSlide14cmChirpsOnly` | 14 cm in 1 s | Chirp; no WARNING |
| `fourSlidesIn120sBurstNoWarning` | 4 slides | `chirps` = 3 + 1 burst; no WARNING |
| `ownerPushesPhoneAsideChirpOnly` | 20 cm slide | Chirp only |
| `tableNudge3cmSilent` / `tableBumpIgnored` / `ringingVibrationIgnored` | — | No chirp, no WARNING |
| `tableLeanWhileWritingNoSlide` | 1° lean for 1 s inside 3 s of taps, no gyro | No slide |
| `typingWithElbowTilt0p3deg10minSilent` / `typingOnDeskKeepsTable` | 10 min bursty taps plus a 0.3° step | ON_TABLE, no event |
| `engineIdleExitsTable` | Steady 25 Hz, 3 s | `table exit reason=ENGINE` |
| `trafficCreepNoTableWarning` | 0.3 m/s² for 1.5 s, every 30 s | No WARNING |
| `tableLiftUncoverWarns` | 8 cm lift, uncover at 0.3 s, then wobble | WARNING `UNCOVER`, then SIREN at grace |
| `tableProximityGlitchIgnored` / `notificationBuzzPlusProxGlitchSilent` | Uncover with no motion / uncover plus vibration 0.5 s | ON_TABLE, glitch logged |
| `twoGlitchesDistrustProximity` | 2 glitches in 10 min | T-UNCOVER off; a later tilt still warns |
| `uncoverWithStalledAccelWarns` | Uncover, no samples for 1.5 s | WARNING `MOTION_UNKNOWN` |
| `tableTiltGyro10` / `tableTiltNoGyro20Gated` / `tableTiltNoGyro16Silent` | — | WARNING / WARNING / none |
| `tableBrakingGyroSilent` / `tableBrakingNoGyroSilent` | a_h 4 m/s² for 2 s | No WARNING; `vehicleSuspect` → exit `VEHICLE` |
| `longHandDragReturnsToTable` | a_h 0.3 one-way for 2.2 s, no vibration, then lift | vehicleSuspect → ON_TABLE → WARNING on the lift |
| `exitBeatsTriggerOnSameTick` | Drive-off and slide cap on the same frame | Exit first |
| `tableLiftFaceUp3cm` / `slowLevelLiftHeld` | Face-up guard 3 cm lift / 2 cm/s lift with 5° wobble | WARNING `LIFT` / `HELD` |
| `tableFallWarns` / `tableHop10mmIgnored` / `pushedOffEdgeStillFalls` | 250 ms fall / kick + 80 ms / 300 ms, lands 8° off | WARNING / none / WARNING `FALL` |
| `tableYawGyroChirps` / `gyroTurnOnBias5dpsNoYaw` / `magneticDisturbanceNoYaw` | — | Chirp / none / none (fake gyro) |
| `slowTiltCappedAt5deg` | 0.2° every 30 s up to 22°, no gyro | WARNING `TILT` once 20° from the entry reference |
| `tableBodyMicroMotionExits` / `bodyExitThenStillReEnters` | Breathing appears / then a new placement | IN_POCKET silently / ON_TABLE |
| `tableGlanceAndPutBackRecovers` | Flip 120°, display on, put back face-down covered, rest 2.5 s | WARNING → ON_TABLE provisional; no SIREN |
| `tableGlanceKeptInHandSirens` | Flip and wobble | SIREN at grace |
| `putBackOnLapDoesNotRecover` | Put back with creep 0.2° | WARNING continues |
| `provisionalBodyResumesWarning` | Recovery, then breathing within 30 s | WARNING, new grace |
| `fourthRecoveryIn10minDoesNotRecover` | — | 4th → SIREN |
| `noDelayTablePickUpWithUnlockAt1sNoSiren` / `noDelayTableLiftSirensAt1p5s` / `noDelayTableSlideChirps` | graceMs = 0 | WAITING / SIREN at 1.5 s / chirp |
| `earbudsDisconnectOnTableBuzzes` / `airplaneOnTableSirens` | — | ON_TABLE plus buzz / SIREN |
| `chargerUnplugOnTableNotHandledLogsOnly` | — | ON_TABLE |

**Pocket**

| Test | Input | Expected |
|---|---|---|
| `gentlePullTiltOutWarnsWithDisplay` | Covered upright 10 s; uncover; 135° in 0.9 s; display ON at +1.0 s; no jolt, no light | WARNING `TILT_OUT` 2.0–3.0 s after the uncover |
| `gentlePullNoDisplayNoTiltOut` | Same without the display | No WARNING; WATCH (documents T2′) |
| `pull90PlusWakeWarns` / `pull90NoWakeSilent` | — | `TILT_OUT` / none |
| `sitDownOpenPocketSilent` | Uncover; 90° in 1 s; seat jolt 4 m/s²; breathing | No WARNING (incl. P-JOLT and P-LONG) |
| `standUpOpenPocketSilent` / `lieDownOpenPocketSilent` / `rollOverInBed180Silent` / `squatPickUpKeysSilent` / `crossLegsOpenPocketSilent` | Generators | No WARNING |
| `salahCycleSilent` | 4 rak'ah, with uncovers during the transitions | No WARNING |
| `outAndInWarnsNotRecovered` | Standing still; out 1.2 s at 70°; covered at 90° from start | WARNING `OUT_AND_IN`; stays after covering |
| `squatOutAndBackSilent` | Out 1.2 s at 70°, back to start | IN_POCKET |
| `looseSidePocketFlipWhileWalkingSilent` / `jacketTakenOffAndHungSilent` | Gait plus flip / pendulum after the cover | No WARNING |
| `outAndInWhileWalkingNeedsCue` | Same as `outAndIn` while walking, plus display | WARNING |
| `quickSameAngleTransferIsKnownLimit` | Uncover 0.7 s, 20°, covered | IN_POCKET |
| `sidewaysYankIsAJolt` | 3 m/s² horizontal, 1.5 s uncovered | WARNING `JOLT` |
| `runThenStopNoTiltOut` | 30 s running, stop, gaping pocket | No WARNING |
| `busPickpocketJoltAtEdgeWarns` / `busPotholeGapingSilent` | Vehicle flag; jolt at the edge, out 3 s / pothole 1 s after the edge, re-covered at 2.5 s | WARNING `JOLT` / none |
| `busPickpocketWithHandStillWarns` | Vehicle; wander 30°, gated | Shadow `WOULD_WARN rule=HANDLED` |
| `severeVibrationDisablesSoftRules` | RMS 1.2, jolt plus uncover | No WARNING |
| `walkingDaylightFlickerSilent` | 0.9 s uncovers at 2,000 lux every 5 s for 10 min | No WARNING |
| `walkingDaylightGape3sSilent` | Uncovered 3 s in daylight, swing ±12° | No WARNING |
| `daylightPullStillWarns` | Not walking, light 0 → 300 | WARNING `LIGHT` at 0.8 s |
| `lightWarningRecoversDuringGait` | Light warning, covered ≤ 1 s, gait unbroken | IN_POCKET |
| `screenLeakage20luxIsNotLight` | Display ON, outside 20 lux, inside 2 | No `LIGHT` |
| `notificationWakeInPocketNoCue` | Gaping, vibration onset, display ON 0.2 s later, 50° swing | No WARNING |
| `unplugWakeIsNotCue` | Display ON 0.5 s after POWER_DISCONNECTED | Not a cue |
| `watchDriftWhileWalkingSilent` / `watchLeanOnCounterSilent` | 1°/min for 60 min while walking / 15° drift then a 35° lean | No WARNING |
| `noEvidenceStaysProtected` | Uncovered 10 s, no cues | WATCH |
| `watchCoveredSamePostureSilent` | WATCH, then covered 10° from u_pre | COVERED, no buzz |
| `pulledOutLaidDownRecoversToTable` | P-LIGHT warning, then rigid rest 2.5 s face-up; later a 25° tilt | ON_TABLE{UP}; then WARNING |
| `motionWarningRecoveryRequiresSamePosture` | Jolt warning, covered 0.5 s later at 50° | Stays WARNING |
| `walkingFlickerSoakSilent` / `walkingGapingPocketSilent` | 10 min walking with flicker / 4 s uncovers | No WARNING |
| `coveredHandlingWarns` / `flipInPocketWarns` / `longUncoverWithHandling` | — | `WOULD_WARN` (shadow) |
| `hangingJacketSilent` | Uncovered and still | WATCH |

**Owner, calls, charger, plumbing**

| Test | Input | Expected |
|---|---|---|
| `unlockEdgeIsOwner` | credentialLocked true → interactive and unlocked | WAITING ≤ 250 ms |
| `trustAgentScreenOnIsNotOwner` / `lockDelayScreenOnIsNotOwner` | `credentialLocked` never true | WARNING continues; DISARM-only |
| `ownerPresentBeforeConfirmNoAlarm` | Edge while a T-rule is pending | WAITING, no WARNING transition |
| `silentWindowHoldsBeepsNotDeadline` | Display DOZE 0.3 s before the trigger, grace 5 s | HELD until +2 s; SIREN at +5 s |
| `noSilentWindowWithoutCredentialLock` / `noDelayHasNoSilentWindow` | — | No hold |
| `ringingRemovalAnsweredNoSirenDuringCall` | RINGING at the trigger; OFFHOOK at +2 s for 50 s | WARNING throughout; SIREN ≥ 10 s after the call ends |
| `answeredCall4MinNoSiren` | OFFHOOK 4 min | No SIREN; CALL_REMINDER every 15 s |
| `callPauseCapEndsCallThenSirens` / `callPauseCapWithoutPermissionSirens` | 6 min call; `endCallOk` true / false | `endCall` at 5:00 then SIREN with ramp / SIREN with ramp, route logged |
| `callEndsThenGrace10s` / `declinedRingThenGrace10s` | — | SIREN at end + 10 s |
| `pocketGaitDuringCallEndsPause` | Trouser-pocket gait 10 s during the call | SIREN 5 s later |
| `vibrateModeRingDetectedByCallState` / `voipRingDetectedByPlayback` | Telephony RINGING in MODE_NORMAL / playback ringtone usage | Ringing-origin |
| `nonRingingWarningNeverPauses` / `outgoingCallNeverPauses` | — | SIREN at grace |
| `chargerUnplugInPocketSilent` / `powerBankCutsWhileWalkingSilent` | Covered / gait | No WARNING; logged |
| `chargerUnplugHandledWarns` | Tilt 8° at +0.5 s | WARNING `CHARGER_HANDLED` |
| `chargerWatchLiftWarns` / `chargerWatchExpires` / `carCrankWatchEndsOnReplug` / `speedBumpInChargerWatchSilent` | — | WARNING / none at 11 min / ended / none (vehicle) |
| `chargerUnplugWithoutAccelWarns` | No accelerometer | WARNING |
| `stallDetectedAfterUnlockAndWarning` | Warning, unlock, then no samples 5 s | DEGRADED (HEALTH survives `cancelAll`) |
| `stallBuzzOncePerSession` / `sensorResumeClearsHealth` | — | 1 buzz / OK |
| `cpuSuspendMarksUnknown` | Δelapsed − Δuptime = 2 s | Log `cpu suspended`; the span is UNKNOWN |
| `warnAcceptsOnTable` / `earbudsInTableBuzz` | — | Guard and branch fixed |

### 5.3 Existing tests

**Move to generators; expectations unchanged:**
- `onMotion(8f)` / `onMotion(4f)` become `jolt(peak)`: a 200 ms half-sine of that peak **along gravity**, on upright gravity (the magnitude rule).
- `onMotion(2f)` becomes `step()` (2 m/s²).

The tests that move:
- `darkRemovalWaitsForSustainedUncover`, `darkFlickerWithYankThatRecoversIsIgnored`, `walkingMotionInDarkPocketIsIgnored`
- `litOutsideButNotBrighterIsNotARemoval`, `recoveredFlickerCancelsPocketWarning`
- every `noDelay*` test, `normalSirenDoesNotStopWhenCovered`
- `frozenLightSensorFallsBackToMotion`, `darkInsideButNoReadingOutsideFallsBackToMotion`, `sensorRepeatingStaleBrightValueWhileCoveredFallsBackToMotion`
- `shortUncoverInsidePocketIsIgnored`, `unlockReArmsWhenStillCovered`

**Expectation change:** `oldMotionDoesNotCount` → IN_POCKET (WATCH), not WAITING.

**Unchanged:** the charger tests (`chargerUnplugWarnsWhenLockedOnly`, `coveringDoesNotCancelOtherWarnings`, `graceUsesConfig`, `noDelayAppliesToChargerToo`, `secondTriggerDuringWarningDoesNotRestartGrace`). They stream no samples, so motion is UNKNOWN and the unplug warns (fail-safe, §4.5).

**`disarmCancelsEverything`** still asserts no timers, because disarm cancels background timers too.

**`callsNeverCountAsPocketOrRemoval`** uses `callAtEar`.

### 5.4 Soak tests, random theft variants, recorded traces

- **Soak** (fixed seeds, with offsets, at 25/50/100 Hz and 0/1 s latency): 8 h of mixed class-A signals, including salah, bus, café typing, bed and power bank. Pass = 0 WARNING and ≤ 2 chirps per hour of café use.
- **Theft variants:** 500 seeds per class (pull 0.3–2 s; palmed or not; light awake or not; display woken or not; owner walking, standing or seated; vehicle or not). Pass = ≥ 99% caught for T1, T2, T4 (new angle), T7–T10 and T13. T2′, T3, T4′, T5 and the sleeping-victim class are **reported as measured**, not gated.
- **`TraceReplayTest`** over `app/src/test/resources/traces/*.csv.gz`:
  - header `# expect: WARNING trigger=TABLE_MOVED evidence=TILT within_ms=1500`, or `# expect: none`, or `# expect: chirp`
  - rows `t_ms,kind,a,b,c`, where kind is ACC, GYR, PROX, LUX, DISPLAY, UNLOCK, CALL, POWER or VIB
- **Every field false alarm becomes a trace test.**

### 5.5 Emulator e2e

**Structure:**
- `e2e/lib.sh` holds the helpers; `e2e/run.sh <apk> <suite>` with suites `core | table | platform`.
- CI runs a 3-job matrix, each with its own emulator (API 34). `platform` also runs on API 35.
- Release screenshots come from `core`.
- Setup runs `adb logcat -G 16M`.

**`e2e/emuctl.py serve`** starts once after boot:
- Opens one console connection to `localhost:<port from $ANDROID_SERIAL>` and sends `auth $(cat ~/.emulator_console_auth_token)`.
- Streams the accelerometer every 20 ms with default noise σ 0.015 per axis and quantisation 0.0096.
- If the E0 probe lists a gyroscope, also streams the gyro with σ 0.002 rad/s and bias 0.003.
- **All** proximity, light, accelerometer and gyro writes go through it, so ordering is kept.
- Listens on `127.0.0.1:5599`; each command replies `OK` when finished, and any console `KO` fails the run.
- Commands: `pose`, `prox near|far`, `light`, `noise`, `offset`, `tilt --to [--via] --ms [--gyro]`, `place --to --drop-cm --ms --cover-at`, `insert --axis --cm --ms`, `slide`, `lift --cm --ms [--uncover-at-ms]`, `accel --axis --ah --ms`, `bump`, `hop --kick --air-ms`, `freefall --ms --then`, `breathe --deg --hz --secs`, `wobble --deg --secs`, `taps --rate --peak --secs`, `vibrate --sigma --secs`, `walk ...`, `replay FILE`.

**Helpers:**

```bash
motion() { python3 e2e/emuctl.py client "$@" || fail "motion $*"; }
emu() { local out; out=$(adb emu "$@" 2>&1); grep -q '^KO' <<<"$out" && { fail "emu $*: $out"; return 1; }; }  # gsm etc. only
still() { motion pose 0:9.81:0; }; covered() { motion prox near; }; uncovered() { motion prox far; }
pocket() { motion light 0; covered; }; jolt() { motion pose 6:6:18; sleep 0.4; still; }
current_phase() { { cat "$FULL_LOG"; app_log; } | grep -oE 'phase=[A-Z_]+' | tail -n1 | cut -d= -f2; }
reset_state() {
  uncovered; still; motion light 400
  case "$(current_phase)" in WAITING_FOR_POCKET|"") ;; *) unlock ;; esac
  sleep 1; screen_off; expect_log "lock check: locked=true" 6 "reset: locked"; reset_log
  [ "$(current_phase)" = WAITING_FOR_POCKET ] || [ -z "$(current_phase)" ] || fail "reset: phase $(current_phase)"
}
table_down() { reset_state; motion place --to 0:0:-9.81 --drop-cm 10 --ms 900 --cover-at 0.7
  expect_phase IN_POCKET 6 "$1: pocketed"; expect_phase ON_TABLE 14 "$1: on the table"; reset_log; }
```

`write_prefs` gains optional `ignore_gyro`, `face_up_guard` and `e2e_call_cap_s` entries. Every S-scenario starts with `reset_state` (or `table_down`). Distances and angles are checked as ranges with 2× margins.

**Core suite:** existing scenarios 1–6 and 8–10, ported to the helpers, plus:

| ID | Commands | Expect |
|---|---|---|
| E0 probe | `adb emu sensor status`; `dumpsys sensorservice \| grep -iE "gyro\|signific"`; `dumpsys audio \| grep -i mode` during `emu gsm call 5551234` | Printed; sets `HAS_GYRO`, `CALL_OK` |
| 7 (updated) | `dumpsys battery set ac 1`; `motion tilt --to 0:9.66:1.7 --ms 300 --gyro &`; `dumpsys battery unplug` | `trigger=CHARGER_UNPLUGGED` ≤ 8 s |
| S19 gentle pull | `upside_down; pocket; expect IN_POCKET; sleep 3; reset_log; uncovered; sh input keyevent KEYCODE_WAKEUP & motion tilt --to 0:6.9:6.9 --ms 900` | `evidence=TILT_OUT` ≤ 5 s (today: nothing) |
| S19b | 90° pull plus wake | `TILT_OUT` ≤ 5 s |
| S19c | 90° pull, no wake | `expect_no_phase WARNING 5` (documents the limit) |
| S20 | Uncover plus `wobble --deg 5-15 --secs 3` | `WOULD_WARN rule=HANDLED` ≤ 5 s |
| S21 out and in | Uncover; tilt to 70° over 700 ms; 0.5 s; tilt to 9.81:0:0; covered | `evidence=OUT_AND_IN` ≤ 4 s; no `IN_POCKET from=WARNING` |
| S22 | Covered flip via 9.81:0:0 | `WOULD_WARN rule=FLIP` |
| S23 walking | `walk --secs 20 --step-hz 1.8 --swing 25 --flicker-every-ms 2000 --flicker-ms 400` | No WARNING |
| S24 sit, open pocket | Uncover; tilt to 0:0:9.81 over 1 s; `bump --peak 4`; `breathe --deg 1 --hz 0.25 --secs 8` | No WARNING 9 s |
| S26 ringing call (if `CALL_OK`) | `e2e_call_cap_s=20`; pocket; `emu gsm call`; 2 s; pull_out; `KEYCODE_CALL` | `ringing=true` WARNING; `call pause`; no SIREN for 18 s; then `call ended by guard` and `phase=SIREN`; `dumpsys audio` mode NORMAL; unlock |
| S26b declined | Ring, pull, `emu gsm cancel` | SIREN between 10 and 14 s after the cancel |
| S38 lock delay | 30 s lock delay; face-down covered; the screen times out; wake; flip plus uncover | `phase=WARNING` (not WAITING); `screen_off` → lockWatch → unlock |

**Table suite** (for each: `table_down S<n>; reset_log; ...`):

| ID | Commands | Expect |
|---|---|---|
| S11 flip | `uncovered` at +0.3 s; `tilt --to 0:-6.9:-6.9 --ms 600 --gyro` | `evidence=(TILT\|UNCOVER)` ≤ 3 s |
| S11b put back | After S11: `tilt --to 0:0:-9.81 --ms 600 --gyro; covered` | `phase=ON_TABLE from=WARNING` ≤ 6 s; no SIREN for 16 s |
| S12 slide | `slide --axis x --cm 25 --ms 1200` | `table slide d=` 12–40 ≤ 4 s; no WARNING 4 s |
| S12b | 4 slides 10 s apart | `slide burst`; no WARNING |
| S13 level lift | `lift --cm 8 --ms 600 --uncover-at-ms 300; wobble --deg 3-8 --secs 18` | `evidence=(UNCOVER\|LIFT\|HELD)` ≤ 3 s; SIREN ≤ 17 s; unlock |
| S14 / S14b glitch | `uncovered` / `uncovered; vibrate --sigma 0.3 --secs 0.5` | No WARNING 3 s; log `table proximity glitch` |
| S15 bumps | 3 × `bump --peak 2 --ms 60`; `vibrate --sigma 0.3 --secs 1` | No WARNING, no chirp |
| S16 / S16b | `freefall --ms 250 --then 0:1:-9.76` / `hop --kick 8 --air-ms 80` | `evidence=FALL` / no WARNING |
| S17 vibrating surface | `reset_state; covered; vibrate --sigma 0.08 --secs 10` | No ON_TABLE |
| S18 braking | `accel --axis y --ah 4 --ms 2000`, with gyro and with `ignore_gyro` | No WARNING; `table exit reason=VEHICLE` ≤ 15 s |
| S18b EV | `ignore_gyro`; `offset 0:-0.3:0`; `accel --axis y --ah 2.7 --ms 4000` | No WARNING 15 s |
| S35 café | `tilt --to 0:0.17:-9.81 --ms 500 --gyro; taps --rate 6 --peak 0.3 --secs 8` (and the `ignore_gyro` version without `--gyro`) | No WARNING, no slide, no table exit |
| S36 timeout entry | `reset_state`; unlock; place face-down covered; 35 s; `screen_off` | ON_TABLE ≤ 12 s after the screen goes off |
| S37 seated pocketing | `pose 0:1.4:9.7; insert --axis y --cm 10 --ms 800` with cover at the start; `breathe --deg 0.5 --hz 0.25 --secs 15` | No ON_TABLE for 15 s |
| S27 face-up guard (optional) | `face_up_guard`; face-up, uncovered, screen off | ON_TABLE ≤ 15 s; 20° tilt → `evidence=TILT` |
| S28 yaw (if `HAS_GYRO`) | `motion` gyro 0:0:1.2 for 0.4 s, with matching tiny motion | Chirp logged; no WARNING |
| S29 no-gyro profile | `ignore_gyro`; S11 with a 30° tilt (no `--gyro`), S18 | Same as S11 / S18 |
| S34 silent window | Wake first, poll `mWakefulness=Awake`, then the S11 tilt | `warning held`; `beeps started` ≥ 1.5 s after `trigger at`; `siren_at` = trigger + 15,000 |
| S39 no-delay | grace 0; S11 | SIREN 1.4–3 s after the trigger |

**Platform suite:**
- `trap 'adb shell cmd sensorservice reset-uid-state $PKG' EXIT`.
- S33 runs last.

| ID | Commands | Expect |
|---|---|---|
| S25 | Upright still; `dumpsys battery set ac 1`; screen off; 4 s; unplug | No WARNING 5 s; `charger watch`; tilt 30° → `trigger=CHARGER_UNPLUGGED` ≤ 3 s |
| S25b | `walk --secs 10` plus unplug | No WARNING; `charger unplugged` logged |
| S30 stall | `cmd sensorservice set-uid-state $PKG idle` | `sensor stall` ≤ 8 s; reset → `sensor resumed` ≤ 5 s |
| S31 Doze | `dumpsys deviceidle enable deep`; whitelist −$PKG; unplug; `force-idle` | Assert `deviceidle get deep` = IDLE and `dumpsys power \| grep PocketGuard:guard`; then S12's slide → chirp logged; then restore |
| S32 kill | `run-as $PKG kill -9` | `service restarted gap=` ≤ 30 s; `dumpsys alarm \| grep $PKG`; `auto_armed` kept |
| S33 background restriction | `cmd appops set $PKG RUN_ANY_IN_BACKGROUND ignore` | Service stopped; the watchdog posts "stopped by the system"; `allow`; re-arm |

**What the emulator can't fake:** OEM throttling and freezing (S30 and S32 approximate it), virtual proximity, real noise, drift, rattle and creep spectra, significant motion, sensor FIFOs, lift-to-wake, face or fingerprint unlock before the swipe, real call audio routing, battery drain, and tremor.

### 5.6 Field checklist for the owner

**Setup for every run:** test mode, PIN plus fingerprint, grace 5 s, Detection lab recording on. Run each line 5 times and label each trace "me" or "test theft".

1. **Setup.**
   - Run Sensor check with the screen off in a pocket.
   - Let the screen time out once (lock check).
2. **Table.** Place it face-down and wait for the double tick. Then:
   - (a) Slide 15 cm: a chirp only.
   - (b) Lift level 10 cm and hold: beeps, then siren at 5 s.
   - (c) Turn it over to look, then put it back face-down: beeps stop within about 3 s of putting it down.
   - (d) Lift with your palm over the screen: beeps.
   - (e) Push it off the edge onto a cushion: beeps.
   - (f) Tap the table, set a cup down, type on a laptop on the same table, let a message buzz: silence.
   - (g) Pick it up with your fingerprint: ideally no sound.
   - (h) Put it face-down on your lap, on a sofa, in your open handbag, in your pocket while seated: **no double tick** (note any tick).
3. **Pocket** (a friend acting, screen off, in a dark room):
   - (a) Slow pull, waking the screen to look: beeps within 3 s.
   - (b) Slow pull without waking it: note the result.
   - (c) Pull and slip it into the friend's bag lying flat: beeps.
   - (d) Fast yank: beeps.
   - (e) The same on a bus while standing: beeps.
4. **Everyday, must be silent** (log any beep or chirp with a timestamp):
   - 30 min walking, 15 min stairs, 30 min bus or rickshaw, 20 min cycling.
   - Sitting down and standing up 10 times in tight jeans and in loose panjabi pockets.
   - One prayer cycle.
   - 1 h at a café face-down; 1 h on your lap.
   - Bed with the phone in a pocket; a jacket hung up; a bag carried and set down.
5. **Calls.** While pocketed, have someone call (SIM and WhatsApp); pull it out and answer on the lock screen; talk 3 min: no siren. Decline and pocket: beeps, then you have 10 s to unlock.
6. **Charger.**
   - Unplug at the wall without touching the phone: silent. Then pick it up: beeps.
   - Unplug at the phone: beeps.
   - Power bank in a pocket running empty: silent.
7. **Battery.** 3 h armed with the screen off, unplugged, before and after the update.

### 5.7 Acceptance targets

**Theft detection:**
- ≥ 19 of 20 for T1, T2 (display woken), T4 (new angle), T7–T10 and T13 on phones whose Sensor check passes.
- Median time to WARNING ≤ 3 s in a pocket and ≤ 1 s on a table.

**False alarms:**
- 0 WARNING in the synthetic soak.
- 0 WARNING in a recorded class-A corpus of ≥ 50 h: 5 h bus/rickshaw, 3 h walking, 2 h cycling, 8 h bed, 3 h café, 1 h prayer, 1 h power bank.
- 0 ON_TABLE entries on laps, sofas, bags and seated pockets (≥ 40 traces).
- ≤ 2 chirps per café hour.
- Live carry: ≤ 1 class-A warning per 20 h.
- **Zero false sirens.**

**Owner:** 0 sirens when unlocking within grace, or when putting a glanced-at phone back; ≥ 80% of biometric pick-ups make no sound.

**Battery:** within ±0.1 %/h measured as in §3.5.

**Shadow rules** go live one at a time, only when their `WOULD_WARN` count in the corpus meets the false-alarm target.

---

## 6. Known limits and user settings

### What this app cannot detect, or detects only partly

1. **Who is holding it**, until they unlock. Owners, friends and waiters get warnings by design.
2. **Anything while unlocked or in use**, including a snatch from the hand (use Theft Detection Lock), and anything on phones without a credential lock, or with Smart Lock or a lock delay active (no table mode; DISARM-only alarms).
3. **A bag or jacket carried off** with the phone inside. A hard bag on a table that passes the hard-surface test warns when you lift it (B11).
4. **Quick transfers at the same angle:** handled for under about 1.5 s, at the same angle, in the dark. Also any transfer that ends at the same angle without light or display (T4′), until hand detection is live.
5. **Gentle pull in the dark without waking the display** (T2′, T3). It is caught only if it then goes into a pocket or bag at a new angle (standing owner), or by later light or display cues, or by H once validated. This is the price of not false-alarming every time someone sits down with an open pocket.
6. **Pulls from a walking owner in daylight** that keep the phone within 30° of its pocket angle and don't wake it.
7. **Theft from someone lying down or asleep:** orientation rules need light or display; P-JOLT still catches quick pulls.
8. **Slides never alarm** (chirp only). The lift, tilt or fall that removes the phone does. On gyro-less phones, slides slower than about 10 cm/s aren't even chirped.
9. **Very careful level lifts on gyro-less phones** under 3 cm/s with < 4° wobble, until the sensor uncovers (face-down) or 3 s pass.
10. **The first 8–10 s after placing it on a table** have pocket-level protection only, until the double tick.
11. **A placement that isn't latched** (placed and then slid, set down roughly) gives no table mode. No double tick means: place it again.
12. **Seated pocketing that passes every entry test** may still enter table mode. The double tick tells you, and standing up then warns (unlock).
13. **Vehicles and vessels:** no table mode for up to 10 min after a ride, unless you walk 10 steps. A strong wave can tilt a phone on a ferry. Severe vibration leaves only the light and display rules active.
14. **The call pause:** an accomplice's call answered on the lock screen buys up to 5 min of silence, unless the phone is carried in a trouser pocket. With ANSWER_PHONE_CALLS denied, the siren at the cap may play in the earpiece until the call ends.
15. **Lock-screen use from the pocket** (music controls, glance) warns, by design.
16. **Proximity that sleeps with the screen off:** no pocket protection; noProx table mode and the tamper triggers only. OEM throttling is detected and reported, not prevented.
17. **Power-off, forced restart, a signal-blocking pouch:** existing limits; use "password to power off".
18. **Car kits reporting HANDSFREE** still count as earbuds for the earbud triggers (existing behaviour); only CAR_AUDIO counts as a vehicle.

### Settings the user can change

| Setting | Default | Notes |
|---|---|---|
| Unlock delay | 5 s (0–15) | History suggests p95 unlock time + 2 s |
| Table protection (automatic) | On | Off brings back today's behaviour |
| Face-up table protection | Off | — |
| Chirp when slid or turned on the table | On | Off: log only |
| Pocket handling detection | Standard | Strict turns on the shadow rules early |
| Charger alarm | On | With the silent watch |
| Calls permission (optional) | Asked in setup | Ringing detection with vibrate or silent ring; the call can be ended at the cap |
| Detection lab | Off | Incident traces are always kept locally |

Thresholds, the call cap and the recovery limits are not user settings. Each would weaken protection and make the tests meaningless.

---

## 7. Implementation order

Each step is a PR with a minor version bump, a README update, and passing unit tests, lint and every e2e suite.

1. **Test infrastructure and plumbing (no behaviour change):**
   - `emuctl` daemon, `lib.sh`, `reset_state`, the E0 probe, the CI matrix and `logcat -G`.
   - `FakeHost.stream` with latency and bursts; `SignalGen` with offsets.
   - Phase and background timers; the `warn()` guard; `onTransition(..., evidence, detail)`; cached `Config` and `Prefs`; the I/O executor.
   - Fix the auto-disarm restart bug.
   - Gate: every existing test passes.
2. **Time base:** timestamps for every sensor, clock check, watermarks, resampling, HEALTH and suspend detection, rate tiers. Gate: §5.1 rate and clock tests, S30.
3. **Owner, display and calls:**
   - Edge-based owner and OWNER_POLL; DisplayListener; the silent window.
   - Call state (telephony, modes, playback); the call pause; output modes and audibility; History changes.
   - Gate: §5.2 owner and call tests, S26, S26b, S34, S38.
4. **`MotionProcessor` live:**
   - Raw accelerometer; jolt by magnitude plus the sideways branch; delete `Detection.motion`.
   - Charger rules and CHARGER_WATCH; the trace buffer and Detection lab.
   - Gate: `MotionProcessorTest`, §5.3 ports, scenario 7, S25, S25b.
5. **ON_TABLE** (the field report, gap 2):
   - Latch, table test, T-rules, recoveries, exits, vehicleSuspect, chirps; UI, notification and history.
   - **Goes live** after the table suite, S29 and checklist section 2 pass.
6. **Pocket episode and WATCH** (gap 1, part of gap 3):
   - No give-up; the posture window; cues; contexts; P-TILT_OUT and P-OUT_AND_IN; P-LIGHT and P-JOLT context rules; flicker and rest recovery.
   - 1 week in shadow on the owner's carry, then live if §5.7 holds.
   - Gate: S19–S24, soak.
7. **Shadow rules:** H, P-HANDLED, P-LONG, P-COVERED_HANDLING, P-FLIP, P-CARRY_CHANGE. Each goes live separately when the corpus meets the targets.
8. **Platform safeguards:** heartbeat, watchdog alarm, background-restriction block, screen-off Sensor check, stuck-mode notice, re-registration backoff. Gate: S31–S33.
9. **Optional:**
   - Face-up guard (S27) and yaw chirp (S28).
   - The full field protocol on a Pixel, a gyro-less Galaxy A, a Redmi and a MediaTek phone; constants tuned only from recorded traces.

**Assumptions to verify on real phones before relying on them:**
- `isDeviceLocked` turns false after face authentication, before the swipe.
- Playback attributes of other apps are visible.
- `endCall()` works on 14 and 15.
- Where the in-call siren is routed.
- Starting a foreground service from the watchdog alarm when battery-exempt.
- Breathing amplitude on a seated thigh (≥ 0.3°?).
- The insertion signature on real pockets.
- Screen-off accelerometer rates on budget phones.

---

## 8. Red-team findings and resolutions

**Critique 1 (missed thefts).** Its text delivered no findings; it was cut off before them, so there is nothing to resolve. To make up for it, I checked every theft in §1a against the final rules. The thefts not fully covered are T2′, T3, T4′, T5, quick same-angle transfers, the sleeping victim, daylight pulls from a walking owner, call-pause abuse and very slow level lifts. Each is stated in §6 (limits 4–9 and 14) and measured, not gated, in §5.4.

**Critique 2 (false alarms):**

| # | Finding | Resolution |
|---|---|---|
| 1 | Glancing at a face-down phone sirens; SCREEN_ON misses ambient display; face-up protection switched on silently | **Closed.** <ul><li>Put-back recovery (§2.7).</li><li>Silent window from DisplayListener rank, DOZE included (§4.2), held min(2 s, grace/2). Not 3 s: put-back already covers glances, and a longer hold only quietens beeps for a thief.</li><li>WATCH → ON_TABLE{UP} needs the face-up guard.</li><li>Rest recovery may go face-up deliberately, because the alternative is a siren.</li></ul> |
| 2 | ON_TABLE entered on the owner's body: seated pocketing, lap, restart while lying | **Mostly closed.** <ul><li>Latch with an insertion test and clean settle; 8 s table test with creep, body (0.3°) and sinking checks.</li><li>Arm-time exception removed; a restart resumes IN_POCKET.</li><li>Residual seated-pocket risk accepted: the double tick warns, and the field corpus gates it (§6.12).</li></ul> |
| 3 | Soft containers and surfaces (bag, sofa, jacket) | **Closed** by the table test on every entry path and the WATCH restriction. Residual: hard bags (§6.3). |
| 4 | Calls longer than 60 s, hang up and pocket, decline | **Mostly closed.** <ul><li>Cap raised to 5 min, with reminders; no vibration at the ear; early end on trouser-pocket gait.</li><li>After the call or a declined ring: grace ≥ 10 s.</li><li>"No cap" rejected: it leaves accomplice calls unbounded. The trade-off is stated in §6.14.</li></ul> |
| 5 | WhatsApp and other VoIP rings miss the pause | **Closed:** playback `USAGE_NOTIFICATION_RINGTONE` plus telephony state (§4.4). Verify per app in the field. |
| 6 | Sit-down races the posture veto; orientation can't tell a thigh from a hand | **Closed:** decide after the posture window (≤ 1.5 s hold); P-TILT_OUT needs display or light; seat-impact posture veto. Cost stated in §6.5. S19 now wakes the screen; S19b and S19c added. |
| 7 | P-LONG fires on sitting and lying | **Closed:** P-LONG uses H and shares the vetoes, so it moves to shadow. The owner's "uncovered 3 s" idea is replaced by WATCH. |
| 8 | Rotation counted as a jolt | **Closed:** magnitude jolt plus the gated sideways branch (§3.4); gait uses boxcar gravity. Tests `fastRotationIsNotJolt` and `crossLegsOpenPocketSilent`. |
| 9 | Rolling over, bending, salah | **Closed:** no upper cap on posture events; "steady" defined by turning rate; lying and rest contexts need a cue; postureCycling context. |
| 10 | Café jostling causes phantom slides or silently loses table mode | **Closed:** <ul><li>Gyro-tracked bursts; no-gyro push threshold 0.4; T-SLIDE(b) dropped.</li><li>T-BODY needs a body signature; the latch persists for re-entry.</li><li>Slides only chirp anyway.</li><li>Cost: gyro-less slides under about 10 cm/s (§6.8).</li></ul> |
| 11 | Power banks and car chargers | **Closed:** covered, walking or vehicle → log only; the watch needs prior rigid rest; replug or vehicle ends it (§4.5). |
| 12 | No-delay table pick-up sirens at once | **Closed:** 1.5 s owner window, with put-back recovery. Cost: 1.5 s for a thief within reach. |
| 13 | WATCH reference goes stale | **Closed:** 5 s bounded θ window in WATCH; u_pre re-taken while walking; orientation rules need a cue in WATCH. |
| 14 | P-LIGHT has no walking or loose-pocket veto | **Closed, stricter than proposed:** in those contexts it needs 1.5 s plus (θ ≥ 30°, display or H), because a 3 s gape in daylight still fired under the proposed fix. Light warnings recover during unbroken gait. Cost: §6.6. |
| 15 | Vehicle swerves look like a hand | **Closed:** gravity gated at 0.3 for θ and H in vehicles. Also, P-JOLT in vehicles now needs an edge-correlated jolt plus 3 s instead of "needs a hand", so bus pickpocketing stays live (§2.6). |
| 16 | Stop-and-go traffic, ferries | **Closed:** vehicle context ends only on steps, AR exit or 10 min; engine-vibration exit; vessel oscillation blocks entry; slides only chirp. A sudden large wave → T-TILT is accepted (§6.13). |
| 17 | Slide warnings annoy | **Closed:** chirp; 4 in 120 s → beep burst; never a warning or siren. |
| 18 | T-UNCOVER fires on proximity glitches | **Closed:** needs a lift-like motion (3° or 1 cm) and no vibration; 2 glitches → proximity distrusted. |
| 19 | Vibration from other apps | **Closed:** vibration detector, blanking, doubled slide and yaw thresholds, playback-based ringing. |
| 20 | T-FALL on small hops | **Closed:** no kick, ≥ 100 ms, landing away from `g_ref`. |
| 21 | P-OUT_AND_IN with loose pockets, bags, jacket removal | **Closed:** needs a cue outside quietUpright; pendulum → WATCH. Cost: §6.4 and §6.6. |
| 22 | Using the lock screen from the pocket | **Accepted:** a recovery would reopen the pocket-to-pocket gap. User guidance in §4.11; documented in §6.15. |
| 23 | Screen-on counts as a cue too easily | **Closed:** display cue timing windows and exclusions; the "replaces the uncover" path removed. |
| 24 | Repeated buzzes | **Closed:** tick on the first entry per latch; stall buzz once per session; no restart notice after a clean shutdown. |
| 25 | Smart Lock: table warnings are DISARM-only | **Closed:** ON_TABLE needs `isDeviceLocked`. Cost: no table mode for those users (§6.2). |

**Critique 3 (platform pitfalls):**

| # | Finding | Resolution |
|---|---|---|
| 1 | Owner check is a level, so trust agents and lock delays pass as the owner | **Closed:** edge-based owner with `credentialLockedSeen`; no shortcut or silent window otherwise (§4.1); tests plus S38. |
| 2 | Sensor noise trips the breathing veto | **Closed:** 2 Hz block means plus reversal test; noise-level and rate tests; `emuctl` streams noise and quantisation by default. |
| 3 | Face-down then screen timeout never enters; vibration exits for good | **Closed:** persistent latch; T-BODY needs a body signature; tick once per latch; tests plus S36. |
| 4 | Long segments → T-SLIDE on busy tables | **Closed:** ≤ 3 s bursts with a starting pulse and bias from the rest before; long low-energy segments are surface vibration; rule (b) dropped. |
| 5 | Rotation as a jolt; the posture race | **Closed:** as Critique 2 #8 and #6; boxcar gravity in gait; DECIDE hold. |
| 6 | T-LIFT swamped by tilt and offsets | **Closed:** \|a\| − G0_pose, ≤ 1 s, 3 cm; T-HELD catches slow lifts; offsets in every `SignalGen` test. |
| 7 | Delivery delay; missing data treated as no motion | **Closed:** event timestamps everywhere, offset estimator, watermarks, UNKNOWN fails safe, suspend detection, latency-parameterised tests. |
| 8 | `ringing()` misses silent and VoIP rings; quiet in-call siren | **Closed / partly accepted:** telephony listener plus extra modes plus playback; `endCall()` at the cap; route logged. Without the permission the pause still applies (owner benefit), and the earpiece-siren residual is stated (§6.14), rather than "don't pause at all". |
| 9 | SCREEN_ON is neither a hand nor an owner cue | **Closed:** dropped as a standalone cue; DisplayListener; exclusions; silent window only with the edge check; stricter P-LIGHT with the display on. |
| 10 | Engine plumbing gaps | **Closed:** `warn()` guard, timer split, OWNER_POLL timer, earbuds branch, evidence in `onTransition`, exits before triggers (§2.1, §2.2). |
| 11 | e2e table scenarios don't reset state | **Closed:** `reset_state` and `current_phase`; every scenario starts with it. |
| 12 | Platform e2e can't run or proves nothing | **Closed:** S31 deep-idle assertions; S33 last, asserting the stop and watchdog; S34 wakes first; S30 trap. Background restriction is a blocking setup failure. |
| 13 | Real rates, aliasing, resolution | **Closed:** resampling, measured quantisation, vibration detector; tremor stays shadow; rate tests. |
| 14 | Gyro bias, fake gyros | **Closed:** in-app bias, 100 ms discard, yaw only inside motion, fake-gyro detection. |
| 15 | Watchdog can't see a dead service; restart drops auto-disarm | **Closed:** AlarmManager watchdog; wall clock plus BOOT_COUNT; null-intent fix; `auto_disarm_at`. |
| 16 | Sound-only table warnings inaudible; ramp fights `volumeLock` | **Closed:** TABLE output on `USAGE_ALARM` 40% with DND override; `audible=false` log; ramp updates `targetAlarmVolume`. |
| 17 | No-gyro 15° has no offset margin | **Closed:** 20°, G0_pose, EV-with-offset test. |
| 18 | Vehicle sources don't deliver | **Closed:** AR ENTER and EXIT while table protection is on; CAR_AUDIO only; ACL tracking. Earbud HANDSFREE behaviour unchanged and noted (§6.18). |
| 19 | Main-thread and allocation budget | **Closed:** config and prefs caching, I/O executor, copied values, 100 ms batching, measurement on the shipped APK with a HandlerThread fallback. |
| 20 | Registration hygiene | **Closed:** user-driven proximity proof, per-sensor backoff, `cancelTriggerSensor`, flush plus "unknown" without an initial event. |
| 21 | History fills up | **Closed:** entry only when audible; recovered tag; separate escalated cap; traces in device-protected storage. |
| 22 | Battery check can't measure 0.1 %/h | **Closed:** unplugged, ≥ 3 h, CHARGE_COUNTER A/B, % over 8 h as the fallback. |
| 23 | CI capacity and fidelity | **Closed:** 3-job matrix, logcat 16 MB with rate-limited shadow lines, range checks, gyro scenarios gated by the probe, platform suite on API 34 and 35. |