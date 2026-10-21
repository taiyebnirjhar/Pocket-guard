# Play Store and privacy decisions

Google Play policy, sideloading, permissions and data. Oldest first. Submission tasks are in
[../plans/roadmap.md](../plans/roadmap.md) (WP6) and [../plans/open-items.md](../plans/open-items.md).

### Cut features after Play Protect blocked the install
- **Date / version:** before 2026-10-16
- **Decision:** Remove SMS, location and power-menu Accessibility from the single version.
- **Why:** Play Protect hard-blocked the sideloaded build.
- **Status:** superseded by the two-build approach (v1.6.0). See [product.md](product.md).

### Plan around the Play Developer Program Policy
- **Date / version:** 2026-10-17 (policy effective 2026-09-30, pasted by the owner)
- **Decision:**
  - Accessibility: optional, prominent disclosure and consent first ("I agree, open settings"),
    `isAccessibilityTool` off, documented in the listing.
  - Message Spam: approval screen with exact text and recipients, plus a 5 s countdown.
  - `SEND_SMS`: only in the full build; apply for the emergency-alert exception later.
  - Location: foreground service while in use, user-initiated SOS only. No background location.
  - Stalkerware: persistent notification, no remote activation, no hidden mode, no call recording,
    listing says personal safety.
  - Full-screen intent (Android 14+): check the permission, setup row, heads-up fallback.
  - Battery-optimisation permission only in the full build; Play opens battery settings.
  - A disclosure and an affirmative tap before every runtime permission (`Disclosure.kt`).
  - Account deletion in the app and by request; privacy policy covering data, sharing with chosen
    contacts and 90-day retention; Data safety form; FGS type declarations with videos.
  - Target SDK 36.
  - No "100% accurate"-style claims in the listing.
- **Why:** To pass review without rewriting later.
- **Status:** current.

### Two builds: full (GitHub) and play
- **Date / version:** 2026-10-17, v1.6.0 (PR #4)
- **Decision:** Flavour `full` keeps `SEND_SMS` and battery-optimisation (and from v2.2.0
  `SCHEDULE_EXACT_ALARM`); flavour `play` (`BuildConfig.PLAY_STORE`) has none of them. Every release
  attaches the Play AAB.
- **Why:** Owner asked "two builds or one Play-safe app"; two builds keep the best GitHub version
  without risking Play review.
- **Status:** current.

### Publish on Play later
- **Date / version:** 2026-10-17
- **Decision:** Owner: "We will publish on playstore later." Work continues on features first.
- **Status:** current. WP6 still open.

### Sideload blocks are handled with guidance, not workarounds in code
- **Date / version:** 2026-10-17, v2.0.1 / v2.1.0
- **Decision:** Explain Play Protect "enhanced fraud protection" (blocks sideloaded v1.5.0+ because
  of Accessibility: pause scanning, `adb install`, or Play internal testing). Guide Android 13+
  "restricted settings" in-app in 3 steps (try the switch, App info → ⋮ → Allow restricted settings,
  turn it on).
- **Why:** Both are system protections for sideloaded apps with Accessibility.
- **Status:** current. The lasting fix is distribution through Play.

### Truthful Play build and clearer permissions
- **Date / version:** 2026-10-17, v2.0.0 (PR #9)
- **Decision:** Play build says SMS is tap-to-send; Accessibility opened only through the disclosure,
  button "Open settings" not "Allow"; specialUse FGS text mentions SOS; approval dialog lists every
  message; permanently denied permissions open App settings.
- **Why:** UI/policy review findings.
- **Status:** current.

### Foreground service types held while armed
- **Date / version:** v2.0.0
- **Decision:** Hold location, camera and microphone service types while armed, and explain to
  reviewers that Android grants them only to a service started while the app is open.
- **Why:** SOS must record and locate from the lock screen.
- **Status:** current; a likely review question. Armed by the schedule while the app is closed on
  Android 14+, these are withheld until the app is opened (a notification says so).

### Developer contact email
- **Date / version:** 2026-10-17 / 2026-10-18
- **Decision:** `support.pocketguard@gmail.com` is the developer contact and deletion channel.
- **Status:** current.

### Data kept to a minimum
- **Date / version:** v1.8.0 onward
- **Decision:** Anonymous ID; incident, locations and media index only during and after SOS; email
  addresses only as hashes; intruder photos never leave the phone; everything deleted after 90 days;
  "Delete my online data" removes it at once.
- **Why:** Privacy and Play Data safety.
- **Status:** current. Privacy policy at https://pocket-guard.pages.dev/privacy.html.

### Play Integrity deferred
- **Date / version:** 2026-10-17
- **Decision:** Add Play Integrity to anonymous sign-up after the Play account exists. Until then,
  server-side caps.
- **Why:** Strongest fix against throwaway-account abuse, but needs a Play listing.
- **Status:** open.

### Accessibility only for SOS keys; no power-menu blocker on Play
- **Date / version:** 2026-10-19
- **Decision:** Treat Accessibility as high-risk on Play (Play allows it mainly for disability
  tools). Any power-menu blocker would be GitHub-only, off by default. The silent-media trick stays
  rejected.
- **Why:** Policy risk.
- **Status:** current. The Play build still declares the SOS key watcher; confirm at WP6 whether
  it can stay (open item).
