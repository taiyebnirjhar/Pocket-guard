# Setting up the free backend (for WP4 and WP5)

Pocket Guard's online features (account, live location page, alert emails, photo and video
upload) run on free tiers: Supabase, Cloudflare (R2 and Pages) and Brevo. This takes about
20 minutes. **Never paste keys into chat or commit them:** they go into GitHub Secrets (or
Supabase's own secret store), where CI reads them.

GitHub Secrets live at: repository **Settings → Secrets and variables → Actions → New repository
secret**.

## 1. Supabase (database, login, live location)

1. Sign up at supabase.com and create a **New project**:
   - **Name:** `pocket-guard`
   - **Region:** the one closest to your users (for Bangladesh: Singapore)
   - **Database password:** generate one and keep it in your password manager
2. Note the project's **Reference ID** (Project Settings → General) and **Project URL**
   (Project Settings → API).
3. Create an **access token** for CI: account menu → Access Tokens → Generate new token.
4. Add these GitHub secrets:
   | Secret | Where it comes from |
   |---|---|
   | `SUPABASE_PROJECT_REF` | Reference ID |
   | `SUPABASE_URL` | Project URL (`https://<ref>.supabase.co`) |
   | `SUPABASE_ANON_KEY` | Project Settings → API → `anon` `public` key (safe to ship in the app; the database rules protect the data) |
   | `SUPABASE_ACCESS_TOKEN` | The access token from step 3 |
   | `SUPABASE_DB_PASSWORD` | The database password from step 1 |
5. Do **not** copy the `service_role` key anywhere. The server functions read it from Supabase
   itself.

## 2. Brevo (email)

1. Sign up at brevo.com (free plan).
2. **Senders, Domains & Dedicated IPs → Senders → Add a sender**, and verify the email. With a
   domain of your own, also add it under **Domains** and create the DNS records Brevo shows:
   alerts are then far less likely to land in spam.
3. **SMTP & API → API Keys → Generate a new API key.** Add GitHub secrets:
   | Secret | Value |
   |---|---|
   | `BREVO_API_KEY` | The API key |
   | `BREVO_SENDER_EMAIL` | The verified sender address |
4. **SMTP & API → SMTP:** note the SMTP server, port, login and an SMTP key. In Supabase:
   **Authentication → Emails → SMTP Settings → Enable custom SMTP**, and enter them with the same
   sender address. (Supabase's built-in email is only for testing and is heavily rate-limited.)

## 3. Cloudflare (photo and video storage, live page)

1. Sign up at cloudflare.com. Note the **Account ID** (right column of the dashboard home).
2. **R2 → Overview → Purchase R2 / enable.** The free tier includes 10 GB of storage, and
   downloads are free. Cloudflare may ask for a card; usage that stays within the free tier is
   not charged.
3. **R2 → Create bucket:** name `pocket-guard-media`, location Automatic. Leave public access off.
4. **R2 → Manage R2 API Tokens → Create API token:** permission *Object Read & Write*, applied to
   that bucket only. Note the **Access Key ID** and **Secret Access Key**.
5. **My Profile → API Tokens → Create Token → Custom:** permissions *Account → Cloudflare Pages →
   Edit* and *Account → Workers R2 Storage → Edit*. This lets CI publish the live page and set the
   bucket's 90-day deletion rule.
6. Add GitHub secrets:
   | Secret | Value |
   |---|---|
   | `CLOUDFLARE_ACCOUNT_ID` | Account ID |
   | `CLOUDFLARE_API_TOKEN` | The token from step 5 |
   | `R2_ACCESS_KEY_ID` | From step 4 |
   | `R2_SECRET_ACCESS_KEY` | From step 4 |
   | `R2_BUCKET` | `pocket-guard-media` |

## 4. Tell Claude

Say "backend secrets are in". CI then creates the tables and rules, deploys the server
functions (they get the R2 and Brevo keys from GitHub Secrets at deploy time), publishes the
live page and sets the 90-day deletion rule. Nothing else is needed from you.

## Free-tier limits to keep in mind

| Service | Free limit | What uses it |
|---|---|---|
| Supabase | 500 MB database, 5 GB egress a month, pauses after 7 days idle | Accounts, contacts, live location (a daily job keeps it awake) |
| Brevo | 300 emails a day, small Brevo logo on emails | Alert and login emails |
| Cloudflare R2 | 10 GB storage, free downloads | Photos and 480p video, deleted after 90 days |
| Cloudflare Pages | Free static hosting | The live location page |

These are the free plans as of writing; check each provider's pricing page.
