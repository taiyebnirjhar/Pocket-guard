#!/usr/bin/env bash
# Starts a local backend for the e2e test, so the emulator never touches the real one: Supabase
# (database, anonymous sign-in, functions; the emulator reaches it at 10.0.2.2:54321), a
# stand-in for Brevo that records the emails in e2e-output/brevo.jsonl, and one for R2. Writes
# the local keys to $GITHUB_ENV as E2E_API_URL, E2E_PUBLISHABLE_KEY and E2E_DB_URL.
set -euo pipefail
mkdir -p e2e-output
: >e2e-output/brevo.jsonl
nohup python3 e2e/mock_brevo.py 9999 e2e-output/brevo.jsonl >e2e-output/mock-brevo.log 2>&1 &
export BREVO_API_KEY=test BREVO_API_URL=http://host.docker.internal:9999/v3 BREVO_SENDER_EMAIL=alerts@example.com
export LIVE_PAGE_URL=https://pocket-guard.pages.dev/live/
# A stand-in for R2. The emulator reaches the host as 10.0.2.2, so the signed links use that
# address; giving the host the same address lets the test and Chrome follow them too.
sudo ip addr add 10.0.2.2/32 dev lo 2>/dev/null || true
mkdir -p e2e-output/s3
nohup npx -y s3rver@3.7.1 -d e2e-output/s3 -a 0.0.0.0 -p 9000 --no-vhost-buckets \
  --configure-bucket pocket-guard-media >e2e-output/s3rver.log 2>&1 &
export R2_ENDPOINT=http://host.docker.internal:9000 R2_PUBLIC_ENDPOINT=http://10.0.2.2:9000
export R2_ACCESS_KEY_ID=S3RVER R2_SECRET_ACCESS_KEY=S3RVER R2_BUCKET=pocket-guard-media
supabase start -x vector,logflare,supavisor,postgres-meta,mailpit,imgproxy,storage-api,realtime,studio
supabase status -o env | grep -E '^(API_URL|PUBLISHABLE_KEY|DB_URL)=' | sed -e 's/^/E2E_/' -e 's/"//g' >>"${GITHUB_ENV:-/dev/stdout}"
