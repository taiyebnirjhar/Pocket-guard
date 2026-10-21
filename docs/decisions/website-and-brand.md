# Website and brand decisions

App look, website, promo video and public contact. Oldest first.

### App look inspired by the "Cryptonix" VPN design
- **Date / version:** 2026-10-16 (build 20, before v1.0.0)
- **Decision:** Dark `#141414` with a purple glow, cards `#252525`, purple `#7A5ADF`, lavender
  `#AA90EA`, grey `#D2D1D3`; Manrope variable font (OFL); particle ring around a big round power
  button; pill cards; floating round-icon bottom nav. Tabs: Home, Triggers, History, Settings.
- **Why:** Owner chose the Behance reference.
- **Status:** current. v2.0.0 changed purple text to lavender for contrast (3.3:1 → about 6:1),
  48 dp touch targets and screen-reader labels.

### Promo video
- **Date / version:** 2026-10-16
- **Decision:** Vertical 1080×1920, about 23 s, for Reels, TikTok and Shorts. HTML animation
  rendered frame by frame (Playwright + ffmpeg). Two cuts: with sound effects, and silent for
  trending music. Story: crowded train → one tap → pocket → thief pulls → siren and intruder photo
  → fingerprint stops it → end card ("Catch pickpockets in the act.", "Android · Free · No account ·
  English & Bangla"). Work moved to its own session; sources in the owner's `Random` repo
  (`pocket-guard-video/`).
- **Why:** Personal branding and launch posts.
- **Status:** current. Updated 2026-10-18: gesture is "press power (or fingerprint), then tap volume
  up 4 times"; the character is renamed Nadia → **Sadia** everywhere; an extra theft-to-SOS scene
  was suggested. "No account" is still true (anonymous accounts, no sign-up).

### Privacy and live pages on GitHub Pages
- **Date / version:** 2026-10-17, v1.6.0 / v1.8.0
- **Decision:** Host `site/privacy.html` and `site/live/` on GitHub Pages.
- **Why:** Fewer secrets; no account-wide Cloudflare Pages token.
- **Rejected alternatives:** Cloudflare Pages with `CLOUDFLARE_API_TOKEN` (dropped at the time).
- **Status:** superseded by Cloudflare Pages in v2.2.0 (repo went private).

### Live page design
- **Date / version:** 2026-10-17, v1.8.0
- **Decision:** OpenStreetMap with Leaflet 1.9.4 and SRI hashes, English and Bangla, trail, battery,
  last seen; "SOS ENDED" / "FALSE ALARM" at the end; "This link no longer works" without a valid
  token; a Recordings list (v1.9.0).
- **Why:** No login for contacts; free map tiles.
- **Status:** current.

### Move the site to Cloudflare Pages
- **Date / version:** 2026-10-18, v2.2.0 (PR #12)
- **Decision:** Publish `site/` to https://pocket-guard.pages.dev with `pages.yml` (wrangler) and a
  token limited to Account → Cloudflare Pages → Edit. Update links in the app, SMS, email and
  privacy policy.
- **Why:** GitHub Pages doesn't work for a private repo on the free plan; the privacy policy must be
  online for Play.
- **Rejected alternatives:** Making the repo public again. A custom subdomain (not chosen yet).
- **Status:** current. Alerts sent before v2.2.0 point to the dead github.io address.

### Public support email
- **Date / version:** 2026-10-18
- **Decision:** Use `support.pocketguard@gmail.com` as the public developer and support contact
  (repo variable `CONTACT_EMAIL`, shown on the privacy page). It is also the deletion channel.
- **Why:** Owner prefers a dedicated address over his personal one.
- **Rejected alternatives:** Cloudflare Email Routing on his own domain (optional later); public
  GitHub issues (not reachable once private).
- **Status:** current.

### Landing website
- **Date / version:** 2026-10-18 (PR #15), refined in PRs #17, #19, #20, #21
- **Decision:** A landing page built in its own session (branch `website-landing`). Brief: public
  safety, primarily women (student walking home, bus commuter, someone harassed); confident, not
  scary; the app's dark purple/lavender look; Behance references credited. Sections: hero +
  Download, How it works (3 steps), SOS, theft alarm, privacy, FAQ, footer. Plain HTML/CSS/JS, no
  build step.
- **Why:** A public place to download and explain the app while the repo is private.
- **Rejected alternatives:** "No AI slop": no fake reviews, no user counts, no stock shields.
- **Status:** current.

### Stable download link
- **Date / version:** 2026-10-18, PR #15
- **Decision:** `pages.yml` copies the latest release APK to `/download/PocketGuard.apk` (plus
  `latest.json`) on every deploy and after every release. The button shows the version. The APK is
  never committed; deploy fails at 25 MiB or more.
- **Why:** GitHub release links need login now the repo is private.
- **Status:** current. PR #20: Pages always deploys the site as on `main`, so a release can't put an
  older page back.

### Website copy and media
- **Date / version:** 2026-10-19, PRs #17, #19, #21
- **Decision:** Real phone screenshots (theft alarm, Triggers, History, SOS); the product video under
  "How it works"; headline "A loud friend in your pocket."; gender-neutral copy that mentions bags as
  well as pockets; the example SMS uses "Sadia". Every claim fact-checked against the code (three
  passes, PR #21).
- **Why:** Accuracy and real visuals over mock-ups.
- **Rejected alternatives:** Headline "Your phone fights back." (tried, reverted).
- **Status:** current. Note: the brief targets women first, but the page copy is gender-neutral.

### Google Play badge waits
- **Date / version:** 2026-10-18
- **Decision:** A hidden Google Play badge slot in `site/index.html` (see the `GOOGLE PLAY` comment).
- **Why:** Turn it on once the listing is live.
- **Status:** open (depends on WP6).
