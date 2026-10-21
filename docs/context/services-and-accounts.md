# Services and accounts

All accounts belong to the owner, Taiyeb. Claude has no console access: it works through the
Claude GitHub App on the repository and through CI. The owner adds and rotates secrets himself
in GitHub settings. Never paste a secret value into chat, commits, docs or logs.

## Services

| Service | Used for | Plan | Owner action needed to change |
|---|---|---|---|
| GitHub (`taiyebnirjhar/Pocket-guard`, **private**) | Code, PRs, Actions CI/CD, releases | Free (2,000 Actions min/month on private repos) | Owner merges PRs, manages secrets |
| Supabase (project "Pocket Guard", Singapore) | Anonymous auth, Postgres, edge functions, pg_cron | Free (pauses after 7 days idle; daily job keeps it awake) | Owner console |
| Brevo | Alert emails (API) and Supabase auth email (SMTP) | Free (300 emails/day, Brevo logo) | Owner console |
| Cloudflare R2 (bucket `pocket-guard-media`) | SOS video pieces, private, 90-day lifecycle | Free (10 GB, free egress) | Owner console |
| Cloudflare Pages (project at `pocket-guard.pages.dev`) | Landing page, privacy policy, live map, APK download | Free | Deployed by `pages.yml` |
| Cloudflare DNS (owner's domain `taiyebnirjhor.com`) | Brevo sender domain `pocketguard.taiyebnirjhor.com` (DKIM, DMARC) | Free | Owner console |
| Gmail `support.pocketguard@gmail.com` | Public support and developer contact | Free | Owner |
| Google Play Console | Not created yet (WP6) | $25 one-time | Owner |

The same Cloudflare account holds the owner's other projects (Gadget Nation buckets and other
API tokens such as "Cloudflare Agent Token" and "Edit Cloudflare Workers"). Pocket Guard must
never use or change them. Pocket Guard's tokens are scoped to its own bucket and to Pages only.

## Public URLs

| What | URL |
|---|---|
| Website | https://pocket-guard.pages.dev |
| Privacy policy | https://pocket-guard.pages.dev/privacy.html |
| Live location page | https://pocket-guard.pages.dev/live/#t=<token> |
| Latest APK (stable link) | https://pocket-guard.pages.dev/download/PocketGuard.apk |
| Latest version info | https://pocket-guard.pages.dev/download/latest.json |
| Repository (private) | https://github.com/taiyebnirjhar/Pocket-guard |
| Releases (private) | https://github.com/taiyebnirjhar/Pocket-guard/releases |
| Alert sender | alerts@pocketguard.taiyebnirjhor.com |
| Support email | support.pocketguard@gmail.com |
| Old site (dead while the repo is private) | https://taiyebnirjhar.github.io/Pocket-guard/ |

## GitHub Actions secrets (names only)

| Secret | Purpose | Used by |
|---|---|---|
| `SIGNING_KEYSTORE_BASE64` | Release keystore (alias `pocketguard`) | `ci.yml` |
| `SIGNING_PASSWORD` | Keystore password | `ci.yml` |
| `SUPABASE_URL` | Project URL, baked into the app | all five workflows |
| `SUPABASE_ANON_KEY` | Publishable key, safe in the app (RLS protects data) | all five workflows |
| `SUPABASE_PROJECT_REF` | Project reference for the CLI | `backend.yml`, `daily.yml`, `check-backend.yml` |
| `SUPABASE_ACCESS_TOKEN` | CLI token "github-ci" for deploys | `backend.yml`, `daily.yml`, `check-backend.yml` |
| `SUPABASE_DB_PASSWORD` | Database password for `db push` | `backend.yml`, `check-backend.yml` |
| `BREVO_API_KEY` | Alert email API (set as a function secret) | `backend.yml`, `check-backend.yml` |
| `BREVO_SENDER_EMAIL` | Verified sender address | `backend.yml`, `check-backend.yml` |
| `BREVO_SMTP_LOGIN`, `BREVO_SMTP_KEY` | Supabase Auth SMTP | `backend.yml`, `check-backend.yml` |
| `CLOUDFLARE_ACCOUNT_ID` | R2 endpoint and Pages deploy | `backend.yml`, `check-backend.yml`, `pages.yml` |
| `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` | R2 token scoped to `pocket-guard-media` only | `backend.yml`, `check-backend.yml` |
| `R2_BUCKET` | Bucket name | `backend.yml`, `check-backend.yml` |
| `CLOUDFLARE_API_TOKEN` | Custom token "pocket-guard-pages": Account → Cloudflare Pages → Edit only | `pages.yml` |

Repository **variable** (not a secret): `CONTACT_EMAIL`, injected into `privacy.html`.

Never use the Supabase `service_role` key outside Supabase. The functions read it from Supabase.

The release key backup is held by the owner offline. Losing it means users must uninstall to
update.

## Who owns what

| Area | Owner |
|---|---|
| All code, tests, docs, CI changes, PRs | Claude |
| Merging PRs (= releasing and deploying to production) | Taiyeb |
| Accounts, consoles, DNS, secrets, R2 lifecycle rule | Taiyeb |
| Phone testing | Taiyeb |
| Play Console, listing upload, declarations submission | Taiyeb (Claude drafts) |
| Website copy and design | Claude, in its own session/branch (`website-landing`), owner merges |
| Promo video | A separate Claude session ("Pocket Guard promo video") |
