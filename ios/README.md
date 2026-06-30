# Slide — iOS

Native SwiftUI client. White background, thin near-black type
(see `../AGENTS.md`); phone-number-only signup; built against the API contract
in `../AGENTS.md`.

## Requirements
- Xcode 26+ / Swift 5.9+ toolchain.
- [XcodeGen](https://github.com/yonyz/XcodeGen) (`brew install xcodegen`) — the
  `.xcodeproj` is generated from `project.yml`, not committed.

## Build & run (Simulator — no account needed)
```bash
cd ios
xcodegen generate
xcodebuild -scheme Slide -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -skipMacroValidation build
# or open Slide.xcodeproj in Xcode and ⌘R
```
Verified: **BUILD SUCCEEDED**; the app launches in the Simulator (see
`screenshots/`). The LiveKit package resolves and links.

## Architecture
- **App/** — `SlideApp` (@main), `RootView`, `MainTabView`, `AppState`, `ActiveCall`.
- **DesignSystem/** — `Theme.swift` (every AGENTS.md design token), `Components.swift`
  (PrimaryButton, HairlineDivider, AvatarCircle, UnderlineField, Wordmark, …).
- **Models/** — Codable types matching the camelCase API JSON.
- **Networking/** — `Config` (base URL), `Keychain`/`TokenStore`, `APIClient`
  (actor; every endpoint; 401 → silent refresh), `APIError`, `MockData`.
- **Services/** — `SignalingClient` (app WS + reconnect/backoff),
  `CallService` protocol + simulator `MockCallService` + production
  `RealCallService` (LiveKit SFU), `PushService`, and `CallKitManager`.
- **Features/** — Onboarding (Welcome/Phone/Code/Name), Calls (Recents),
  Contacts (+ sheet), InCall (+ incoming), Profile.

### Incoming-call lifecycle

- Real invitations arrive over both signaling WebSocket and PushKit and are
  deduplicated by server `callId`; a live socket never suppresses VoIP push.
- PushKit payloads are reported to CallKit before completion, buffered across a
  cold launch, and bounded by the server-provided `expiresAt` deadline.
- Standard APNs and VoIP tokens register separately through `/push/register`
  and are detached on logout or token rotation.
- CallKit answer/end/mute actions, media startup, and backend accept/leave are
  one idempotent state machine, including sibling-device acceptance.

Config: point `Config.baseURL` at the deployed API
(`https://<api-host>/v1`). Media defaults to the mock in Debug/Simulator and to
LiveKit in Release; `SLIDE_USE_REAL_WEBRTC=1` keeps the legacy override name for
running real media from a debug build.

## Bundle id
`app.exla.slide` (in `project.yml`). Must match the App ID registered in App Store
Connect.

## Ship it (TestFlight / App Store) — CLI

Use the **`/app-store-deploy` skill** (or run its script directly). It installs
fastlane, generates the project, archives, and uploads + submits.

```bash
../.claude/skills/app-store-deploy/deploy.sh build_sim   # sanity, no account
../.claude/skills/app-store-deploy/deploy.sh bootstrap   # create app record
../.claude/skills/app-store-deploy/deploy.sh beta        # -> TestFlight
../.claude/skills/app-store-deploy/deploy.sh release      # -> App Store + submit
```

### One-time setup (Apple requires the web UI once, ~60 s)
The skill prints this if the key is missing. Create an **App Store Connect API
key** at <https://appstoreconnect.apple.com/access/integrations/api> (role: App
Manager), download the `.p8`, then create `ios/fastlane/.asc.env` (gitignored):

```
ASC_KEY_ID=XXXXXXXXXX
ASC_ISSUER_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
ASC_KEY_PATH=$HOME/.appstoreconnect/private_keys/AuthKey_XXXXXXXXXX.p8
APPLE_TEAM_ID=XXXXXXXXXX
APP_IDENTIFIER=app.exla.slide
```

`APPLE_TEAM_ID` is at <https://developer.apple.com/account> → Membership.

### fastlane lanes (`fastlane/Fastfile`)
- `build_sim` — unsigned Simulator build (CI smoke).
- `bootstrap` — `produce` the App Store Connect app record (idempotent).
- `archive` — signed `.ipa` via `gym`.
- `beta` — archive + `upload_to_testflight`.
- `release` — archive + `upload_to_app_store` + submit for review.
- `tf_beta_meta`, `tf_beta_submit`, `tf_public_link`, `tf_invite` — external
  TestFlight tester setup. See `../AGENTS.md` for the operator sequence.

Listing copy is in `../store/listing.md`, privacy answers in
`../store/privacy.md`, screenshots in `screenshots/`.

## Gated on the paid Apple account (you have it)
1. **Signing** — set `APPLE_TEAM_ID`; Automatic signing (or `fastlane match` for
   multi-machine/CI). Archiving requires signing enabled.
2. **VoIP/PushKit** — for true background incoming-call ringing (CallKit is wired;
   needs the VoIP entitlement + APNs key).
3. **API key** — the one-time step above.
4. **Review** — Apple's human review (~1–2 days) after `release`.
