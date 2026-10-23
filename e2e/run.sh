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
# Pocketed, then locked. The emulator can deliver the proximity change after the screen goes off,
# and an uncovered phone lying still when locked is (rightly) on a table, so wait until the app
# has seen the cover (after a reset_log) before pressing power.
pocket_and_lock() {
  pocket
  wait_for_cover
  screen_off
}
# Up to 6 s (slow 2-core runners): said in the log when it never came, so a wrong phase later is
# easy to trace back to it.
wait_for_cover() {
  for _ in $(seq 1 30); do app_log | grep -q "log: covered" && return 0; sleep 0.2; done
  log "  (the app never logged the cover before the lock)"
}

# Settings are written straight into the app's device-protected storage (debug build: run-as).
# Grace is 15 s so the scripted PIN entry (several seconds on CI) fits inside it.
write_prefs() {
  local onboarded="${1:-true}"
  cat >"$OUT/prefs.xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="onboarded" value="$onboarded" />
    <int name="grace_seconds" value="15" />
    ${EXTRA_PREFS:-}
</map>
EOF
  sh am force-stop $PKG
  adb push "$OUT/prefs.xml" /data/local/tmp/pg-prefs.xml >/dev/null
  sh chmod 644 /data/local/tmp/pg-prefs.xml
  adb shell "run-as $PKG sh -c 'mkdir -p /data/user_de/0/$PKG/shared_prefs && cp /data/local/tmp/pg-prefs.xml /data/user_de/0/$PKG/shared_prefs/pocket_guard.xml'"
}

# The online part always uses the local backend from e2e/backend.sh (never the real one); without
# it, an empty address switches the online part off.
E2E_API_URL="${E2E_API_URL:-}"
E2E_PUBLISHABLE_KEY="${E2E_PUBLISHABLE_KEY:-}"
LIVE=false
[ -n "$E2E_API_URL" ] && [ -n "$E2E_PUBLISHABLE_KEY" ] && LIVE=true
EMU_API="${E2E_API_URL/127.0.0.1/10.0.2.2}"
write_backend() {
  local url=""
  [ "$LIVE" = true ] && url="$EMU_API"
  cat >"$OUT/backend.xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="url_override">$url</string>
    <string name="key_override">$E2E_PUBLISHABLE_KEY</string>
</map>
EOF
  adb push "$OUT/backend.xml" /data/local/tmp/pg-backend.xml >/dev/null
  sh chmod 644 /data/local/tmp/pg-backend.xml
  adb shell "run-as $PKG sh -c 'mkdir -p /data/user_de/0/$PKG/shared_prefs && cp /data/local/tmp/pg-backend.xml /data/user_de/0/$PKG/shared_prefs/backend.xml'"
}
view_incident() {
  curl -s -X POST "$E2E_API_URL/rest/v1/rpc/view_incident" -H "apikey: $E2E_PUBLISHABLE_KEY" \
    -H "Content-Type: application/json" -d "{\"token\":\"$1\"}"
}

# The app's own actions (arm, fix the lock) go through a non-exported alias, which only the app's
# notifications and tile can start; root (adb root, below) may start it too.
OWNER="$PKG/.OwnerAction"
arm() {
  sh am start -W -n $OWNER --ez arm true >/dev/null
  expect_phase WAITING_FOR_POCKET 10 "$1"
}

# --- Device setup -------------------------------------------------------------------------------

log "Setup: root (for the app's non-exported actions and real key presses), PIN lock that locks instantly"
adb root >/dev/null 2>&1; sleep 2; adb wait-for-device
sh locksettings set-pin "$PIN" >/dev/null
sh settings put secure lock_screen_lock_after_timeout 0
sh settings put system screen_off_timeout 600000
sh cmd connectivity airplane-mode disable >/dev/null 2>&1 || true

log "Installing"
adb install -r -g "$APK" || exit 1
sh dumpsys deviceidle whitelist +$PKG >/dev/null
write_prefs
write_backend
log "Online part: $([ "$LIVE" = true ] && echo "local backend at $EMU_API" || echo "off (no local backend)")"
# On CI the online checks must run: skipping them quietly would still read as a pass.
if [ "${CI:-}" = true ] && [ "$LIVE" != true ]; then fail "the local backend isn't configured (E2E_API_URL, E2E_PUBLISHABLE_KEY)"; fi
still
uncovered
emu sensor set light 400

# --- 1. Pickpocket with evidence ------------------------------------------------------------------

log "Scenario 1: pickpocket, intruder photos, history"
# Slower runners (private repositories: 2 cores) can reach this point with the screen already
# timed out and locked behind the new PIN; this scenario starts from an unlocked phone.
sh input keyevent KEYCODE_WAKEUP; sleep 0.7
if keyguard_showing; then unlock; fi
reset_log
arm "arming from the app"
pocket
expect_log "not protecting yet" 6 "pocketed while unlocked: waits for the lock"
expect_no_phase IN_POCKET 1 "not protected while unlocked"
screen_off
expect_phase IN_POCKET 2 "power pressed: protected at once"
expect_log "lock check: locked=true" 15 "lock check passes with an instant lock" # first screen-off after launch is slow on CI
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
reset_log
covered # no light change at all
wait_for_cover
screen_off
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
reset_log
pocket_and_lock
expect_phase IN_POCKET 6 "pocketed again"
calm_down
reset_log
pull_out
expect_phase WARNING 5 "owner's own pull-out warns first"
unlock
expect_phase WAITING_FOR_POCKET 4 "owner's unlock within grace stops it"
expect_no_phase SIREN 12 "no siren when the owner unlocks in time"

log "Scenario 4: uncovered with no movement or light (a careful pull) still warns"
reset_log
pocket_and_lock
expect_phase IN_POCKET 6 "pocketed for the careful pull"
calm_down
reset_log
uncovered
expect_log "phase=WARNING .*evidence=UNCOVERED" 5 "uncovered while locked warns, no proof needed"
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "unlock after the careful-pull warning"

log "Scenario 5: walking, pocket fabric flickers over the sensor for under a second"
reset_log
pocket_and_lock
expect_phase IN_POCKET 6 "pocketed for the flicker test"
calm_down
reset_log
jolt &
uncovered
sleep 0.3
covered
wait
# Longer than the 15 s grace: a warning the flicker wrongly left running would siren by then.
expect_no_phase SIREN 17 "brief flicker while walking never sirens"

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

# --- 5. Motion: tables and gentle pulls -----------------------------------------------------------

face_down() { emu sensor set acceleration 0:0:-9.81; }
face_up() { emu sensor set acceleration 0:0:9.81; }
tilted_off_table() { emu sensor set acceleration 0:5:-8.45; } # 31 degrees, still 1 g

log "Scenario 7b: face-down on a table, lifted, put back, then taken"
face_down
sleep 1
reset_log
covered
sleep 1 # Let the reading reach the app first: covered at the lock means a pocket, not a table.
screen_off
expect_phase IN_POCKET 2 "laid face-down and locked: protected at once"
expect_phase ON_TABLE 25 "lying still face-down: table protection"
reset_log
tilted_off_table
expect_log "phase=WARNING .*trigger=TABLE_MOVED" 5 "tilting it up off the table warns (sensor still covered)"
face_down
expect_log "phase=ON_TABLE from=WARNING" 8 "put back down: the warning stops by itself"
# Longer than the 15 s grace: a siren timer the put-back failed to cancel would fire by then.
expect_no_phase SIREN 17 "no siren after putting it back"
reset_log
uncovered
tilted_off_table
expect_log "phase=WARNING .*trigger=TABLE_MOVED" 5 "picked up off the table warns"
expect_phase SIREN 20 "not put back and no unlock: siren"
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "unlock after the table alarm"
still

log "Scenario 7d: face-up on a table, locked: protected at once, picked up warns"
face_up
uncovered
sleep 1
reset_log
screen_off
expect_log "phase=ON_TABLE " 4 "locked, black and lying face-up: table protection at once"
reset_log
emu sensor set acceleration 0:5:8.45 # Tilted up 31 degrees off the table.
expect_log "phase=WARNING .*trigger=TABLE_MOVED" 5 "picked up face-up warns"
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "unlock after the face-up warning"
still

log "Scenario 7c: gentle pull with no jolt and no light, turned to look"
reset_log
pocket_and_lock
expect_phase IN_POCKET 6 "pocketed for the gentle pull"
calm_down
reset_log
uncovered
sleep 0.3
face_up # Turned to look, slowly: |a| stays 1 g, so there is no jolt.
expect_log "phase=WARNING .*evidence=UNCOVERED" 6 "a gentle pull warns without any jolt or light"
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "unlock after the gentle-pull warning"
still
uncovered
sleep 2 # The new pose settles before locking: lying still and locked, it guards the table.

log "Scenario 7e: SOS: screen woken, then volume up tapped 4 times"
A11Y="$PKG/.SosAccessibilityService"
# Real key events written to the input device that owns the volume keys (root, emulator only):
# accessibility key filters never see keys injected with "adb shell input" (those count as
# already filtered), so the test must press them the way the hardware does. With the screen off
# Android shows volume keys to no app at all, so the gesture starts with power to wake the screen.
adb root >/dev/null 2>&1; sleep 2; adb wait-for-device
KEYDEV=$(adb shell getevent -pl 2>/dev/null | tr -d '\r' | awk '/add device/ { d = $4 } /KEY_VOLUMEUP/ { print d; exit }')
hw_key() {
  local code
  case "$1" in KEY_VOLUMEUP) code=115 ;; KEY_VOLUMEDOWN) code=114 ;; esac
  adb shell "sendevent $KEYDEV 1 $code $2; sendevent $KEYDEV 0 0 0" >/dev/null 2>&1
}
tap_up() {
  local i
  for i in $(seq 1 "$1"); do hw_key KEY_VOLUMEUP 1; sleep 0.05; hw_key KEY_VOLUMEUP 0; sleep 0.2; done
}
tap_down() {
  local i
  for i in $(seq 1 "$1"); do hw_key KEY_VOLUMEDOWN 1; sleep 0.05; hw_key KEY_VOLUMEDOWN 0; sleep 0.2; done
}
expect_true '[ -n "$KEYDEV" ]' "found the input device with the volume keys ($KEYDEV)"
reset_log
sh settings put secure enabled_accessibility_services "$A11Y"
sh settings put secure accessibility_enabled 1
expect_log "SOS key watcher connected" 10 "the SOS key watcher (accessibility) connects"
screen_off
sh input keyevent KEYCODE_WAKEUP; sleep 1 # Power pressed: the lock screen shows.
reset_log
tap_up 2
expect_no_phase SIREN 3 "two volume taps (turning it up) are not SOS"
reset_log
tap_up 4
expect_log "phase=SIREN .*trigger=SOS" 6 "volume up tapped 4 times on the lock screen sounds the SOS alarm"
expect_log "SOS video recording" 10 "recording starts with the SOS alarm"
sleep 3
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "unlock stops the SOS alarm"
expect_log "SOS video saved" 10 "the recording is saved when it stops"
for _ in $(seq 1 20); do grep -q '\.mp4' <<<"$(history)" && break; sleep 0.25; done
expect_true 'grep -q "\.mp4" <<<"$(history)"' "history links the SOS recording"
VIDEOS=$(adb shell run-as $PKG ls -l files/sos 2>/dev/null | grep -c mp4)
expect_true '[ "${VIDEOS:-0}" -ge 1 ]' "recording files exist on disk ($VIDEOS)"

log "Scenario 7e2: harassed while armed: she grabs the phone from her pocket, then 4 taps turn the theft alarm into SOS"
reset_log
pocket_and_lock
expect_phase IN_POCKET 5 "pocketed and locked: protected"
calm_down
reset_log
pull_out
expect_phase WARNING 5 "pulling it out starts the theft warning (unchanged)"
sh input keyevent KEYCODE_WAKEUP; sleep 1 # Power pressed to see the screen; the warning goes on.
tap_up 4
expect_log "phase=SIREN .*trigger=SOS" 6 "4 volume taps during the theft warning switch it to SOS"
sleep 3
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "unlock stops the SOS alarm"
sleep 2

log "Scenario 7e3: silent SOS: volume down tapped 4 times, nothing to hear or see; volume up makes it loud"
screen_off
sh input keyevent KEYCODE_WAKEUP; sleep 1
reset_log
tap_down 4
expect_log "SOS: volume down tapped 4 times \\(silent\\)" 6 "volume down tapped 4 times on the lock screen starts a silent SOS"
expect_log "phase=SIREN .*trigger=SOS" 6 "the silent SOS is an SOS (alerts and recording as usual)"
expect_log "SOS: silent, no siren or alarm screen" 6 "no siren and no alarm screen"
sleep 2
expect_true '! grep -q "alarm volume" <<<"$(app_log)"' "the siren never started (the alarm volume wasn't raised)"
expect_true '! sh dumpsys window | grep "mCurrentFocus" | grep -q AlarmActivity' "the alarm screen isn't showing"
reset_log
unlock
expect_no_phase WAITING_FOR_POCKET 3 "unlocking doesn't stop a silent SOS"
reset_log
tap_up 4
expect_log "SOS: loud now" 6 "volume up tapped 4 times makes the silent SOS loud"
expect_log "alarm volume" 6 "the siren starts"
screen_off
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "unlock stops it once it is loud"
sleep 2
sh settings delete secure enabled_accessibility_services >/dev/null
sleep 2


log "Scenario 7g: SOS alerts an approved emergency contact by SMS and email, with the live location"
# The emulator's own number receives the SMS; the stand-in for Brevo receives the email. The
# contact list and its approval are written the way the app stores them (the approval covers the
# text version, whether it carries the live link, the wording in the phone's language, the name,
# and the people).
sh am force-stop $PKG
if [ "$LIVE" = true ]; then MODE=live; else MODE=sms; fi
WORDING=$(python3 "$(dirname "$0")/wording_hash.py" app/src/main/res/values/strings.xml)
cat >"$OUT/contacts.xml" <<CONTACTS
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="contacts">[{&quot;name&quot;:&quot;Mom&quot;,&quot;phone&quot;:&quot;+15555215554&quot;,&quot;email&quot;:&quot;mom@example.com&quot;}]</string>
    <string name="name">Taiyeb</string>
    <string name="approved">3|$MODE|$WORDING|Taiyeb|Mom=+15555215554,mom@example.com</string>
</map>
CONTACTS
adb push "$OUT/contacts.xml" /data/local/tmp/pg-contacts.xml >/dev/null
sh chmod 644 /data/local/tmp/pg-contacts.xml
adb shell "run-as $PKG sh -c 'cp /data/local/tmp/pg-contacts.xml /data/user_de/0/$PKG/shared_prefs/emergency_contacts.xml'"
emu geo fix 90.4125 23.8103
reset_log
arm "re-armed with an approved contact (location declared for SOS)"
sleep 2
sh input keyevent KEYCODE_HOME
screen_off
emu geo fix 90.4125 23.8103
reset_log
sh am startservice -n "$PKG/.GuardService" -a com.taiyeb.pocketguard.SOS >/dev/null
expect_log "SOS alerts: 1 contacts in 5 s" 6 "SOS starts the alert countdown for the approved contact"
sleep 2
expect_true '! app_log | grep -q "SMS sent"' "no SMS during the countdown"
expect_log "SOS alerts: SMS sent to 1 of 1" 12 "after the countdown the SMS goes out"
sent_sms() { adb shell content query --uri content://sms/sent --projection address:body 2>/dev/null; }
for _ in $(seq 1 20); do grep -q "Taiyeb needs help" <<<"$(sent_sms)" && break; sleep 0.5; done
expect_true 'grep -q "Taiyeb needs help" <<<"$(sent_sms)"' "the SMS names the owner"
expect_true 'grep -q "maps.google.com/?q=23.81" <<<"$(sent_sms)"' "the SMS carries the location"
LIVE_TOKEN=""
if [ "$LIVE" = true ]; then
  expect_true 'grep -q "Live location: https://pocket-guard.pages.dev/live/#t=" <<<"$(sent_sms)"' "the SMS carries the live location link"
  LIVE_TOKEN=$(grep -oE '#t=[0-9a-f]{48}' <<<"$(sent_sms)" | tail -n 1 | cut -c4-)
  expect_true 'view_incident "$LIVE_TOKEN" | grep -q "\"status\": *\"active\""' "the live page sees the SOS as active"
  expect_log "Live location: email sent to 1" 8 "the contact with an address gets the alert by email"
  expect_true 'grep -q "mom@example.com" "$OUT/brevo.jsonl" && grep -q "#t=$LIVE_TOKEN" "$OUT/brevo.jsonl"' "the email goes to the approved address, with the same live link"
fi
# Unlocked within 15 s of the alert, so it counts as a false alarm.
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "unlock stops SOS"
expect_log "sending false alarm" 4 "stopped right after the alert: a false-alarm SMS follows"
if [ "$LIVE" = true ]; then
  expect_log "Live location: stopped" 10 "the live share ends with SOS (queued points uploaded first)"
  echo "live view: $(view_incident "$LIVE_TOKEN" | head -c 400)" >>"$FULL_LOG"
  expect_true 'view_incident "$LIVE_TOKEN" | grep -qE "\[23\.81[0-9]*, *90\.41"' "the live page has the phone's position"
  expect_true 'view_incident "$LIVE_TOKEN" | grep -q "\"status\": *\"false_alarm\""' "the live page shows the false alarm"
  for _ in $(seq 1 20); do [ "$(wc -l <"$OUT/brevo.jsonl")" -ge 2 ] && break; sleep 0.5; done
  expect_true 'tail -n 1 "$OUT/brevo.jsonl" | grep -q "false alarm"' "the emailed contact hears it was a false alarm"
  # The recording's last piece is saved on unlock and still goes up after SOS has ended.
  expect_log "Live location: video uploaded" 60 "the SOS video is uploaded (after SOS ended, too)"
  MEDIA_ID=$(view_incident "$LIVE_TOKEN" | python3 -c 'import sys,json;m=json.load(sys.stdin)["media"];print(m[0][0] if m else "")')
  expect_true '[ -n "$MEDIA_ID" ]' "the live page lists the recording"
  VIDEO_URL=$(curl -s -X POST "$E2E_API_URL/functions/v1/media" -H "apikey: $E2E_PUBLISHABLE_KEY" -H "Content-Type: application/json" \
    -d "{\"action\":\"view\",\"token\":\"$LIVE_TOKEN\",\"media_id\":${MEDIA_ID:-0}}" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("url",""))')
  curl -s -o "$OUT/uploaded.mp4" "$VIDEO_URL"
  expect_true '[ "$(stat -c %s "$OUT/uploaded.mp4" 2>/dev/null || echo 0)" -gt 10000 ] && head -c 12 "$OUT/uploaded.mp4" | grep -q ftyp' "the live page's link plays the uploaded video (an MP4, $(stat -c %s "$OUT/uploaded.mp4" 2>/dev/null || echo 0) bytes)"
fi
sleep 2

log "Scenario 7f: SOS from the notification while the phone is unlocked"
# The notification action starts the service with the SOS action (root may start it directly).
reset_log
sh am startservice -n "$PKG/.GuardService" -a com.taiyeb.pocketguard.SOS >/dev/null
expect_log "phase=SIREN .*trigger=SOS" 6 "the notification's SOS action sounds the SOS alarm"
expect_no_phase WAITING_FOR_POCKET 3 "started unlocked, SOS keeps sounding (the unlock it began in doesn't stop it)"
screen_off
reset_log
unlock
expect_phase WAITING_FOR_POCKET 6 "lock and unlock stops it"
sleep 2

# --- 6. Setup requirement: the phone must lock immediately ----------------------------------------

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
sh am start -W --activity-clear-top --activity-single-top -n $OWNER --ez fix_lock true >/dev/null
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

# --- 7. Forced restart during an alarm --------------------------------------------------------------

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

# --- 9b. Arming schedule -----------------------------------------------------------------------------

log "Scenario 9b: an arming schedule arms the guard by itself; a pause holds it off"
# One window covering every whole day (start = end), so it's "inside" in any time zone. Opening
# the app catches up on the schedule, as after a missed alarm.
ALL_DAY='<boolean name="schedule_on" value="true" /><string name="schedule">[{&quot;days&quot;:[1,2,3,4,5,6,7],&quot;start&quot;:&quot;00:00&quot;,&quot;end&quot;:&quot;00:00&quot;}]</string>'
NOW_MS=$(( $(sh date +%s | tr -d '\r') * 1000 ))
open_app_unlocked() {
  sh input keyevent KEYCODE_WAKEUP; sleep 0.7
  if keyguard_showing; then unlock; fi
  sh am start -W -n $PKG/.MainActivity >/dev/null
}
EXTRA_PREFS="$ALL_DAY<long name=\"schedule_paused_until\" value=\"$((NOW_MS + 600000))\" />" write_prefs
reset_log
open_app_unlocked
expect_no_phase WAITING_FOR_POCKET 6 "a paused schedule leaves the guard off"
expect_true 'sh dumpsys notification --noredact | grep -q "arms again at"' "the pause says when it arms again"
EXTRA_PREFS="$ALL_DAY<long name=\"schedule_paused_until\" value=\"$((NOW_MS - 1000))\" />" write_prefs
reset_log
open_app_unlocked
expect_phase WAITING_FOR_POCKET 10 "inside its window the schedule arms the guard by itself"
expect_true 'sh dumpsys notification --noredact | grep -q "Armed by your schedule"' "it says the schedule armed it"
write_prefs # Schedule off again for the rest.

# --- 8. UI ------------------------------------------------------------------------------------------

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
open_owner_tab() { sh am start -S -W -n $OWNER --es tab "$1" "${@:2}" >/dev/null; }
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
open_tab theft
shot 5-theft
open_tab sos
shot 5b-sos
open_tab history
shot 6-history
open_tab setup
shot 10-setup
open_tab settings
shot 7-settings
swipe 50 80 50 25
shot 8-settings-checklist
open_owner_tab home --ez arm true
shot 4-home-armed
screen_off
sh cmd connectivity airplane-mode enable
sleep 3
sh input keyevent KEYCODE_WAKEUP
shot 9-alarm
sh cmd connectivity airplane-mode disable
unlock
SHOTS=$(find "$SCREENS" -name "*.png" -size +10k | wc -l)
expect_true '[ "$SHOTS" -eq 11 ]' "every screen rendered for a screenshot ($SHOTS of 11)"
UI_CRASHES=$(adb logcat -d -b crash 2>/dev/null)
expect_true '! grep -q "$PKG" <<<"$UI_CRASHES"' "no crash opening the onboarding, setup, every tab and the alarm screen"
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

# --- 11. Live location page -------------------------------------------------------------------------

# The page contacts open, rendered in the runner's Chrome against the local backend, for the
# incident from scenario 7g (still viewable for a day after it ended).
if [ "$LIVE" = true ] && [ -n "$LIVE_TOKEN" ] && command -v google-chrome >/dev/null; then
  log "Scenario 11: the live location page"
  rm -rf "$OUT/site" && cp -r site "$OUT/site"
  printf 'window.POCKET_GUARD = { url: "%s", key: "%s" };\n' "$E2E_API_URL" "$E2E_PUBLISHABLE_KEY" >"$OUT/site/live/config.js"
  (cd "$OUT/site" && exec python3 -m http.server 8088 >/dev/null 2>&1) &
  SITE_PID=$!
  sleep 1
  PAGE="http://localhost:8088/live/#t=$LIVE_TOKEN"
  google-chrome --headless=new --no-sandbox --hide-scrollbars --window-size=412,860 --force-device-scale-factor=2 \
    --virtual-time-budget=15000 --screenshot="$OUT/screens/11-live-page.png" "$PAGE" >/dev/null 2>&1
  google-chrome --headless=new --no-sandbox --virtual-time-budget=15000 --dump-dom "$PAGE" >"$OUT/live-page.html" 2>/dev/null
  kill $SITE_PID 2>/dev/null
  expect_true 'grep -q "Taiyeb.s location" "$OUT/live-page.html"' "the live page shows whose location it is"
  expect_true 'grep -q "FALSE ALARM" "$OUT/live-page.html"' "the live page shows the false alarm"
  expect_true '[ "$(stat -c %s "$OUT/screens/11-live-page.png" 2>/dev/null || echo 0)" -gt 10240 ]' "the live page rendered for a screenshot"
elif [ "${CI:-}" = true ]; then
  fail "the live page wasn't checked (no live incident from scenario 7g, or no Chrome on the runner)"
fi

app_log >>"$FULL_LOG"
log "Result: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
