#!/usr/bin/env bash
# Deploy the Slide backend: Supabase Postgres + Fly (api, sfu, coturn).
# Prereqs: `fly auth login`, `supabase login`, secrets known.
# This is intentionally explicit so each step can be run/verified on its own.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "1) Ensure Fly apps exist (idempotent)…"
fly apps create slide-api  --machines 2>/dev/null || true
fly apps create slide-sfu  --machines 2>/dev/null || true
fly apps create slide-turn --machines 2>/dev/null || true

echo "2) Set shared secrets (edit before running for real)…"
cat <<'EOF'
  Run these once with real values:
    fly secrets set -a slide-turn TURN_SECRET="$TURN_SHARED_SECRET"
    fly secrets set -a slide-sfu  SFU_JWT_SECRET="$SFU_JWT_SECRET" \
        TURN_URIS="turn:slide-turn.fly.dev:3478?transport=udp" \
        TURN_SHARED_SECRET="$TURN_SHARED_SECRET"
    fly secrets set -a slide-api  DATABASE_URL="$SUPABASE_DB_URL" \
        REDIS_URL="$UPSTASH_REDIS_URL" JWT_SECRET="$JWT_SECRET" \
        SFU_JWT_SECRET="$SFU_JWT_SECRET" OTP_PEPPER="$OTP_PEPPER" \
        LIVEKIT_URL="$LIVEKIT_URL" LIVEKIT_API_KEY="$LIVEKIT_API_KEY" \
        LIVEKIT_API_SECRET="$LIVEKIT_API_SECRET" \
        SFU_PUBLIC_URL="wss://slide-sfu.fly.dev" \
        TURN_URIS="turn:slide-turn.fly.dev:3478?transport=udp" \
        TURN_SHARED_SECRET="$TURN_SHARED_SECRET" \
        SMS_PROVIDER="twilio" TWILIO_ACCOUNT_SID="$T_SID" \
        TWILIO_AUTH_TOKEN="$T_TOK" TWILIO_FROM_NUMBER="$T_FROM"
EOF

echo "3) Deploy coturn…"
fly deploy -c deploy/fly/coturn.fly.toml

echo "4) Deploy SFU…"
fly deploy -c deploy/fly/slide-sfu.fly.toml

echo "5) Deploy API (migrations run automatically on boot)…"
fly deploy -c deploy/fly/slide-api.fly.toml

echo "6) Health checks…"
curl -fsS https://slide-api.fly.dev/v1/health && echo " api ok"
curl -fsS https://slide-sfu.fly.dev/health && echo " sfu ok"
echo "✅ backend deployed"
