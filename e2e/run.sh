#!/usr/bin/env bash
# End-to-end test on an emulator. Fakes the proximity, light and motion sensors and system events
# with adb, then checks what the app logs ("PocketGuard: ..."), what it stores (history, photos)
# and what is on screen. Every user-facing feature an emulator can exercise is covered.
set -uo pipefail

APK="$1"
PKG=com.taiyeb.pocketguard
PIN=1234
FAILED=0
PASSED=0
OUT=e2e-output
FULL_LOG="$OUT/pocketguard.log"
mkdir -p "$OUT"

log() { echo "[e2e] $*"; }
emu() { adb emu "$@" >/dev/null; }
sh() { adb shell "$@"; }
app_log() { adb logcat -d -v time -s PocketGuard:*; }
pass() { log "PASS: $1"; PASSED=$((PASSED + 1)); }

fail() {
  FAILED=$((FAILED + 1))
  log "FAIL: $1"
  local file="$OUT/fail-$FAILED.png"
  adb exec-out screencap -p >"$file" 2>/dev/null || true
  log "  screenshot: $file"
  log "  app log (latest):"
  { tail -n 8 "$FULL_LOG"; app_log; } | tail -n 15 | sed 's/^/    /'
  log "  window state:"
  sh dumpsys window | grep -E "mCurrentFocus|isKeyguardShowing" | head -n 3 | sed 's/^/    /'
}

# Waits until the app log (since the last reset) matches $1, up to $2 seconds.
expect_log() {
  local pattern="$1" timeout="$2" name="$3"
  for _ in $(seq 1 $((timeout * 4))); do
    if grep -qE "$pattern" <<<"$(app_log)"; then
      pass "$name"
      return 0
    fi
    sleep 0.25
  done
  fail "$name (no log matching '$pattern' within ${timeout}s)"
  return 1
}

expect_phase() { expect_log "phase=$1 " "$2" "$3"; }

# Asserts the app does NOT log phase=$1 within $2 seconds.
expect_no_phase() {
  sleep "$2"
  if grep -q "phase=$1 " <<<"$(app_log)"; then fail "$3 (unexpected phase=$1)"; else pass "$3"; fi
}

expect_true() { if eval "$1"; then pass "$2"; else fail "$2"; fi; }

reset_log() { app_log >>"$FULL_LOG"; adb logcat -c; }
screen_off() { sh input keyevent KEYCODE_SLEEP; sleep 1; }
keyguard_showing() { sh dumpsys window | grep -q "isKeyguardShowing=true"; }

# Alarm screens sit on top of the lock screen, so leave them first, then open the PIN bouncer.
unlock() {
  sh input keyevent KEYCODE_WAKEUP; sleep 0.7
  sh input keyevent KEYCODE_HOME; sleep 0.7
  sh wm dismiss-keyguard; sleep 1
  sh input keyevent KEYCODE_MENU; sleep 0.7
  sh input text "$PIN"; sleep 0.3
  sh input keyevent KEYCODE_ENTER; sleep 2
  if keyguard_showing; then
    log "  (keyguard still showing after PIN entry, retrying)"
    sh wm dismiss-keyguard; sleep 1
    sh input text "$PIN"; sh input keyevent KEYCODE_ENTER; sleep 2
  fi
}

still() { emu sensor set acceleration 0:9.81:0; }
uncovered() { emu sensor set proximity 10; }
covered() { emu sensor set proximity 0; }
pocket() { emu sensor set light 0; covered; }
jolt() { emu sensor set acceleration 6:6:18; sleep 0.4; still; }
covered_off_with_light() { uncovered; emu sensor set light 400; }
pull_out() { jolt & covered_off_with_light; wait; }
calm_down() { still; sleep 3.5; } # let the motion window (3 s) expire

# Settings are written straight into the app's device-protected storage (debug build: run-as).
# Grace is 15 s so the scripted PIN entry (several seconds on CI) fits inside it.
write_prefs() {
  local onboarded="${1:-true}"
  cat >"$OUT/prefs.xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="onboarded" value="$onboarded" />
    <int name="grace_seconds" value="15" />
</map>
EOF
  sh am force-stop $PKG
  adb push "$OUT/prefs.xml" /data/local/tmp/pg-prefs.xml >/dev/null
  sh chmod 644 /data/local/tmp/pg-prefs.xml
  adb shell "run-as $PKG sh -c 'mkdir -p /data/user_de/0/$PKG/shared_prefs && cp /data/local/tmp/pg-prefs.xml /data/user_de/0/$PKG/shared_prefs/pocket_guard.xml'"
}

arm() {
  sh am start -W -n $PKG/.MainActivity --ez arm true >/dev/null
  expect_phase WAITING_FOR_POCKET 10 "$1"
}

# --- Device setup -------------------------------------------------------------------------------

log "Setup: PIN lock that locks instantly"
sh locksettings set-pin "$PIN" >/dev/null
sh settings put secure lock_screen_lock_after_timeout 0
sh settings put system screen_off_timeout 600000
sh cmd connectivity airplane-mode disable >/dev/null 2>&1 || true

log "Installing"
adb install -r -g "$APK" || exit 1
sh dumpsys deviceidle whitelist +$PKG >/dev/null
write_prefs
still
uncovered
emu sensor set light 400

# --- 1. Pickpocket with evidence ------------------------------------------------------------------

log "Scenario 1: pickpocket, intruder photos, history"
reset_log
arm "arming from the app"
screen_off
expect_log "lock check: locked=true" 15 "lock check passes with an instant lock" # first screen-off after launch is slow on CI
reset_log
pocket
expect_phase IN_POCKET 6 "pocketed phone becomes protected"
calm_down
reset_log
pull_out
expect_phase WARNING 5 "pulled out while locked starts the warning"
expect_phase SIREN 19 "no unlock within grace starts the siren"
expect_log "intruder photos saved: [12]" 15 "front camera photographed the intruder"
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "owner unlock stops the siren and re-arms"
# The phase is logged before the history is saved, so give the write a moment.
history() { adb shell run-as $PKG cat files/incidents.json 2>/dev/null; }
for _ in $(seq 1 20); do grep -q '"ownerStopped":true' <<<"$(history)" && break; sleep 0.25; done
HISTORY=$(history)
expect_true 'grep -q "\"escalated\":true" <<<"$HISTORY"' "history records the alarm"
expect_true 'grep -q "\"ownerStopped\":true" <<<"$HISTORY"' "history records the owner's unlock"
expect_true 'grep -q "\.jpg" <<<"$HISTORY"' "history links the intruder photos"
PHOTOS=$(adb shell run-as $PKG ls files/intruders 2>/dev/null | grep -c jpg)
expect_true '[ "${PHOTOS:-0}" -ge 1 ]' "photo files exist on disk ($PHOTOS)"
sleep 2

# --- 2. The field bug: light sensor asleep with the screen off ------------------------------------

log "Scenario 2: light sensor frozen (no readings while pocketed), motion must still catch it"
screen_off
reset_log
covered # no light change at all
expect_phase IN_POCKET 6 "pocketed with a frozen light sensor"
calm_down
reset_log
jolt &
uncovered
wait
expect_phase WARNING 5 "pull-out caught by motion alone"
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "unlock during the warning stops it"
sleep 2

# --- 3. False alarms ------------------------------------------------------------------------------

log "Scenario 3: owner pulls it out and unlocks in time"
screen_off
reset_log
pocket
expect_phase IN_POCKET 6 "pocketed again"
calm_down
reset_log
pull_out
expect_phase WARNING 5 "owner's own pull-out warns first"
unlock
expect_phase WAITING_FOR_POCKET 4 "owner's unlock within grace stops it"
expect_no_phase SIREN 9 "no siren when the owner unlocks in time"

log "Scenario 4: sensor uncovered without movement or light"
screen_off
reset_log
pocket
expect_phase IN_POCKET 6 "pocketed for the flicker test"
calm_down
reset_log
uncovered
expect_no_phase WARNING 3 "uncovering without movement or light is ignored"

log "Scenario 5: walking, pocket fabric flickers over the sensor for under a second"
covered
sleep 2
reset_log
jolt &
uncovered
sleep 0.3
covered
wait
expect_no_phase WARNING 4 "brief flicker while walking is ignored"

# --- 4. Tamper triggers ----------------------------------------------------------------------------

log "Scenario 6: airplane mode while locked"
uncovered
emu sensor set light 400
sleep 1.5
unlock # clears the warning the uncover above starts
screen_off
reset_log
sh cmd connectivity airplane-mode enable
expect_log "phase=SIREN .*trigger=AIRPLANE_MODE" 8 "airplane mode on the lock screen sirens"
sh cmd connectivity airplane-mode disable
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "unlock after the airplane alarm"

log "Scenario 7: charger unplugged while locked"
sh dumpsys battery set ac 1
sleep 2
screen_off
reset_log
sh dumpsys battery unplug
expect_log "phase=WARNING .*trigger=CHARGER_UNPLUGGED" 8 "unplugging the charger while locked warns"
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "unlock after the charger warning"
sh dumpsys battery reset

# --- 5. Setup requirement: the phone must lock immediately ----------------------------------------

log "Scenario 8: lock check catches a lock delay, keeps the failure, clears after Fix"
LOCK_NOTIF='NotificationRecord\(0x[0-9a-f]+: pkg=com\.taiyeb\.pocketguard user=[^ ]+ id=4 '
lock_warning_shown() { sh dumpsys notification --noredact | grep -qE "$LOCK_NOTIF"; }
app_prefs() { adb shell run-as $PKG cat /data/user_de/0/$PKG/shared_prefs/pocket_guard.xml 2>/dev/null; }
# The power button locks at once whatever the delay; a lock delay only applies when the screen
# times out, so let it time out (10 s is the platform minimum).
sh settings put secure lock_screen_lock_after_timeout 30000
sh settings put global stay_on_while_plugged_in 0
sh settings put secure screensaver_enabled 0
sh settings put system screen_off_timeout 10000
sh input keyevent KEYCODE_WAKEUP
sleep 1
reset_log
expect_log "lock check: locked=false" 25 "a 30 s lock delay is caught when the screen times out"
sleep 1
expect_true lock_warning_shown "lock warning notification is shown"
sh settings put system screen_off_timeout 600000
sh input keyevent KEYCODE_WAKEUP; sleep 1
reset_log
screen_off
expect_log "lock check: locked=true" 6 "a power-button lock is checked too"
expect_true lock_warning_shown "a power-button pass doesn't hide the timeout failure"
expect_true 'app_prefs | grep -q "lock_check\">FAILED"' "the failure stays stored"
sh settings put secure lock_screen_lock_after_timeout 0
unlock
# Same flags as the warning notification: reaches the running app through onNewIntent.
sh am start -W --activity-clear-top --activity-single-top -n $PKG/.MainActivity --ez fix_lock true >/dev/null
sleep 2
expect_true '! lock_warning_shown' "tapping Fix clears the warning and starts the test over"
sh input keyevent KEYCODE_HOME; sleep 1
reset_log
screen_off
expect_log "lock check: locked=true" 6 "immediate lock passes the new test"
sleep 1
expect_true 'app_prefs | grep -q "lock_check\">PASSED"' "the pass is stored"
expect_true '! lock_warning_shown' "no lock warning after the pass"
unlock

# --- 6. Forced restart during an alarm --------------------------------------------------------------

log "Scenario 9: phone restarted during an alarm"
screen_off
reset_log
sh cmd connectivity airplane-mode enable
expect_phase SIREN 8 "alarm running before the restart"
sh cmd connectivity airplane-mode disable
sleep 1
app_log >>"$FULL_LOG"
adb reboot
adb wait-for-device
for _ in $(seq 1 120); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 1
done
sleep 5
expect_log "phase=SIREN .*trigger=RESTARTED" 30 "alarm resumes after the restart, before the first unlock"
reset_log
unlock
expect_phase WAITING_FOR_POCKET 10 "first unlock after the restart stops it"

# --- 7. UI ------------------------------------------------------------------------------------------

log "Scenario 10: UI screens, screenshots and monkey fuzzing for crashes"
SCREENS="$OUT/screens"
mkdir -p "$SCREENS"
read -r W H < <(sh wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -n 1 | tr 'x' ' ')
# Retries when the capture is nearly empty (caught mid-transition on a slow emulator).
shot() {
  local file="$SCREENS/$1.png"
  for _ in 1 2 3 4; do
    sleep 2
    adb exec-out screencap -p >"$file"
    [ "$(stat -c %s "$file")" -gt 10240 ] && return
  done
}
# -S restarts the app, since a running task would only be brought to the front, ignoring the extras.
open_tab() { sh am start -S -W -n $PKG/.MainActivity --es tab "$1" "${@:2}" >/dev/null; }
swipe() { sh input swipe $((W * $1 / 100)) $((H * $2 / 100)) $((W * $3 / 100)) $((H * $4 / 100)) 400; }
adb logcat -c
write_prefs false
sh am start -W -n $PKG/.MainActivity >/dev/null
shot 1-onboarding
swipe 85 60 15 60
shot 2-onboarding-arm
write_prefs
open_tab home
shot 3-home-off
open_tab triggers
shot 5-triggers
open_tab history
shot 6-history
open_tab settings
shot 7-settings
swipe 50 80 50 25
shot 8-settings-checklist
open_tab home --ez arm true
shot 4-home-armed
screen_off
sh cmd connectivity airplane-mode enable
sleep 3
sh input keyevent KEYCODE_WAKEUP
shot 9-alarm
sh cmd connectivity airplane-mode disable
unlock
SHOTS=$(find "$SCREENS" -name "*.png" -size +10k | wc -l)
expect_true '[ "$SHOTS" -eq 9 ]' "every screen rendered for a screenshot ($SHOTS of 9)"
UI_CRASHES=$(adb logcat -d -b crash 2>/dev/null)
expect_true '! grep -q "$PKG" <<<"$UI_CRASHES"' "no crash opening the onboarding, every tab and the alarm screen"
sh am force-stop $PKG
sleep 1
sh am start -W -n $PKG/.MainActivity >/dev/null
sleep 3
adb logcat -c
sh monkey -p $PKG --throttle 150 --pct-syskeys 0 --pct-appswitch 0 --ignore-security-exceptions -v 500 >"$OUT/monkey.txt" 2>&1 || true
sleep 2
CRASHES=$(adb logcat -d -b crash 2>/dev/null; adb logcat -d | grep -A 30 "FATAL EXCEPTION" || true)
if grep -q "$PKG" <<<"$CRASHES"; then
  fail "app crashed during the monkey run"
  grep -B 2 -A 30 "$PKG" <<<"$CRASHES" | head -n 80
else
  pass "no crashes during the monkey run"
fi
if grep -q "// CRASH: $PKG\|ANR in $PKG" "$OUT/monkey.txt"; then
  fail "monkey reported a crash or ANR"
  grep -A 20 "CRASH\|ANR" "$OUT/monkey.txt" | head -n 60
fi

app_log >>"$FULL_LOG"
log "Result: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
