# Ideas

Floated in conversation but not committed to. Nothing here is planned until the owner says so.
Move an idea to [open-items.md](open-items.md) or the [roadmap](roadmap.md) when it is accepted.

## App

- **Sharing for 30 minutes after SOS ends**, with a "Stop sharing" button and a persistent
  "Sharing your location with N contacts · Stop" notification. In the original live-location plan.
- **Home-screen SOS widget.**
- **A photo every 10 s uploaded during SOS.** In the original plan; only video is uploaded today.
- **Fewer SMS:** drop the Google Maps link when the live link exists, or send fewer location updates.
- **More schedule pause options:** "1 h" and "Off until tomorrow".
- **Gyroscope** for slow, level slides; and the other unbuilt steps in the
  [detection plan](detection-plan.md) ("Not yet": edge-based owner check, call-state pause, charger
  watch, hand rules in shadow mode, detection lab, watchdog, rate tiers, `emuctl` e2e daemon).
- **Power-menu blocker** during the siren (GitHub build only). Awaiting the owner's answer; see
  [open-items.md](open-items.md).

## Web and accounts

- **Account-deletion web page** for people without the app (today: in-app "Delete my online data"
  or email support).
- **Custom domain** for the site, e.g. a `pocketguard.` or `live.` subdomain of the owner's domain.
- **Support address on the owner's domain** via Cloudflare Email Routing, instead of Gmail.
- **Feature list as a page** to share with family.

## Marketing

- A real 5-second phone screen recording at the start of the promo video.
- An extra promo scene: a theft alarm switching to SOS.
- GitHub About topics: android, kotlin, jetpack-compose, anti-theft (needs the owner; moot while private).

## Engineering

- If starting a new product: a monorepo (apps/web, apps/api, packages/shared). Raised when the
  owner asked about other stacks (Next.js, NestJS, Postgres, Redis).
