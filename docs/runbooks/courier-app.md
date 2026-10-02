# Courier app (S-87)

The iOS and Android app couriers use on a shift: sign in, start and end the shift, follow the run's stops in order,
pick up, and drop off with proof of delivery (a photo, the customer's signature or their PIN). It is built with
Expo + React Native + expo-router, like billionairedev24/samop-inv-ship-26's `apps/mobile`, and lives in `mobile/`.
For mobile developers first, then release managers.

> **Status (2026-10-01): never run on a phone.** No iPhone, Android device, simulator or emulator has run this app,
> and no native build (Xcode, Gradle, EAS) has been made: the machine it was written on has no macOS or Android SDK.
> What has run: Jest + React Native Testing Library (screens against an in-app stand-in of the api), `tsc`, ESLint,
> `expo export` of the iOS and Android JS bundles (Hermes bytecode), and the web build in headless Chromium. Never
> exercised: the camera, background location and its foreground service, the Keychain / Keystore, the system-browser
> sign-in against a real northline-auth, uploads from a phone, EAS Build/Submit, the stores. The S-87 acceptance
> criterion ("a courier completes a run end to end in staging") has **not** been met yet — see
> [Staging acceptance](#staging-acceptance).

Other runbooks: [fulfilment.md](fulfilment.md) (the courier API, runs, proof) · [mobile-auth.md](mobile-auth.md)
(PKCE, DPoP, refresh) · [ci.md](ci.md) · [regions.md](regions.md)

## Contents

- [Layout](#layout)
- [Run it](#run-it)
- [Checks](#checks)
- [How it behaves](#how-it-behaves)
- [Variants and build variables](#variants-and-build-variables)
- [EAS builds](#eas-builds)
- [Store accounts](#store-accounts)
- [Signing](#signing)
- [Permissions and their texts](#permissions-and-their-texts)
- [Staging acceptance](#staging-acceptance)
- [Troubleshooting](#troubleshooting)

## Layout

```
mobile/                         a pnpm workspace of its own (not web/): Expo pins its own React, Metro and Jest
├── apps/courier/               @northline/courier — the app
│   ├── app/                    expo-router routes: sign-in, oauth2redirect, (app)/{index (shift), run, stops/[id]/{index,pickup,dropoff}, account}
│   ├── src/                    api client of the courier API, outbox, location (pinger + background task), signature PNG, fixtures, i18n (en, fr-CA)
│   ├── __tests__/              Jest + RNTL
│   ├── e2e/smoke.mjs           the web build in headless Chromium
│   ├── app.config.ts           the single source of both native projects (ios/ and android/ are generated, never committed)
│   ├── eas.json                EAS profiles: development, development-device, preview, production
│   └── locales/fr.json         iOS permission texts in French
└── packages/mobile-kit/        @northline/mobile-kit — shared with the consumer app (phase 4): DPoP session (PKCE,
                                nonces, rotating refresh), api client, secure storage, theme from @northline/tokens, i18n
```

`@northline/mobile-kit` links `web/packages/tokens` (its `tokens.json`): colours, radii, spacing and fonts come from
the same file as the web, and the ramps are derived in OKLCH exactly as `derived.css` does. No colour literal is
allowed in the app (ESLint and a test).

## Run it

Node 22 and pnpm 10 (corepack). `make courier-install` (or `cd mobile && pnpm install`).

**In a browser, without any server** — the fastest look at every screen:

```
make courier-web          # EXPO_PUBLIC_FIXTURES=1 expo start --web; press w
```

The in-app fixture backend (`src/fixtures/server.ts`) answers like northline-auth and the courier API: sign-in
succeeds at once, the shift is on, run R-701 has two pickups and two drop-offs, the PIN is `4821`, pings faster than
2 s get 429. Camera, background location and secure storage are phone-only (the web build says so).

**On a phone or simulator, against your laptop's servers:**

1. Start the stack with real sign-in: `make up SERVICES="auth api bff-consumer consumer"` (the courier signs in on the
   consumer site's page, `CONSUMER_ORIGIN/sign-in`).
2. Make yourself a courier (console API, staff persona Priya; or SQL on a disposable database):
   `POST /api/v1/console/fulfilment/couriers {userId, market, vehicle}` then
   `POST /api/v1/console/fulfilment/couriers/{id}/shifts {startsAt, endsAt}` ([fulfilment.md](fulfilment.md#console-api-s-81s-orders-monitor-and-delivery-ops-map)).
3. A development build is needed (Expo Go cannot run the background location task): on a Mac
   `cd mobile/apps/courier && npx expo run:ios`, with the Android SDK `npx expo run:android`, or an EAS
   `development` / `development-device` build ([EAS builds](#eas-builds)).
4. Point it at your laptop by IP (a phone can't reach `localhost`):
   ```
   EXPO_PUBLIC_API_URL=http://192.168.1.20:8080/api/v1 EXPO_PUBLIC_AUTH_ISSUER=http://192.168.1.20:9000 make courier-start
   ```
   northline-auth's `AUTH_ISSUER` must then be the same `http://192.168.1.20:9000`, and `CONSUMER_ORIGIN` reachable
   from the phone. Cleartext http is allowed only in the development variant.

## Checks

| make target | what |
|---|---|
| `courier-check` | everything the manual CI job runs: the four below except the smoke |
| `courier-lint` | ESLint over the app and the kit, warnings fail (`--max-warnings=0`) |
| `courier-typecheck` | `tsc --noEmit` (strict, `noUncheckedIndexedAccess`) |
| `courier-test` | Jest + RNTL: the kit (PKCE vectors, DPoP proofs verified with the public key, nonce retry, single-flight rotating refresh, sign-out, the api client's 401 refresh, theme contrast, i18n) and the app (outbox, pinger, signature PNG, every screen on the fixture backend, en/fr parity, region and colour rules, the native config against the auth server's client registration) |
| `courier-export` | `expo export` of the iOS and Android bundles — proves Metro resolves and Hermes compiles everything |
| `courier-web-smoke` | the web build on the fixture backend in headless Chromium at 390 px: sign in, run in order, pickup, PIN drop-off, French, no horizontal scroll, no page errors (screenshots in `smoke-out/`) |
| `courier-eas-build` | queues an EAS Build (`EAS_PROFILE`, `EAS_PLATFORM`, `EAS_FLAGS`) |

CI: `.github/workflows/courier.yml` and `ci/gitlab/courier.yml`, **manual only** ([ci.md](ci.md)).

## How it behaves

- **Sign-in:** the `courier-app` client (public, PKCE S256, DPoP) in the system browser (`ASWebAuthenticationSession` /
  Custom Tabs, never a WebView), back on `ca.northline.courier:/oauth2redirect`. northline-auth sends couriers to the
  consumer site's sign-in page (`consumer-clients`), never the Studio's. The refresh token (12 h, one shift) and the
  key are in the Keychain / Keystore-encrypted storage, readable after the first unlock (so the background task can
  refresh with the phone locked), never backed up or moved to another device. A person who is not a courier sees
  "This account isn't set up as a Northline courier" (403 `not_a_courier`).
- **Shift:** status, start (from 15 min before), end (blocked while a run is open). Starting and ending need a
  connection; the screen says so offline.
- **Run and stops:** the api's order (pickups first). Times show in the run's market zone, read from the region model
  (`GET /api/v1/geo/regions`), never a zone in code. A stop shows the shop or street, the unit, the customer's note
  and the order reference — no customer name or phone number (the api gives none). There is no in-app call or message
  yet; the stop says to contact the dispatcher.
- **Offline (the outbox):** arrive, pickup, proof upload and drop-off are saved on the phone first (AsyncStorage, the
  proof file in the app's documents) and sent in order, one at a time, each with its own `Idempotency-Key` repeated on
  every retry. No answer, 408, 429 or 5xx: retried with back-off (1 s doubling to 60 s, or `Retry-After`) and at once
  when the connection comes back. A refusal (wrong PIN, shop not packed): dropped with the api's message, together with
  the later actions for the same stop. The screens show unsent stops as "Saved on phone". Sign-out warns when actions
  are still unsent. The api's stop actions are idempotent by state, so replays change nothing ([fulfilment.md](fulfilment.md)).
- **Location:** only while a run is open. The run screen explains why and what is kept before the OS asks. "Always":
  OS location updates to a background task with a foreground-service notification on Android ("On a run · Sharing
  your location until your run ends") and the blue indicator on iOS. "While using": only while the app is open. A ping
  at most every 4 s (the api allows one per 2 s; customers see moves ≤ 5 s apart), later on 429 `Retry-After`; only
  the newest fix is sent, a failed one is dropped (nothing about positions is stored on the phone); no run any more or
  409 `not_on_shift` stops the updates. The api keeps the latest position only (S-88).
- **Proof:** photo (expo-camera, scaled to 1600 px JPEG), signature (strokes rasterised to a PNG in JS — no screenshot
  module), or the 4-digit PIN (checked on the phone for the format, by the api for the value).

## Variants and build variables

| variant (`APP_VARIANT`) | bundle id / package | name | EAS profile | api / auth |
|---|---|---|---|---|
| development | `ca.northline.courier.dev` | Courier (Dev) | `development`, `development-device` | `https://api.dev.northline.ca/api/v1`, `https://auth.dev.northline.ca` |
| preview | `ca.northline.courier.preview` | Courier (Preview) | `preview` | staging |
| production | `ca.northline.courier` | Northline Courier | `production` | prod |

Build-time variables (EAS profile `env`, or your shell; none of them is a secret, all end up in the app):

| variable | default | meaning |
|---|---|---|
| `APP_VARIANT` | development | the table above |
| `EXPO_PUBLIC_API_URL` | `http://localhost:8080/api/v1` | the api; https required for preview/production |
| `EXPO_PUBLIC_AUTH_ISSUER` | `http://localhost:9000` | northline-auth (`AUTH_ISSUER`) |
| `EXPO_PUBLIC_FIXTURES` | 0 | 1 = the in-app fixture backend; a production build refuses it |
| `EAS_PROJECT_ID`, `EAS_OWNER` | placeholder, `northline` | from `eas init`; EAS Update's URL |
| `NL_IOS_BUILD_NUMBER`, `NL_ANDROID_VERSION_CODE` | — | local builds only; EAS numbers builds remotely |

All variants use the one redirect registered for `courier-app` (`ca.northline.courier:/oauth2redirect`; the server
matches exactly). iOS hands it to the app that opened the browser session; on Android keep one variant installed, or
register a variant's own scheme under `northline.oauth.clients.courier-app.redirect-uris` first. No server variable is
new for S-87.

## EAS builds

One-time setup (an owner of the Expo organisation):

1. `cd mobile/apps/courier && npx eas-cli login && npx eas-cli init` → note the project id; set `EAS_COURIER_PROJECT_ID`
   (the older `EAS_PROJECT_ID` still works for this workflow) and `EAS_OWNER` as GitHub Actions *variables* (and GitLab
   CI/CD variables).
2. Create an access token (Expo › Access tokens) → GitHub secret / GitLab masked variable `EXPO_TOKEN`.
3. `npx eas-cli credentials` per platform and profile: let EAS create the iOS distribution certificate and profiles
   and the Android upload keystore ([Signing](#signing)).
4. Fill `submit.base.ios` in `eas.json` (App Store Connect app id, Apple team id) once the store records exist —
   the whole store release (submit profiles, listings, privacy answers, rollout, rollback) is
   [mobile-release.md](mobile-release.md) (S-103).

Build: `make courier-eas-build EAS_PROFILE=preview` (both platforms; `EAS_PLATFORM=ios|android`), or Actions ›
courier › Run workflow with `eas=preview`. Preview builds are internal distribution (an install link: iOS devices must be
registered with `eas device:create`; Android gets an APK). Production builds are store builds (app-bundle / IPA) with
auto-incremented build numbers; `make mobile-eas-build MOBILE_APP=courier EAS_PROFILE=production` builds and submits
them to TestFlight and the Play internal track (submit profile `internal`). The version is `package.json`'s.
Over-the-air updates: `runtimeVersion` is the native fingerprint, so a JS update only reaches binaries with the same
native layer (`make mobile-update MOBILE_APP=courier UPDATE_CHANNEL=preview UPDATE_MESSAGE=…`;
[mobile-release.md § Over-the-air updates](mobile-release.md#over-the-air-updates)).

## Store accounts

| account | who | needed for |
|---|---|---|
| Expo organisation (EAS) | Northline's engineering lead | builds, credentials storage, updates; a paid plan for build concurrency |
| Apple Developer Program, as an **organisation** (D-U-N-S number, the legal entity) | the legal entity's signer | signing, TestFlight, App Store |
| App Store Connect app record `ca.northline.courier` | an Admin / App Manager | TestFlight and release; privacy labels; review notes for background location |
| Google Play Console, **organisation** account (D-U-N-S, verified) | the legal entity | internal testing track, release, Data safety, the location-permissions declaration |

Couriers are Northline's workforce, not the public: consider an unlisted App Store app (or Apple Business Manager custom
app) and a Play internal/closed track or managed private app. Store review still applies, including the background
location declarations below.

## Signing

- **iOS:** a distribution certificate and provisioning profiles per bundle id, created and stored by EAS (remote
  credentials, `credentialsSource: remote`). Development builds for devices need the devices registered
  (`eas device:create`). Never commit `.p12`, `.p8` or `.mobileprovision` files (`mobile/.gitignore`).
- **Android:** an upload keystore per package, generated and kept by EAS; Google Play App Signing holds the app
  signing key. Download a backup of the upload keystore (`eas credentials`) into the secrets manager — losing it means
  a Play support request. Never commit `.jks` / `.keystore` files.
- **App Links / Universal Links** (`https://<consumer host>/courier/oauth2redirect`) need the team id + bundle id and the
  package + signing-certificate SHA-256 in `apple-app-site-association` / `assetlinks.json` on the consumer host; the
  consumer site serves them since S-97 once `mobileApps` is configured ([mobile.md § App Links](mobile.md#app-links));
  the courier app still uses the custom scheme (it has no `associatedDomains` yet).

## Permissions and their texts

| permission | when the app asks | en (`app.config.ts` `PERMISSION_TEXT`) | fr-CA (`locales/fr.json`) |
|---|---|---|---|
| Camera (`NSCameraUsageDescription`, `CAMERA`) | Drop-off › Photo › Allow camera | Northline Courier uses the camera to photograph a delivery at the door as proof of delivery. | Northline Coursier utilise l’appareil photo pour photographier une livraison à la porte comme preuve de livraison. |
| Location while using (`NSLocationWhenInUseUsageDescription`, `ACCESS_FINE_LOCATION`) | Run › Share location (after the app's own explanation) | While you are on a run, Northline Courier shares your location so customers can follow their order on its way. Only your latest position is kept, never a history. | Pendant une tournée, Northline Coursier partage votre position pour que les clients suivent leur commande en route. Seule votre dernière position est conservée, jamais un historique. |
| Location always (`NSLocationAlwaysAndWhenInUseUsageDescription`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE_LOCATION`) | right after "while using" | While you are on a run, Northline Courier keeps sharing your location when the app is in the background, so customers can follow their order on its way. Sharing stops when your run ends; only your latest position is kept. | Pendant une tournée, Northline Coursier continue de partager votre position quand l’app est en arrière-plan, pour que les clients suivent leur commande en route. Le partage s’arrête à la fin de la tournée; seule votre dernière position est conservée. |

Blocked from the merged Android manifest: `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, external storage and media reads.
`allowBackup=false`. Store declarations to prepare:

- **Google Play:** the location-permissions declaration (background location: core function — "customers follow
  their delivery while the courier is on a run"; a short video of the run screen, the explanation card and the
  notification), the foreground-service type `location` declaration (Android 14), Data safety (precise location and
  photos: collected, linked, app functionality, not shared for advertising; encrypted in transit).
- **App Store:** `UIBackgroundModes: location` (set by the expo-location plugin) with review notes and a demo courier
  account on a staging-like environment; App Privacy: precise location, photos, user id — linked, app functionality,
  no tracking (also in the privacy manifest in `app.config.ts`).

## Staging acceptance

"A courier completes a run end to end in staging" — not done yet (no device, no EAS project). The steps:

1. A preview build installed on a test phone (`make courier-eas-build EAS_PROFILE=preview`).
2. In staging: a person account for the courier; `POST /console/fulfilment/couriers` and a shift that covers now.
3. Two shop orders with pooled delivery in that market past the cut-off; the shops mark them packed.
4. On the phone: sign in (consumer sign-in page) → Start shift → `POST /console/fulfilment/plan` (or wait for the
   planner) → the run appears → Share location ("Always") → arrive and pick up each shop → drop off one with a photo,
   one with the customer's PIN. Toggle airplane mode during one drop-off: it shows "Saved on phone" and is sent when
   the connection is back.
5. Check: the orders are `delivered`, the customer's tracking showed the courier moving, the console's couriers list
   shows only the latest position, End shift works.

## Troubleshooting

| symptom | cause → fix |
|---|---|
| "Sign-in was cancelled" straight away on Android | another installed variant took the redirect → keep one variant, or register a variant scheme |
| sign-in loops back to the sign-in page | the phone can't reach `AUTH_ISSUER` / `CONSUMER_ORIGIN`, or `AUTH_ISSUER` differs from `EXPO_PUBLIC_AUTH_ISSUER` |
| every call 401 after a while | refresh refused (`invalid_grant`: the sign-in ended or a refresh token was presented twice) → the app shows "Your sign-in ended"; unsent actions wait for the next sign-in |
| "Couldn't save: This shop hasn't packed the order yet." | the api's 409 `not_packed`; wait for the shop, then pick up again |
| no position on the customer's tracking | location "While using" with the screen off, or denied → Account/Run shows it; Settings › Location › Always |
| `make courier-export` fails resolving `@northline/tokens` | `web/packages/tokens` missing from the checkout (the kit links it) |
