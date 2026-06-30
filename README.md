# Knock Knock

**Video calls you'll actually want to make.** Don't ring people — *knock*.
Your taps travel in real time, the other phone feels your knocking rhythm,
and nobody knows who's at the door until they answer. Phone-number-only
signup, warm eggshell-and-espresso design, no ads, no tracking.

Knock Knock is **open source**. Found a bug or have an idea?
[File an issue](https://github.com/viraatdas/knock-knock/issues) — PRs welcome.

- **Get it:** "Knock Knock - Video Chat" on the iOS App Store (TestFlight for
  the latest builds).
- **Clients:** native iOS (SwiftUI) + Android (Kotlin/Compose), using CallKit
  and Android call-style notifications for background ringing.
- **Media:** WebRTC through a self-hosted LiveKit SFU + coturn (STUN/TURN).
- **Backend:** Rust (axum + sqlx + tokio), Postgres, Redis.
- **Scope today:** 1:1 + group video/audio calls, knock-style ringing.
- **History note:** the project was born as "Slide" — internal crate names and
  the `app.exla.slide` bundle id keep that prefix.

## Repository layout

```
crates/
  slide-core/   shared models, JWT, OTP, phone E.164, TURN creds   (lib, tested)
  slide-api/    axum control plane: auth, /me, contacts, calls, ws (binary)
  slide-sfu/    webrtc-rs SFU: signaling + selective forwarding     (binary)
ios/            SwiftUI app                          (see ios/README.md)
android/        Jetpack Compose app                  (see android/README.md)
web/            Next.js marketing site               (see web/README.md)
deploy/fly/     Fly.io configs (api, sfu, coturn)
scripts/        smoke.sh (API e2e), deploy-backend.sh
AGENTS.md       internal API, design, deploy, SFU, and release notes
migrations/     in crates/slide-api/migrations (embedded at build time)
```

## Run the backend locally

```bash
docker compose up -d              # Postgres, Redis, coturn
cp .env.example .env              # (a dev .env is already present)
livekit-server --dev --bind 0.0.0.0 # media, in another shell
cargo run -p slide-api            # http://localhost:8080  (runs migrations)
# cargo run -p slide-sfu          # legacy clients only
```

The maintained clients use LiveKit; `slide-sfu` remains only as a legacy
fallback. Install the local server with `brew install livekit` (or follow the
official LiveKit install instructions). Production must set `LIVEKIT_URL`,
`LIVEKIT_API_KEY`, and `LIVEKIT_API_SECRET` to one matching deployment.

Smoke-test the whole phone-auth + call flow (uses the dev OTP):

```bash
./scripts/smoke.sh
```

## Test

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug
cd ../web && npm run build
```

## Deploy

Backend -> Fly.io/AWS; landing site -> Vercel; apps -> App Store / Play Store
via fastlane (gated on paid developer accounts). See the per-platform READMEs;
maintainer details live in `AGENTS.md`.

## Design

Pure-white backgrounds, near-black type/actions, gray secondary text, hairline
dividers, and restrained red for destructive actions. The same quiet, precise
system is used on every surface; the exact tokens live in `AGENTS.md`.
