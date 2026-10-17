#!/usr/bin/env bash
# Starts a local backend for the e2e test, so the emulator never touches the real one: Supabase
# (database, anonymous sign-in, functions; the emulator reaches it at 10.0.2.2:54321) and a
# stand-in for Brevo that records the emails in e2e-output/brevo.jsonl. Writes the local keys to
# $GITHUB_ENV as E2E_API_URL, E2E_PUBLISHABLE_KEY and E2E_DB_URL.
set -euo pipefail
mkdir -p e2e-output
: >e2e-output/brevo.jsonl
nohup python3 e2e/mock_brevo.py 9999 e2e-output/brevo.jsonl >e2e-output/mock-brevo.log 2>&1 &
export BREVO_API_KEY=test BREVO_API_URL=http://host.docker.internal:9999/v3 BREVO_SENDER_EMAIL=alerts@example.com
export LIVE_PAGE_URL=https://taiyebnirjhar.github.io/Pocket-guard/live/
supabase start -x vector,logflare,supavisor,postgres-meta,mailpit,imgproxy,storage-api,realtime,studio
supabase status -o env | grep -E '^(API_URL|PUBLISHABLE_KEY|DB_URL)=' | sed -e 's/^/E2E_/' -e 's/"//g' >>"${GITHUB_ENV:-/dev/stdout}"
