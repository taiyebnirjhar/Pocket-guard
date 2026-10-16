#!/usr/bin/env bash
# End-to-end test on an emulator: fakes the proximity, light and motion sensors and system
# events with adb, and checks the phase transitions the app logs ("PocketGuard: phase=...").
set -uo pipefail

APK="$1"
PKG=com.taiyeb.pocketguard
FAILED=0
PASSED=0

log() { echo "[e2e] $*"; }
emu() { adb emu "$@" >/dev/null; }
sh() { adb shell "$@"; }

# Waits until the app logs phase=$1 (since the last logcat clear), up to $2 seconds.
expect_phase() {
  local phase="$1" timeout="$2" name="$3"
  for _ in $(seq 1 $((timeout * 4))); do
    if adb logcat -d -s PocketGuard:I | grep -q "phase=$phase "; then
      log "PASS: $name"
      PASSED=$((PASSED + 1))
      return 0
    fi
    sleep 0.25
  done
  log "FAIL: $name (no phase=$phase within ${timeout}s)"
  adb logcat -d -s PocketGuard:I | tail -n 10
  FAILED=$((FAILED + 1))
  return 1
}

# Asserts the app does NOT log phase=$1 within $2 seconds.
expect_no_phase() {
  local phase="$1" seconds="$2" name="$3"
  sleep "$seconds"
  if adb logcat -d -s PocketGuard:I | grep -q "phase=$phase "; then
    log "FAIL: $name (unexpected phase=$phase)"
    adb logcat -d -s PocketGuard:I | tail -n 10
    FAILED=$((FAILED + 1))
  else
    log "PASS: $name"
    PASSED=$((PASSED + 1))
  fi
}

reset_log() { adb logcat -c; }
screen_off() { sh input keyevent KEYCODE_SLEEP; sleep 1; }
unlock() { sh input keyevent KEYCODE_WAKEUP; sleep 0.5; sh wm dismiss-keyguard; sleep 1; }
still() { emu sensor set acceleration 0:9.81:0; }
pocket() { emu sensor set light 0; emu sensor set proximity 0; }
pull_out() { emu sensor set acceleration 6:6:18; emu sensor set proximity 10; emu sensor set light 400; sleep 0.3; still; }
calm_down() { still; sleep 2.5; } # let the motion window (2 s) expire

log "Installing"
adb install -r -g "$APK" || exit 1
sh dumpsys deviceidle whitelist +$PKG >/dev/null
sh settings put global window_animation_scale 0
sh settings put global transition_animation_scale 0
sh cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
still
emu sensor set proximity 10
emu sensor set light 400

log "Scenario 1: pickpocket"
reset_log
sh am start -W -n $PKG/.MainActivity --ez arm true >/dev/null
expect_phase WAITING_FOR_POCKET 10 "arming from the app"
screen_off
pocket
expect_phase IN_POCKET 6 "pocketed phone becomes protected"
calm_down
reset_log
pull_out
expect_phase WARNING 5 "pulled out while locked starts the warning"
expect_phase SIREN 8 "no unlock within grace starts the siren"
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "owner unlock stops the siren and re-arms"
sleep 2

log "Scenario 2: owner takes it out and unlocks"
screen_off
reset_log
pocket
expect_phase IN_POCKET 6 "pocketed again"
calm_down
unlock
reset_log
emu sensor set proximity 10
emu sensor set light 400
expect_no_phase WARNING 4 "unlocked removal is not an alarm"

log "Scenario 3: sensor flicker without motion or light"
emu sensor set proximity 10
sleep 1
screen_off
reset_log
pocket
expect_phase IN_POCKET 6 "pocketed for flicker test"
calm_down
reset_log
emu sensor set proximity 10
expect_no_phase WARNING 3 "uncovering without movement or light is ignored"

log "Scenario 4: airplane mode while locked"
screen_off
reset_log
sh cmd connectivity airplane-mode enable
expect_phase SIREN 8 "airplane mode on the lock screen sirens"
sh cmd connectivity airplane-mode disable
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "unlock after airplane alarm"

log "Scenario 5: charger unplugged while locked"
sh dumpsys battery set ac 1
sleep 1
screen_off
reset_log
sh dumpsys battery unplug
expect_phase WARNING 6 "unplugging the charger while locked warns"
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "unlock after charger warning"
sh dumpsys battery reset

log "Scenario 6: UI screens and monkey fuzzing for crashes"
sh am force-stop $PKG
sleep 1
sh am start -W -n $PKG/.MainActivity >/dev/null
sleep 3
adb logcat -c
sh monkey -p $PKG --throttle 150 --pct-syskeys 0 --pct-appswitch 0 --ignore-security-exceptions -v 400 >/tmp/monkey.txt 2>&1 || true
sleep 2
CRASHES=$(adb logcat -d -b crash 2>/dev/null; adb logcat -d | grep -A 30 "FATAL EXCEPTION" || true)
if echo "$CRASHES" | grep -q "$PKG"; then
  log "FAIL: app crashed"
  echo "$CRASHES" | grep -B 2 -A 30 "$PKG" | head -n 80
  FAILED=$((FAILED + 1))
else
  log "PASS: no crashes during monkey run"
  PASSED=$((PASSED + 1))
fi
if grep -q "// CRASH: $PKG\|ANR in $PKG" /tmp/monkey.txt; then
  log "FAIL: monkey reported a crash or ANR"
  grep -A 20 "CRASH\|ANR" /tmp/monkey.txt | head -n 60
  FAILED=$((FAILED + 1))
fi

log "Result: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
