# SOS and settings UX plan (shipped together as v2.6.0)

Agreed with the owner on 2026-10-23. Each step is one PR and one release.

## Why

- The online part of SOS (live map, video upload, emails) was invisible. It only runs during an SOS
  that reaches approved contacts, and its one switch ("Live location link", under Settings) quietly
  covered three things.
- A siren tells an attacker "this phone is calling for help": they may switch it off or smash it.
  Sometimes the user needs SOS to be silent.
- Theft and SOS settings were mixed on one Triggers screen.

## Step 1 · v2.6.0 (with step 2) · SOS tab and separate online switches

- Five tabs: **Home · Theft · SOS · History · Settings**.
  - **Theft**: the theft alarm switch and its triggers, unlock delay, arming reminders, schedule,
    intruder photos.
  - **SOS**: a readiness line ("Ready: alerts go to 3 people" / "Siren only: no emergency contacts
    yet" / "SOS alerts are off" when the approval needs renewing), then "On SOS, Pocket Guard will":
    siren · record video · text contacts (always) · live map link · upload the video · email contacts
    (each a switch; video and email need the live map), then how to start SOS, then the contacts.
  - **Settings**: general only (test mode, setup checklist, sensor check, log, language, delete my
    online data, guide, privacy policy).
- Home shows "Siren only: no emergency contacts yet" when SOS has nobody to tell, and opens the SOS tab.
- Switching emails off asks for the contacts' approval again (it changes who an alert reaches).
- `--es tab triggers` still opens the Theft tab.

## Step 2 · v2.6.0 · Silent SOS

- **Volume up × 4 = loud SOS** (as now). **Volume down × 4 = silent SOS.**
- Silent SOS: no siren, voice, flash or alarm screen; the screen stays as it was. One short buzz
  confirms it. The 5 s cancel countdown runs silently. The status notification stays
  plain ("Pocket Guard is on"), with no SOS wording on the lock screen.
- SMS, live map, video recording and upload run as for a loud SOS. Video pieces are shorter
  (30 s), so less is lost if the phone is cut off.
- Unlocking doesn't end it; Home shows "Silent SOS is on" with cancel, send by SMS app, and "I'm
  safe: stop the SOS". Volume up × 4 makes it loud; nothing makes a loud SOS silent.
- The volume keys still change the volume (the key watcher only watches). Not while music, a video
  or a call plays, as for volume up.
- Limits Android won't let us hide: the green camera/microphone dot (Android 12+), and the phone can
  still be switched off (the power-menu blocker is a separate, GitHub-only question).

## Step 3 · v2.6.0 (with steps 1 and 2) · Signal lost, on the live map only

- Owner, 2026-10-23: "Keep things in live location as much as possible. Reduce email usage as much
  as possible… Goal is to keep things 0 infra for life." So no email, no server job.
- The server keeps `incidents.last_seen_at`: the time of the phone's latest upload (a location, or
  a check-in with the battery every 15 s when there's no new location). `view_incident` also returns
  the server's own time, so the viewer's clock doesn't matter.
- The live page: SOS active and **60 s** without the phone → "The phone has been offline since 21:42
  (3 min). It may be switched off, broken or out of signal", with the last position and the route
  (already drawn). It clears by itself when the phone comes back.
- Fewer emails: "Send a test" is SMS only; the email switch is **off by default** (on for anyone who
  approved contacts before, so their approval holds). The SOS email and its "false alarm" follow-up
  stay for those who switch emails on.

## Not now

- Disguising the alarm screen as a fake power-off (removed in v1.x for Play Protect reasons).
- A second loud/silent setting: the two gestures make it a choice in the moment instead.
