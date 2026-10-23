# Working agreements

How the owner (Taiyeb) works, and the rules every Claude session follows on this project.

## Roles

- **Claude** writes all code, tests, docs and CI changes, and opens pull requests.
- **Taiyeb** merges pull requests, usually from his phone. He has no local Git setup.
- **Claude never merges** a PR and never deploys to production. A merge to `main` is the owner's
  go-ahead for the release and the backend deploy.
- Merging to `main` with a new `VERSION` releases automatically (APK + Play AAB), then deploys the
  website and backend. Same `VERSION` = tested, not released.
- Side work can run in its own session (website: branch `website-landing`; promo video: its own
  session). It still goes through PRs the owner merges.

## Pull requests and commits

- One work package or fix = one PR = one release. Bump `VERSION` in the same PR (patch fix, minor
  feature, major big change) and add a row to [../history/releases.md](../history/releases.md).
- Wait for all CI, including the emulator e2e, before telling the owner it's ready. Keep the PR
  in Draft until green. Ask him to wait for green if he merges early.
- Wait for one release to finish before the next merge. Retarget stacked PRs to `main` after each
  merge.
- After a merge, reset the work branch to the latest `origin/main` before new work.
- Every commit message ends with two trailer lines:
  ```
  Co-Authored-By: <assistant name> <noreply@anthropic.com>
  Claude-Session: <link to this session>
  ```
  Use the exact lines the session's own instructions give.
- Every PR description ends with:
  ```
  🤖 Generated with [Claude Code](https://claude.com/claude-code)

  <link to this session>
  ```
- No model names or identifiers in commit messages, PR text or code.
- Pushes that change only Markdown don't run CI.

## Secrets and accounts

- Never put secret values in chat, commits, docs, logs or screenshots. They live only in GitHub
  Secrets (and Supabase's own store). The owner adds them himself ("Don't paste the token here").
- Never send the owner's private email address to any service.
- The repository is **public** (since 2026-10-23). Never print or upload anything private from a
  workflow: its logs and files are public too.
- Don't use or change the owner's other Cloudflare tokens or buckets (e.g. Gadget Nation). Scope
  every Pocket Guard credential to the minimum.
- Production backend changes happen only through `backend.yml` after his merge.

## Communication

- If the owner only asks a question, answer it. Don't start work until he asks ("I just asked.
  Dont start immediately"). Stash rather than push unapproved changes.
- For bigger work: plan first ("make a plan and wbs"), get a go-ahead, then build step by step.
  If you need something from him, ask; don't get blocked.
- Use plain, clear English and short steps. He writes informally; don't correct him.
- He reads on a phone: give direct links and numbered steps (e.g. exact GitHub settings paths).
- "What's our status?" → a short table: released, open PRs, what is needed from him, with links.
- Always send the release link when a release is ready, plus a short phone test list.
- Admit mistakes plainly.
- **Every reply ends with a TL;DR in bullets** (owner's rule, 2026-10-23): what was done, what's
  going on, what's next, and what's needed from him.

## Testing

- He prefers automated tests over manual phone testing ("So dont need to relay on manual test").
  Every behaviour change gets unit tests and, where an emulator can fake it, an e2e scenario.
- New tests should fail without their fix (mutation check).
- Build regression tests from his detection-log pastes when he reports a field bug.
- Big changes get adversarial multi-reviewer passes (as in v2.0.0 and v2.3.0).

## Product values

- Never miss a real theft; false alarms are cut through arming conditions, not by weakening an
  armed guard.
- Simple, quick, conflict-free interactions: one button, few taps.
- Don't break features that work ("stolen alarm… is nice").
- Guided setup in the first-run tour; don't make users hunt in settings.
- Free to run: stay within free tiers.
- Strings in English and Bangla (`values/` and `values-bn/`); SOS SMS in English letters.
- No over-claiming ("100% accurate") in public text; no fake reviews or user counts.
