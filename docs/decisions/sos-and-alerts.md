# SOS and alerts decisions

The SOS gesture, the SOS alarm and recording, emergency contacts, SMS, email and the live link.
Oldest first.

### SOS for harassment in crowds
- **Date / version:** 2026-10-17
- **Decision:** Add an SOS mode with its own loud voice alarm, usable whenever armed, triggered by a
  button combo that doesn't clash with the system.
- **Why:** Owner, for women harassed in crowds.
- **Status:** current. The gesture changed several times (below).

### SOS alarm sound and screen
- **Date / version:** 2026-10-17, v1.4.0 code (shown from v1.5.0)
- **Decision:** A 3 kHz whistle, with a spoken "Help! Stop it!" in its pauses (Bangla when the app
  is in Bangla and a voice exists), full volume through silent mode, "I NEED HELP" on screen. Fires
  at once with no warning. Unlock stops it. Saved in History.
- **Why:** A theft siren says "stolen"; harassment needs a call for help.
- **Status:** current.

### SOS gesture v1: volume up 5 times in 3 s
- **Date / version:** 2026-10-17, v1.4.0 (shipped hidden)
- **Decision:** Volume up pressed 5 times within 3 s, read through a MediaSession / volume-level trick.
- **Rejected alternatives:** Holding both volume keys (Android's accessibility shortcut). Volume
  down (double press opens the camera on Xiaomi).
- **Status:** superseded by the v1.5.0 two-key hold. Couldn't hear presses while music played.

### SOS gesture v2: power, then hold + and − for 3 s
- **Date / version:** 2026-10-17, v1.5.0 (PR #3)
- **Decision:** Wake the screen, then hold both volume keys 3 s, seen by an opt-in Accessibility key
  watcher with a disclosure first. Setup warns if the system shortcut is assigned.
- **Why:** Owner asked for a both-keys hold of "3-5" s. Rarely accidental.
- **Rejected alternatives:** Screen-off volume hold (Android gives volume keys to no app with the
  screen off; MediaSession gets none on 13+). Silent-audio + volume-reset fallback (Play device
  interference policy; didn't work screen-off) — deleted from all builds. Shake or squeeze
  (false alarms in crowds).
- **Status:** superseded by 4 taps in v2.2.0.

### SOS records video with sound
- **Date / version:** 2026-10-17, v1.5.0
- **Decision:** On SOS, record from the back camera with the microphone (in a pocket, sound is the
  evidence) in 1-minute pieces, up to 15 minutes, until unlock. Stored in app-private storage.
  Notice that recording without consent is illegal in some places. Mic permission asked when SOS
  is turned on.
- **Why:** Owner: "start recording video immediately". Pieces keep everything up to a grab.
- **Status:** current. Local limits from v2.0.0: stops under 300 MB free; 1 GB total, oldest pruned.

### SOS without Accessibility: notification, tile, Home
- **Date / version:** 2026-10-17, v1.6.0 (PR #4)
- **Decision:** Add an SOS action on the lock-screen notification, an SOS Quick Settings tile, an SOS
  card on Home, and a setup tip pointing to Android's own Emergency SOS (power ×5).
- **Why:** Play policy risk on Accessibility; backups when the key watcher is off.
- **Status:** current.

### Safety network at zero running cost
- **Date / version:** 2026-10-17
- **Decision:** When SOS fires, trusted people learn at once, see the location and get evidence,
  with no running cost. Each layer works without the later ones: no internet → SMS with GPS still
  goes; backend down → the phone keeps everything.
- **Why:** Owner's wife's idea (contacts, live location, media, 90-day deletion); owner wants
  "free and 0 infra cost".
- **Status:** current.

### WhatsApp only as a share button
- **Date / version:** 2026-10-17, v1.7.0
- **Decision:** "Share on WhatsApp" opens WhatsApp prefilled; the user taps Send.
- **Why:** Automatic WhatsApp needs the paid Business API.
- **Status:** current.

### Emergency contacts with approved wording and a countdown
- **Date / version:** 2026-10-17, v1.7.0 (PR #5)
- **Decision:** Up to 5 contacts, picked without the contacts permission or typed. The user
  approves the exact messages and recipients ("Send a test" available). On SOS, the alarm and
  recording start at once; messages wait for a 5 s countdown with Cancel. Any change to contacts,
  settings or wording needs re-approval; Home shows when alerts are off for that reason.
- **Why:** Play "Message Spam" policy wants user-confirmed content and recipients; the countdown
  also stops false alarms.
- **Status:** current. Since v2.0.0 the approval hashes the resolved wording and shows every
  variant. Numbers need at least 6 digits.

### SMS plan: alert, follow-ups, false alarm
- **Date / version:** 2026-10-17, v1.7.0
- **Decision:** Alert SMS with a Google Maps link. Follow-up on 200 m moved, at most every 5 min,
  max 3. "False alarm, I'm safe" if stopped within 15 s of the alert. Location only during SOS
  (foreground service, while in use). Full build sends by itself; Play build opens the SMS app.
- **Why:** SMS is noticed fastest and works without internet.
- **Status:** current. v2.0.0: failed sends retried up to 5 times; fixes older than 2 min never sent.

### Email as a backup channel
- **Date / version:** 2026-10-17, v1.8.0
- **Decision:** Contacts may have an email. They get the alert and false-alarm emails through Brevo.
  SMS stays primary.
- **Why:** Email can carry links and is free; SMS is noticed faster.
- **Status:** current. Emails follow the app language. Email addresses are stored server-side only
  as hashes.

### Live location link
- **Date / version:** 2026-10-17, v1.8.0 (PR #6)
- **Decision:** The SMS carries a private link to a live map. Location uploads every 15 s during SOS;
  the page refreshes every 10 s; token in the URL fragment; no login for contacts. Link stops working
  1 day after SOS ends; data deleted after 90 days. On by default; switchable in Settings → Online.
- **Why:** Contacts need to follow a moving person.
- **Rejected alternatives:** Link valid 7 days (plan). Sharing for 30 min after unlock with a "Stop
  sharing" button (planned, not built).
- **Status:** current.

### Upload SOS video, not audio only
- **Date / version:** 2026-10-17, v1.9.0 (PR #8)
- **Decision:** With the live link on, record at 480p (about 12 MB/min) and upload each 1-minute
  piece to R2 through a server-signed URL. The live page lists recordings and plays them through
  10-minute links.
- **Why:** Evidence reaches contacts even if the phone is taken.
- **Rejected alternatives:** Audio-only (about 10× smaller) was offered. A photo every 10 s was in
  the plan; intruder photos are never uploaded.
- **Status:** current.

### Anonymous sign-in at approval time
- **Date / version:** by v2.0.0
- **Decision:** Sign in when contacts are approved, not when SOS starts.
- **Why:** SOS must not wait for sign-in; CGNAT and rate limits could block it at the worst time.
- **Status:** current.

### Online retries never give up during SOS
- **Date / version:** 2026-10-17, v2.0.0
- **Decision:** Unlimited retries with backoff min(5 s × attempt, 30 s) while SOS runs. Closing the
  incident and the false-alarm email retry after stop. No SOS email after a false alarm. 429 → retry
  later; 400/404/413/507 → give up.
- **Why:** The old client gave up after about 50 s.
- **Status:** current.

### Local emergency number
- **Date / version:** v2.0.0
- **Decision:** Use the phone's local emergency number instead of a hard-coded "999".
- **Why:** Works outside Bangladesh.
- **Status:** current.

### SMS always in English letters
- **Date / version:** 2026-10-17, v2.1.0 (PR #11)
- **Decision:** SOS SMS is in English or Banglish (picked on the contacts card), with English
  digits, whatever the app language. English SMS strings are `translatable="false"`.
- **Why:** One Bangla letter makes the SMS Unicode (70 chars per part, not 160): about double the cost.
- **Rejected alternatives:** Dropping the Maps link when the live link exists; fewer updates.
- **Status:** current.

### SOS by holding volume up 5 s
- **Date / version:** 2026-10-17, v2.1.0
- **Decision:** Add volume up held 5 s (both keys 3 s still works). Screen must be on.
- **Why:** Owner insisted, after finding the power button unlocks by fingerprint. Claude first
  advised against (single key held in a pocket is easy to press).
- **Status:** superseded by 4 taps in v2.2.0.

### SOS gesture: tap volume up 4 times within 2 s
- **Date / version:** 2026-10-18, v2.2.0 (PR #12)
- **Decision:** Screen on (locked or unlocked), tap volume up 4 times within 2 s. Volume down resets
  the count. Taps don't count while music, video or a call uses audio (ringing too, from v2.3.0),
  except while Pocket Guard's own alarm sounds.
- **Why:** Owner: "very simple, quick and dont conflict to anything else"; "I want to click few times".
- **Rejected alternatives:** Power pressed 5 times (Samsung camera on double press, Emergency SOS on 5,
  fingerprint unlock). Squeezing both keys 1.5 s ("tough"). Holding volume up 2 s or 3 s ("No press").
- **Status:** current.

### Screen must be on for the gesture
- **Date / version:** 2026-10-18, v2.2.0
- **Decision:** Keep the screen-on requirement. For screen-off emergencies, point users to the
  phone's own Emergency SOS.
- **Why:** Android gives no app key events with the screen off; it also guards against pocket presses.
- **Rejected alternatives:** Pretending to be a media player playing silence (battery, fights real
  music, unreliable on Samsung/Xiaomi, Play risk, more pocket false SOS).
- **Status:** current.

### Theft alarm can become SOS
- **Date / version:** 2026-10-18, v2.2.0
- **Decision:** If a harassed owner grabs her own armed phone, the theft warning starts as usual.
  Tapping volume up 4 times or the "🆘 I need help" button (no unlock) turns it into SOS. A thief
  pressing it only helps the owner. If the theft alarm began unlocked, the SOS keeps the theft
  unlock rule.
- **Why:** Owner case; "I dont want to damage our existing stole alarm".
- **Status:** current. v2.2.2 fixed a theft-screen flash after SOS stops.

### Alert state survives restarts; unlock to send doesn't end SOS
- **Date / version:** 2026-10-18, v2.3.0 (PR #16)
- **Decision:** Persist the alert stage (NONE/COUNTING/CANCELLED/SENT), time and incident. A restart
  mid-SOS doesn't re-alert and respects a Cancel. The unlock needed for "Send SMS" or WhatsApp no
  longer stops SOS or sends "false alarm". "False alarm" can't arrive before a delayed alert. A
  "couldn't send" state with manual send. Fixed double-cost SMS on Android 14+ and the theft→SOS
  camera conflict.
- **Why:** Full review findings.
- **Status:** current.

### DND override only up to Android 14
- **Date / version:** v2.0.0
- **Decision:** No DND override on API 35+; the limitation is shown in setup.
- **Why:** Platform review finding in v2.0.0 for API 35+. The README notes the override applies on Android 14 and older.
- **Status:** current.
