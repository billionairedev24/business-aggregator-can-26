# Consumer app (S-97)

`mobile/apps/consumer` — Northline for iPhone and Android: Expo SDK 57, React Native 0.86, expo-router, on the shared
`@northline/mobile-kit` (`mobile/packages/mobile-kit`). Design 01 (`design/01 Consumer App.dc.html`); the plan, the
conventions and the screen list are in [MOBILE_PLAN.md](../MOBILE_PLAN.md). The courier app next to it has its own
runbook ([courier-app.md](courier-app.md)); sign-in and tokens: [mobile-auth.md](mobile-auth.md).

> **Status (2026-10-02):** the app has **never run on a phone, simulator or emulator** — no macOS or Android SDK
> where it was built. What is proven: Jest + React Native Testing Library on an in-app fixture backend, `tsc`, ESLint,
> `expo export` of the iOS and Android bundles, `expo prebuild` of both native projects with checks of the generated
> manifests, entitlements and permissions, and the web build in headless Chromium. Never exercised: the Keychain /
> Keystore, the system-browser sign-in, location services, App Links / Universal Links, EAS Build and Submit, the stores,
> and (S-99) Stripe's React Native SDK — PaymentSheet, 3-D Secure and the bank's return link have never run, nor
> against a real Stripe account (none exists); the Jest tests mock the SDK.

## Contents

- [Layout](#layout) · [Run it](#run-it) · [Environments](#environments) · [Checks](#checks)
- [Sign-in](#sign-in) · [Payments](#payments-s-99) · [App Links](#app-links) · [Variants and build variables](#variants-and-build-variables)
- [EAS builds](#eas-builds) · [Store accounts](#store-accounts) · [Signing](#signing) · [Permissions](#permissions)
- [Troubleshooting](#troubleshooting)

## Layout

```
mobile/
  apps/consumer/        this app (MOBILE_PLAN.md § Layout)
  apps/courier/         the courier app (S-87)
  packages/mobile-kit/  DPoP session, api client, secure storage, theme from @northline/tokens, i18n, push hook point
  eslint.config.mjs     one lint config; colour literals are errors
make/mobile-consumer.mk the make targets below
.github/workflows/mobile-consumer.yml, ci/gitlab/mobile-consumer.yml   manual-only CI
```

## Run it

Node 22 and pnpm 10 (corepack). `make mobile-consumer-install` (or `cd mobile && pnpm install`).

**In a browser, without any server** — the fastest look:

```
make mobile-consumer-web     # EXPO_PUBLIC_FIXTURES=1 expo start --web; press w; use a 402 px wide window
```

The in-app fixture backend (`src/fixtures/`) answers like northline-auth and the api, with made-up places (Sample
Province, Sampleville). Sign-in succeeds at once; secure storage is in memory (a reload signs you out).

**On a phone or simulator, against your laptop's servers:**

1. The stack with real sign-in: `make up SERVICES="auth api bff-consumer consumer"` (the consumer site serves the
   sign-in page for passkeys, Google and Apple, and the `/app/oauth2redirect` page).
2. A development build (Expo Go can't load the app's native config): on a Mac `cd mobile/apps/consumer && npx expo
   run:ios`, with the Android SDK `npx expo run:android`, or an EAS `development` / `development-device` build.
3. Point it at your laptop by IP (a phone can't reach `localhost`):
   ```
   EXPO_PUBLIC_API_URL=http://192.168.1.20:8080/api/v1 EXPO_PUBLIC_AUTH_ISSUER=http://192.168.1.20:9000 \
   EXPO_PUBLIC_SITE_ORIGIN=http://192.168.1.20:3000 make mobile-consumer-start
   ```
   northline-auth's `AUTH_ISSUER` must be the same `http://192.168.1.20:9000` and its `CONSUMER_ORIGIN`
   `http://192.168.1.20:3000` (the registered redirect `${CONSUMER_ORIGIN}/app/oauth2redirect` must equal the app's).
   Cleartext http is allowed only in the development variant.

## Environments

One build per environment; the EAS profile sets the variables (`eas.json`), Metro inlines them:

| EAS profile | `APP_VARIANT` | bundle id / package | `EXPO_PUBLIC_API_URL` | `EXPO_PUBLIC_AUTH_ISSUER` | `EXPO_PUBLIC_SITE_ORIGIN` |
|---|---|---|---|---|---|
| `development`, `development-device` | development | `ca.northline.app.dev` | `https://api.dev.northline.ca/api/v1` | `https://auth.dev.northline.ca` | `https://dev.northline.ca` |
| `preview` | preview | `ca.northline.app.preview` | `https://api.staging.northline.ca/api/v1` | `https://auth.staging.northline.ca` | `https://staging.northline.ca` |
| `production` | production | `ca.northline.app` | `https://api.northline.ca/api/v1` | `https://auth.northline.ca` | `https://northline.ca` |

`app.config.ts` refuses a preview or production build without https on all three, and a production build with
`EXPO_PUBLIC_FIXTURES=1`. No secret is ever built into the app (it is a public OAuth client).

## Checks

| make target | what |
|---|---|
| `mobile-consumer-check` | what the manual CI job runs: lint, typecheck, test, export |
| `mobile-consumer-lint` | ESLint over the apps and the kit, warnings fail; colour literals are errors |
| `mobile-consumer-typecheck` | `tsc --noEmit` (strict, `noUncheckedIndexedAccess`) for the app and the kit |
| `mobile-consumer-test` | Jest + RNTL: the shell, tabs, header, states, guest id, sign-in, en/fr parity, region and colour rules, the screen list against the design and MOBILE_PLAN, the native config against the auth server's client registration; the kit's tests |
| `mobile-consumer-export` | `expo export` of the iOS and Android Hermes bundles — Metro resolves and Hermes compiles everything |
| `mobile-consumer-native-check` | `expo prebuild` of both platforms for the development and production variants, then checks: bundle ids, the OAuth redirect scheme, the verified App Link, associated domains, location-while-in-use only, no camera / microphone / storage / backup, cleartext only in development, French localisation, privacy manifest; the generated `android/` and `ios/` are deleted |
| `mobile-consumer-web-smoke` | the web build on the fixture backend in headless Chromium at 402 × 874: the journeys built so far (A; B with the payment stand-in), the tabs, no horizontal scroll, no page errors (screenshots in `smoke-out/`) |
| `mobile-consumer-eas-build` | queues an EAS Build (`EAS_PROFILE`, `EAS_PLATFORM`, `EAS_FLAGS`) |

CI: `.github/workflows/mobile-consumer.yml` (Actions › mobile-consumer › Run workflow) and
`ci/gitlab/mobile-consumer.yml` (`PIPELINE_PART=mobile-consumer`), **manual only** ([ci.md](ci.md)).

## Sign-in

Client `mobile-consumer` (public, PKCE S256, DPoP; [mobile-auth.md](mobile-auth.md)). Tokens are bound to a P-256 key
the app creates at sign-in and keeps in the Keychain / Keystore-encrypted storage (a software key, like the courier
app's — the Secure Enclave / StrongBox key is a follow-up); the refresh token (30 days, rotating) is stored before
anything from a refresh answer is used; one refresh at a time.

- **On the app's own screens** (S-98: Create account, Sign in): northline-auth's JSON sign-in API in the app's cookie
  session, then the authorization code on the claimed https redirect — [mobile-auth.md § On the app's own
  screens](mobile-auth.md#on-the-apps-own-screens-consumer-app-s-98). Create account: mobile, full name, email, terms
  → a 6-digit code by SMS (or a call) → an authenticator app or "SMS code · backup only" (no second factor, S-62) →
  Location. Sign in: email or mobile → a code to the account's phone. The redirect URI
  `${EXPO_PUBLIC_SITE_ORIGIN}/app/oauth2redirect` must equal the registered `${CONSUMER_ORIGIN}/app/oauth2redirect`.
- **In the system browser** (Sign in › "Sign in with a passkey", "Continue with Apple" / "Google"; RFC 8252): the authorization request opens in
  ASWebAuthenticationSession / Custom Tabs; northline-auth sends `mobile-consumer` to the **consumer site's** sign-in
  page (`northline.auth.consumer-clients`, S-97), where passkeys, Google and Apple work; the code comes back to
  `ca.northline.app:/oauth2redirect`.
- **Sign-out** (the You tab, S-98): the push hook (`PushHooks.signingOut`), then `POST /oauth2/revoke` (ends the sign-in, S-20),
  then the key and tokens are deleted. A sign-in that ends on the server (refresh refused) shows "Your sign-in ended".

## Payments (S-99)

Paying (Journey B's Payment screen) follows the consumer web (S-51): `POST /me/checkouts` opens one manual-capture
PaymentIntent per order line plus one for the delivery fee, the phone authorizes them, `POST /me/checkouts/{id}/place`
places the order. Which provider runs them is the **server's** `northline.payments` configuration
([stripe.md](stripe.md)); the api answers it with each checkout (`payment.provider`, `payment.publishableKey`), so
**no Stripe key or variable is built into the app** and nothing changes per environment.

| api says | the app |
|---|---|
| `stripe` | Stripe's React Native SDK (`@stripe/stripe-react-native`, the version Expo SDK 57 pins; a native module autolinked by `expo prebuild`). New card → Stripe's PaymentSheet (card entry is native, outside the app's JS); saved card (`GET /me/payment-methods`) → its PaymentMethod id; 3-D Secure is Stripe's own native screen; a bank app returns to `ca.northline.app://stripe-redirect` (route `app/stripe-redirect.tsx` hands it to the SDK) |
| `fake` (local, the fixture backend) | nothing is collected: the design's bank step stands in, then the order is placed |

Paying with a sign-in that had no second factor asks for a **step-up** (S-51): the authenticator app's 6-digit code,
checked by northline-auth in the app's auth session (`POST /api/auth/step-up/totp`, the platform cookie store), sent to
the api as `X-Step-Up`. Passkeys need a native module the app doesn't have; an account with no second factor is sent
to Security on the consumer site.

Apple Pay / Google Pay: not offered — they need an Apple merchant id (and the config plugin's
`merchantIdentifier`) and Google Pay enabled on the Stripe account. The SDK's card scanner would need the camera,
which the app keeps out (`blockedPermissions`), so it is off.

## App Links

The consumer site proves which apps may open its links and receive the OAuth redirects on it
(`web/apps/consumer/server/app-links.mjs`, served by the node server and the Vite dev server):

| path | content | needs |
|---|---|---|
| `/.well-known/apple-app-site-association` | `applinks`: `<team>.<bundle id>` claims `/app/*` (consumer app) or `/courier/*` (courier app); `webcredentials`: the apps may use the site's passkeys | `NL_APPLE_TEAM_ID`; ids from `NL_IOS_BUNDLE_IDS` |
| `/.well-known/assetlinks.json` | `handle_all_urls` + `get_login_creds` for each package with the signing certificate's SHA-256 | `NL_ANDROID_CERT_SHA256`; packages from `NL_ANDROID_PACKAGES` |
| `/app/oauth2redirect`, `/courier/oauth2redirect` | a small "Open the Northline app" page (no-store, no Referer) for when no app opened the link | — |

The chart sets the four variables on the consumer app from `mobileApps` (values.yaml; values-dev / values-staging set
the `.dev` / `.preview` ids). Until the team id or a fingerprint is set, that file answers 404 and the apps use the
custom scheme. One-time setup per environment:

1. Apple: the team id (Membership details) → `mobileApps.appleTeamId`. The app's `associatedDomains`
   (`applinks:` / `webcredentials:` + the site host) come from `EXPO_PUBLIC_SITE_ORIGIN`; EAS enables the Associated
   Domains capability on the bundle id.
2. Google: Play Console › App integrity › App signing key certificate SHA-256 (and, for internal APKs, EAS's upload
   key: `eas credentials`) → `mobileApps.androidCertSha256`.
3. Check: `curl -s https://<zone>/.well-known/apple-app-site-association | jq` and Google's
   `https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://<zone>&relation=delegate_permission/common.handle_all_urls`.
   Both files must answer 200 without a redirect.

## Variants and build variables

`app.config.ts` is the only source of both native projects. Variants: development / preview / production (own bundle
id and name, so all three install side by side). Every variant uses the one registered redirect
`ca.northline.app:/oauth2redirect` (the server matches exactly): on Android keep one variant installed at a time when
testing the browser sign-in, or Android may ask which app opens it. `NL_IOS_BUILD_NUMBER` / `NL_ANDROID_VERSION_CODE`
only for local builds (EAS keeps version numbers remotely, `appVersionSource: remote`).

## EAS builds

One-time setup (an owner of the Expo organisation):

1. `cd mobile/apps/consumer && npx eas-cli login && npx eas-cli init` → the project id; set `EAS_CONSUMER_PROJECT_ID`
   and `EAS_OWNER` as GitHub Actions *variables* (GitLab: `EAS_PROJECT_ID` on the pipeline).
2. An access token (Expo › Access tokens) → GitHub secret / GitLab masked variable `EXPO_TOKEN` (shared with the
   courier app).
3. `npx eas-cli credentials` per platform and profile ([Signing](#signing)).
4. `submit.production` in `eas.json` (App Store Connect app id, Apple team id) once the store records exist.

Build: `make mobile-consumer-eas-build EAS_PROFILE=preview` (both platforms; `EAS_PLATFORM=ios|android`), or Actions ›
mobile-consumer › Run workflow with `eas=preview`. Preview = internal distribution (registered iOS devices, Android
APK); production = store builds with auto-incremented build numbers; `npx eas-cli submit -p ios|android --profile
production`. Over-the-air updates: `runtimeVersion` is the native fingerprint (`npx eas-cli update --channel preview`).
The store release pipeline and listings are S-103.

## Store accounts

| account | needed for |
|---|---|
| Expo organisation (EAS) | builds, credentials, updates |
| Apple Developer Program (organisation) | signing, TestFlight, App Store; the team id for App Links |
| App Store Connect record `ca.northline.app` | TestFlight, release, App Privacy |
| Google Play Console (organisation) | testing tracks, release, Data safety; the app-signing certificate for App Links |

## Signing

- **iOS:** distribution certificate and profiles per bundle id, created and kept by EAS (`credentialsSource:
  remote`); the Associated Domains capability on each bundle id. Never commit `.p12`, `.p8`, `.mobileprovision`.
- **Android:** an upload keystore per package kept by EAS; Google Play App Signing holds the app-signing key (its
  SHA-256 goes into `assetlinks.json`). Back up the upload keystore into the secrets manager. Never commit keystores.

## Permissions

| permission | when the app asks | en (`app.config.ts`) | fr-CA (`locales/fr.json`) |
|---|---|---|---|
| Location while using (`NSLocationWhenInUseUsageDescription`, `ACCESS_FINE_LOCATION` / `COARSE`) | Delivery address › "Use my location" (S-98), never at launch; once allowed, the app also uses it to name where you are until you save an address | Northline uses your location to suggest your delivery address and show the shops and providers that serve it. It is used only while you have the app open and is never shared with businesses. | Northline utilise votre position pour suggérer votre adresse de livraison et afficher les commerces et prestataires qui la desservent. Elle n’est utilisée que lorsque l’app est ouverte et n’est jamais transmise aux entreprises. |

No background location, camera, microphone, storage or motion: removed from the merged Android manifest and kept
out of Info.plist (checked by `mobile-consumer-native-check`). `allowBackup=false`. Notifications are S-102's.
App Privacy / Data safety: name, email, phone, address, precise location, user id — linked to the person, app
functionality only, no tracking (the privacy manifest in `app.config.ts`).

## Troubleshooting

| symptom | cause → fix |
|---|---|
| "Sign-in was cancelled" at once on Android | another installed variant took `ca.northline.app:` → keep one variant installed |
| the browser sign-in lands on the Studio's page | northline-auth older than S-97 (`mobile-consumer` not in `northline.auth.consumer-clients`) |
| sign-in loops back | the phone can't reach `AUTH_ISSUER` / `CONSUMER_ORIGIN`, or `AUTH_ISSUER` ≠ `EXPO_PUBLIC_AUTH_ISSUER` |
| "Signing in didn't finish" after the code / second factor | no code on the https redirect: `EXPO_PUBLIC_SITE_ORIGIN` ≠ the auth server's `CONSUMER_ORIGIN` (the redirect isn't registered), or the platform cookie store dropped the auth session → "Finish signing in", else start again |
| `/.well-known/apple-app-site-association` 404 | `mobileApps.appleTeamId` not set for the environment |
| `make mobile-consumer-export` can't resolve `@northline/tokens` | `web/packages/tokens` missing from the checkout (the kit links it) |
| "We couldn't take the payment" at once on Payment with `stripe` | the api's `STRIPE_PUBLISHABLE_KEY` is empty (the app gets no key) → set it with the secret key ([stripe.md](stripe.md)) |
| Payment says the phone can't confirm the second factor | no auth session on this phone (signed in in the system browser, or it expired) → sign in again in the app, or pay on the site |
| `native-check` leaves `package.json` changed | it was interrupted: `git checkout mobile/apps/consumer/package.json` (prebuild rewrites its scripts) |
