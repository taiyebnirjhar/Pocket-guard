# Backend and infrastructure decisions

Repository, CI/CD, releases, Supabase, Brevo and R2. Oldest first. Current setup is described in
[../context/architecture.md](../context/architecture.md).

### Build everything in GitHub Actions
- **Date / version:** before 2026-10-16
- **Decision:** Unit tests, lint, emulator e2e and release all run in GitHub Actions (`ci.yml`).
  Markdown-only pushes don't build.
- **Why:** Claude's sandbox has no Android SDK (Google domains blocked); the owner has no local setup.
- **Status:** current.

### Own repository, MIT, new signing key
- **Date / version:** 2026-10-16
- **Decision:** Move the app out of the owner's `Random` repo into `taiyebnirjhar/Pocket-guard`,
  MIT licence, full history kept. The old signing key (public in `Random`) was purged and replaced;
  users needed one uninstall.
- **Why:** Owner: "Seperat the repo. And make it public for open source".
- **Status:** current for the repo and licence. Public visibility superseded (below).

### Versions from a VERSION file
- **Date / version:** 2026-10-16, v1.0.0
- **Decision:** `VERSION` holds `major.minor.patch`; tags `vX.Y.Z`; APK `PocketGuard-vX.Y.Z.apk`.
  Patch for a fix, minor for a feature, major for a big change. A release happens only when VERSION
  is new.
- **Why:** Owner wanted "v1.0.1"-style versions instead of "build N".
- **Rejected alternatives:** Prerelease tags `pocket-guard-build-N`.
- **Status:** current.

### Release only from main, through the owner's merge
- **Date / version:** 2026-10-17
- **Decision:** Claude pushes a branch and opens a PR. CI tests it (debug key, never publishes). The
  owner merges. `main` builds, tests and publishes if VERSION is new and the official key signed it.
- **Why:** Owner: "U can create pr. I will merge it". He wants APKs from `main` only.
- **Rejected alternatives:** Claude pushing straight to `main` (done before 2026-10-17).
- **Status:** current.

### Free serverless backend
- **Date / version:** 2026-10-17
- **Decision:** Supabase (auth, Postgres, realtime, edge functions) + Brevo (email) + Cloudflare R2
  (media) + static pages + GitHub Actions. No VPS.
- **Why:** Owner: free, zero infra cost; only the $25 Play fee.
- **Rejected alternatives:** Phone OTP (paid), Brevo SMS (paid), WhatsApp Business API (paid).
- **Status:** current.

### Supabase in Singapore
- **Date / version:** 2026-10-17
- **Decision:** Project "Pocket Guard" in Singapore (ap-southeast-1).
- **Why:** Bangladesh traffic lands through Singapore cables.
- **Rejected alternatives:** Sydney (created by accident, deleted), Mumbai.
- **Status:** current.

### CI owns migrations; no Supabase GitHub integration
- **Date / version:** 2026-10-17
- **Decision:** Don't connect Supabase's GitHub integration. CI applies migrations. Never edit an
  applied migration; keep changes backward-compatible; RLS from the start; CI applies all migrations
  to a clean database in tests.
- **Why:** One deploy path, tested first.
- **Status:** current.

### Supabase CI token with full access
- **Date / version:** 2026-10-17
- **Decision:** The `github-ci` access token has full access and no expiry.
- **Why:** Owner's choice.
- **Rejected alternatives:** Claude recommended a minimal token and explained the leak risk.
- **Status:** current.

### Brevo sender on a subdomain
- **Date / version:** 2026-10-17
- **Decision:** Authenticate Brevo on `pocketguard.taiyebnirjhor.com` (DNS-only records on
  Cloudflare); sender "Pocket Guard" `alerts@pocketguard.taiyebnirjhor.com`. No IP restriction.
- **Why:** Isolate alert email reputation from the main domain. Edge functions have no fixed IPs.
- **Status:** current.

### R2 bucket scoped and isolated
- **Date / version:** 2026-10-17
- **Decision:** Bucket `pocket-guard-media`, APAC, private, no public URL. Lifecycle rule
  `delete-after-90-days`, set by the owner by hand. R2 keys scoped to this one bucket.
- **Why:** The same Cloudflare account holds the owner's Gadget Nation buckets, which must never be
  touched. Setting the rule by hand means CI needs no account-wide R2 rights.
- **Status:** current.

### Secrets only in GitHub Secrets; check them without printing
- **Date / version:** 2026-10-17, v1.8.0
- **Decision:** All keys go in GitHub Secrets (owner adds them). `check-backend.yml` verifies each
  one (Supabase login, Brevo sender, R2 write/read/delete) without printing it. Never use the
  `service_role` key outside Supabase.
- **Why:** Keys must never appear in chat or commits.
- **Status:** current.

### Backend deploys only on merge
- **Date / version:** 2026-10-17, v1.8.0; tightened v2.0.0 and v2.3.0
- **Decision:** `backend.yml` deploys migrations and functions only from `main`. Since v2.0.0 it runs
  after CI succeeds (`workflow_run`, dry run first). Since v2.3.0 it skips commits that are no longer
  `main`'s tip. A smoke test with a self-deleting account follows.
- **Why:** The owner's merge is the go-ahead for production. Claude's safety check blocked a pre-merge
  deploy to the live backend.
- **Status:** current.

### Daily keep-alive and cleanup
- **Date / version:** 2026-10-17, v1.9.0
- **Decision:** `daily.yml` pings Supabase and posts a usage report. pg_cron ends SOS still running
  after 1 day and deletes data older than 90 days.
- **Why:** Free Supabase pauses after 7 days idle; 90-day retention promise.
- **Status:** current.

### Hand-written SigV4 and s3rver in tests
- **Date / version:** 2026-10-17, v1.9.0
- **Decision:** Sign R2 requests in `_shared/s3.ts`, checked byte-for-byte against botocore vectors.
  Test with s3rver.
- **Why:** No SDK needed in the edge function. MinIO hit Docker Hub limits.
- **Status:** current.

### Server-side limits that hold under parallel requests
- **Date / version:** 2026-10-17, v2.0.0; extended v2.3.0
- **Decision:** Caps on SOS per day, location points, media and email (see architecture), enforced
  with advisory locks and claim/release RPCs. Server sets `ended_at`; future timestamps clamped.
  v2.3.0: 60% cap on non-SOS email kinds; claim released on failure.
- **Why:** Anonymous accounts are cheap to create; protect the free quotas.
- **Rejected alternatives:** An auth rate-limit change (reverted: CGNAT puts many users behind one IP).
- **Status:** current. Play Integrity is the planned stronger fix (open).

### Store email hashes only
- **Date / version:** by v2.0.0
- **Decision:** The server never stores contact email addresses, only one-way hashes, to cap mail per
  address.
- **Why:** Privacy.
- **Status:** current. Adding a secret pepper is open (PR #16 finding).

### Repo visibility: public, then private
- **Date / version:** 2026-10-17 to 2026-10-18
- **Decision:** At 10:36 on 2026-10-17 the owner considered private and said "Leave it". Around 17:23
  the same day he made it **private**. On 2026-10-18: "We will keep it private for now".
- **Why:** Owner's choice.
- **Status:** superseded on 2026-10-23 (below). While private: GitHub Pages stopped (free plan);
  release links needed login; 2,000 Actions minutes a month; smaller runners.

### Repo public again
- **Date / version:** 2026-10-23
- **Decision:** The owner made the repository **public** again.
- **Why:** The private repo's free Actions minutes ran out (CI jobs on PR #24 got no runner), and the
  code holds no secrets (they are all in GitHub Secrets). Unlimited free CI on public repos.
- **Consequence:** anything a workflow prints or uploads is public, so detection logs are read from
  the Supabase dashboard, never through a workflow. Secrets stay masked in logs.
- **Status:** current.

### CI fits private-repo runners
- **Date / version:** 2026-10-17, v2.1.0 (PR #11)
- **Decision:** Free disk (remove .NET, Haskell, NDK), 3 GB emulator partition, 15 min boot timeout.
- **Why:** 2-core private runners lacked disk for the emulator.
- **Status:** current. A run takes about 25–30 min (about 70 runs per month of free minutes).

### Public APK is a release build
- **Date / version:** 2026-10-18, v2.3.0 (PR #16)
- **Decision:** Publish signed `fullRelease` (8.5 MB). e2e runs `fullDebug`. Backend overrides work
  only in debug builds. CI got concurrency, timeouts and Play-flavour unit tests. Release tag check
  fails closed.
- **Why:** Review HIGH finding: the public APK was the debuggable build signed with the official key.
- **Status:** current.

### Wait for one release before the next merge
- **Date / version:** 2026-10-17
- **Decision:** Merge the next PR only after the previous release and deploy finish. Stacked PRs are
  retargeted to `main` after each merge.
- **Why:** Releases and deploys must not overlap.
- **Status:** current.
