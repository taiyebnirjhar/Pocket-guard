# Current state (handoff)

Snapshot as of **2026-10-21**. Update this at the end of every work session.

## Latest release

- **v2.4.0**, PR #22, merged and released 2026-10-20. Fewer false pocket alarms (finger over the
  sensor in the hand, 2 s settle, 2.5 s re-cover recovery including the sit-down posture shift)
  and a separate Theft alarm switch ("SOS ready" mode). 174 unit tests.
- Release page: https://github.com/taiyebnirjhar/Pocket-guard/releases/tag/v2.4.0 (private)
- Public download: https://pocket-guard.pages.dev/download/PocketGuard.apk
- Full history: [../history/releases.md](../history/releases.md)

## What's live

- App v2.4.0 (full build) on the website and GitHub releases; Play AAB attached but not uploaded.
- Website https://pocket-guard.pages.dev (landing, privacy policy, live map, APK download).
- Backend on Supabase (Singapore), Brevo email, R2 media; daily keep-alive running.
- Repo is **public** (since 2026-10-23). No open PRs from Claude as of this snapshot (apart from this docs work).

## Pending

See [../plans/open-items.md](../plans/open-items.md). In short:

- **WP6 Play Store submission** not started (needs the owner's Play account).
- Six unfixed findings from the PR #16 review (dual-SIM, email hash pepper, Play false-alarm
  notification, SHA-pinning actions, deletion during SOS upload, throwaway-account email abuse).
- Phone tests owed by the owner for v2.2.0 schedule, v2.3.1 table buzz, v2.4.0 pocket fixes and
  SOS-only mode; volume-lowering log still missing.
- Google Play badge on the website after the listing goes live.

## Awaiting the owner's answer

- Whether to build the optional **power-menu blocker** during the siren (GitHub build only, off by
  default). Offered 2026-10-19.

## In progress

- This knowledge base (`docs/` reorganised into context, decisions, plans, setup, history, memory),
  requested 2026-10-21.

## Branches

- Claude's work branch for this session line: `ccr-d63a1ea6-qcsp4u` (Claude Code sessions use a
  `ccr-…` name). After each merge it is reset to `origin/main` and force-pushed.
- Website session branch: `website-landing`.
- Older `wip/*` branches (one per early work package) are merged and can be deleted by the owner.
- Releases come only from `main`.

## Where things are

| What | Where |
|---|---|
| App code | `app/src/main/java/com/taiyeb/pocketguard/` |
| Full-build-only permissions | `app/src/full/AndroidManifest.xml` |
| Unit tests | `app/src/test/` |
| Emulator e2e | `e2e/run.sh` |
| Backend | `supabase/` (migrations, functions, tests) |
| Website | `site/` |
| CI/CD | `.github/workflows/` |
| Current behaviour | root [README.md](../../README.md) |
| Decisions | [../decisions/](../decisions/) |
| Detection design | [../plans/detection-plan.md](../plans/detection-plan.md) |
| Backend setup guide | [../setup/backend.md](../setup/backend.md) |
| Services and secret names | [../context/services-and-accounts.md](../context/services-and-accounts.md) |

## Local dev notes for Claude

- Clone at `/home/user/pg-repo`. No Android SDK locally: run engine tests in a scratch JVM
  harness; UI, notification and camera code compile only in CI.
- Maven through `repo1.maven.org`. The proxy can't delete remote branches.
