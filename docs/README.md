# Pocket Guard knowledge base

Current version: **2.4.0** (from [`VERSION`](../VERSION)). Snapshot date: 2026-10-21.

## The project in one paragraph

Pocket Guard is an Android app (Kotlin, Jetpack Compose) for public safety, aimed first at women
in Bangladesh, in English and Bangla. It has two jobs. The **theft alarm** watches a locked phone
in a pocket, bag or on a table and sounds a siren (with intruder photos) when someone takes it.
**SOS** (volume up tapped 4 times) sounds a help alarm, records video, and alerts up to 5
emergency contacts by SMS and email with a live location link. The backend is free-tier and
serverless: Supabase, Brevo, Cloudflare R2 and Cloudflare Pages. Claude writes the code, GitHub
Actions builds, tests and releases it, and the owner (Taiyeb) merges and tests on his phone.

## Folders

| Folder | What it holds |
|---|---|
| [`context/`](context/) | What the app is, how it is built, which services it uses. Read to get oriented. |
| [`decisions/`](decisions/) | Every product and technical decision, one file per area, with date, reason and status. |
| [`plans/`](plans/) | The roadmap, the detection design, open items and unchosen ideas. |
| [`setup/`](setup/) | How to set up the backend accounts and GitHub secrets. |
| [`history/`](history/) | Release history and bugs reported from real use. |
| [`memory/`](memory/) | Working agreements and the current-state handoff. Read this first in a new session. |

### Files

- [context/overview.md](context/overview.md): the app, its users, the two builds, features, repo layout.
- [context/architecture.md](context/architecture.md): app components, backend, website, CI/CD, releases, e2e.
- [context/services-and-accounts.md](context/services-and-accounts.md): services, public URLs, secret names, ownership.
- [decisions/product.md](decisions/product.md)
- [decisions/detection.md](decisions/detection.md)
- [decisions/sos-and-alerts.md](decisions/sos-and-alerts.md)
- [decisions/backend-and-infra.md](decisions/backend-and-infra.md)
- [decisions/website-and-brand.md](decisions/website-and-brand.md)
- [decisions/play-store-and-privacy.md](decisions/play-store-and-privacy.md)
- [plans/roadmap.md](plans/roadmap.md): work packages WP1 to WP6.
- [plans/detection-plan.md](plans/detection-plan.md): the full detection design and threat model.
- [plans/open-items.md](plans/open-items.md): everything still pending.
- [plans/ideas.md](plans/ideas.md): ideas floated but not committed to.
- [setup/backend.md](setup/backend.md): Supabase, Brevo, R2 and Pages setup.
- [history/releases.md](history/releases.md): v1.0.0 to now.
- [history/field-reports.md](history/field-reports.md): bugs from real use, causes and fixes.
- [memory/working-agreements.md](memory/working-agreements.md): how we work, and rules for Claude.
- [memory/current-state.md](memory/current-state.md): where things stand right now.

The root [README](../README.md) describes how the app behaves today. When this folder and the
README disagree about current behaviour, the README (and the code) wins.

## How to keep this up to date

- Every PR that makes or changes a decision updates the matching `decisions/` file in the same PR.
  Mark the old entry "superseded by ... in vX"; don't delete it.
- Every PR that bumps `VERSION` adds a row to [history/releases.md](history/releases.md).
- A bug the owner reports from real use goes into [history/field-reports.md](history/field-reports.md)
  with its fix version.
- Close or add items in [plans/open-items.md](plans/open-items.md) as they change.
- At the end of a work session, refresh [memory/current-state.md](memory/current-state.md).
- New sessions start by reading `memory/`, then `plans/open-items.md`.
- Never write secret values, tokens, keys, project refs, account IDs or private emails here.
  Secret **names** are fine.
