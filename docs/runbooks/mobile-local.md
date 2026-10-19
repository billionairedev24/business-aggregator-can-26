# The mobile apps on your phone, against your Mac

The consumer app (`mobile/apps/consumer`) and the courier app (`mobile/apps/courier`) on an iPhone, an Android phone,
the iOS Simulator or an Android emulator, talking to the stack `make up-all` runs on your Mac
([local.md § 6a](local.md#6a-run-everything-locally-with-your-own-postgres-valkey-and-grafana)). One command starts
Metro with the apps' API addresses pointed at your Mac; the auth server's `local` profile lets a phone on your Wi-Fi
sign in. Both apps wear the redesign (`docs/design/redesign-spec.md` § 2.4, § 2.5): the palette,
fonts and logo of `web/packages/tokens/brand.json`.

> **Status (2026-10-19):** built and checked on Linux (typecheck, Jest, ESLint, the auth server's integration tests
> for the LAN sign-in, the script's checks against stand-in servers). **Never run on a Mac, a phone, the Simulator or
> an emulator** — there is no macOS or Android SDK where it was built. Expect the first run to surface something;
> § Troubleshooting lists what is most likely.

## Contents

- [The short version](#the-short-version) · [Prerequisites](#1-prerequisites) · [The stack](#2-the-stack)
- [A development build, once](#3-a-development-build-once) · [Every day: make mobile-dev](#4-every-day-make-mobile-dev)
- [Sign in](#5-sign-in) · [Pay](#6-pay-a-test-card) · [The courier app](#7-the-courier-app) · [Expo Go](#8-expo-go)
- [How it works](#9-how-the-phone-reaches-your-mac) · [Troubleshooting](#10-troubleshooting)

## The short version

```sh
make up-all                                         # the stack, as you run it today
make mobile-dev-build APP=consumer TARGET=ios       # once: builds and installs the app in the iOS Simulator (Xcode)
make mobile-dev APP=consumer                        # every day: Metro for a phone on your Wi-Fi
```

Then open **Northline (Dev)** on the phone or Simulator, sign in as `amara.osei@example.com` with the code from
`grep 'Verification code' .run/logs/auth.log | tail -1`, and shop. `APP=courier` for the courier app.

## 1. Prerequisites

| what | why | how |
|---|---|---|
| Node 22, pnpm 10 | Metro, the Expo CLI | as for the web apps (`make setup`); `make mobile-dev` installs `mobile/` the first time |
| **Xcode** (App Store) with the iOS Simulator | builds the iOS development build; the Simulator | open Xcode once, accept the licence, Settings › Platforms › iOS; `xcode-select -p` must print a path inside Xcode.app. CocoaPods comes with Expo's prebuild (`brew install cocoapods` if it asks) |
| an **Apple ID** in Xcode (Settings › Accounts) | installing on your own iPhone (not needed for the Simulator) | a free Personal Team works for 7 days per install; see § 3 for the push entitlement it lacks |
| **Android Studio** (optional) | the Android emulator or a USB Android phone | SDK Platform 36, an emulator (Device Manager), `ANDROID_HOME` set and `platform-tools` on `PATH` (`adb devices` works) |
| your phone on the **same Wi-Fi** as the Mac | it reaches the Mac by its LAN address | a guest network or "client isolation" blocks it (§ 10) |
| Expo Go (optional) | a quick look without building (§ 8) | App Store / Play Store; it must support Expo SDK 57 |

## 2. The stack

`make up-all` as you run it today (your Postgres, Valkey and Grafana; [local.md § 6a](local.md#6a-run-everything-locally-with-your-own-postgres-valkey-and-grafana)).
What the phone needs from it:

| service | port | why |
|---|---|---|
| api | 8080 | everything the apps show |
| northline-auth (`local` profile) | 9000 | sign-in, tokens |
| consumer web (Vite dev server) | 3000 | the sign-in page and the consumer app's `/app/oauth2redirect` (the in-app sign-in lands there) |

The Java services listen on every interface already. The consumer web's dev server listens on `localhost` only unless
`NL_DEV_HOST=0.0.0.0` is set; `make mobile-dev` restarts it that way when it sees the phone can't reach it (it runs
`NL_DEV_HOST=0.0.0.0 scripts/stack.sh up consumer`). To make it permanent, put `NL_DEV_HOST=0.0.0.0` in
`web/apps/consumer/.env` — then every `make up-all` starts it reachable from your Wi-Fi (only do that on a network you
trust).

Pull the branch and run `make up-all` again after an update: northline-auth must include the `local` profile's
`northline.auth.local-network-devices: true` (§ 9).

## 3. A development build, once

The apps are **development builds** (`expo-dev-client`): their native code — the Keychain, Stripe, the camera, push,
background location, their own URL schemes — is compiled into an app you install once. After that, `make mobile-dev`
only serves JavaScript, and a code change reloads in a second.

| target | command | needs |
|---|---|---|
| iOS Simulator | `make mobile-dev-build APP=consumer TARGET=ios` | Xcode |
| your iPhone (USB, then Wi-Fi) | `make mobile-dev-build APP=consumer TARGET=device` | Xcode and your Apple ID; the phone in Developer Mode (Settings › Privacy & Security) and trusted |
| Android emulator | `make mobile-dev-build APP=consumer TARGET=android` | Android Studio, an emulator running |
| an Android phone (USB) | `make mobile-dev-build APP=consumer TARGET=device PLATFORM=android` | USB debugging on, `adb devices` lists it |

`mobile-dev-build` runs `npx expo run:ios` / `run:android` in the app's folder: Expo generates `ios/` / `android/`
(git-ignored), builds, installs **Northline (Dev)** (`ca.northline.app.dev`; the courier app **Courier (Dev)**,
`ca.northline.courier.dev`) and starts Metro. The first build takes 5–15 minutes; rebuild only when native
dependencies or `app.config.ts` change (`make mobile-consumer-clean`, or delete `mobile/apps/courier/ios` and
`android`, then build again).

**Your own iPhone with a free Apple ID:** the consumer app's development variant asks for the push entitlement
(`aps-environment`), which a free Personal Team can't sign. If Xcode says *"Personal development teams … do not
support the Push Notifications capability"*, either use the Simulator, sign in with a paid Apple Developer account, or
build in the cloud with EAS (`make mobile-consumer-eas-build EAS_PROFILE=development-device EAS_PLATFORM=ios`,
[mobile.md § EAS builds](mobile.md#eas-builds)) and install it from the link EAS gives you. In every case pick a
signing team in Xcode the first time (open `mobile/apps/consumer/ios/*.xcworkspace` › the target › Signing &
Capabilities) if the command stops at signing.

## 4. Every day: make mobile-dev

```sh
make mobile-dev APP=consumer                  # a phone on the same Wi-Fi (TARGET=device, the default)
make mobile-dev APP=consumer TARGET=ios       # the iOS Simulator: localhost
make mobile-dev APP=consumer TARGET=android   # the Android emulator: 10.0.2.2 (the emulator's name for your Mac)
make mobile-dev APP=courier                   # the courier app
make mobile-dev-check APP=consumer            # only the checks
```

It works out the address the phone uses for your Mac (TARGET=device: the Wi-Fi's address, `ipconfig getifaddr` on
the default route's interface; override with `MOBILE_HOST=192.168.1.20`), then:

1. **checks the stack on that address** — the api's health, auth's discovery document, the consumer site's
   `/app/oauth2redirect` — and says what to do for each one that doesn't answer (restarting the consumer site for the
   LAN, the firewall, a service that isn't running);
2. **starts Metro** for the development build (`expo start --dev-client`, `--lan` for a phone so the QR code and the
   dev client's link carry the LAN address) with `APP_VARIANT=development` and the apps' build variables set to your
   Mac:

   | variable | TARGET=device | ios | android |
   |---|---|---|---|
   | `EXPO_PUBLIC_API_URL` | `http://192.168.1.20:8080/api/v1` | `http://localhost:8080/api/v1` | `http://10.0.2.2:8080/api/v1` |
   | `EXPO_PUBLIC_AUTH_ISSUER` | `http://192.168.1.20:9000` | `http://localhost:9000` | `http://10.0.2.2:9000` |
   | `EXPO_PUBLIC_SITE_ORIGIN` | `http://192.168.1.20:3000` | `http://localhost:3000` | `http://10.0.2.2:3000` |

3. prints which account to sign in with.

Open the app on the phone: the development build lists the Metro server it found on the network, or scan the QR code
in the terminal with the Camera app. Press `r` in the terminal to reload, `j` for the debugger. Other settings:
`CLIENT=go` (Expo Go, § 8), `FIXTURES=1` (the in-app fixture backend, no server at all), `CHECK=0` (skip the checks).

The build variables are inlined when Metro bundles, so switching between TARGET=device and ios needs a Metro restart
(`make mobile-dev` again), not a new build.

## 5. Sign in

The consumer app signs in on its own screens (Sign in › email or mobile → a 6-digit code). Locally no SMS leaves your
Mac: the code is in the auth server's log —

```sh
grep 'Verification code' .run/logs/auth.log | tail -1
```

| who | sign in with | good for |
|---|---|---|
| Amara Osei (customer) | `amara.osei@example.com` · `+1 403 555 0201` | browsing, the cart, her orders and bookings (Activity), Account. She has no second factor, so **paying** asks her to add one |
| Jas Gill (has an authenticator) | `jas.gill@example.com`, then the authenticator key `NORTHLINEJASDEVTOTPSECRET2345672` (add it to any authenticator app, or `oathtool --totp -b NORTHLINEJASDEVTOTPSECRET2345672`) | paying: a sign-in with a second factor needs no step-up |
| the demo accounts | after `make demo-data`: the customer (Jordan Demo-Shopper) and the others in [demo.md](demo.md), their authenticator keys in the script's state file | the client demo's data — shops with photos, past orders, bookings — on the phone |
| a new account | Sign up in the app: your name, a fake email, a mobile number; the code from the log; choose "Authenticator app" and scan the key with your phone's authenticator | the whole journey as a new customer |

"Sign in with a passkey", Google and Apple open the system browser on the consumer site: passkeys only work on
`localhost` (WebAuthn's relying party), so on a phone use the email / mobile code. The seeded personas are in the
root README § Local sign-in.

## 6. Pay (a test card)

`make up-all` keeps the **fake payment gateway** ([stripe.md § 3](stripe.md#3-local-development)): checkout's step 2
shows a notice instead of a card form, Place your order shows the bank's confirmation step, and Approve places the
order — nothing is charged and no card is asked for.

To see Stripe's own card sheet, put a **test-mode** account's keys in `server/.env` and restart the api:

```sh
STRIPE_SECRET_KEY=sk_test_your_key
STRIPE_PUBLISHABLE_KEY=pk_test_your_key
```

The api then answers `payment.provider: stripe` and the development build opens Stripe's PaymentSheet (it never runs
in Expo Go reliably, § 8). Test cards: `4242 4242 4242 4242` (succeeds), `4000 0025 0000 3155` (asks for 3-D
Secure), any future expiry, any CVC, any postal code. Payments are authorized and held (manual capture), as in
production; [stripe.md](stripe.md) has the rest.

## 7. The courier app

```sh
make mobile-dev-build APP=courier TARGET=ios   # once
make mobile-dev APP=courier                    # every day
```

The courier app signs in **in the system browser** (Sign in → the consumer site's sign-in page on your Mac → back to
the app through `ca.northline.courier:/oauth2redirect`), so it needs the consumer site reachable from the phone
(§ 2) and a development build (Expo Go can't receive that redirect). Sign in as **Priya Nair**
(`priya.nair@example.com`, the code from the auth log): the seed gives her a run with a pickup. For a courier of your
own, register one with the console API ([courier-app.md § Run it](courier-app.md#run-it)). Location sharing asks for
"Always" on iPhone; the Simulator's Features › Location › City Run moves it.

## 8. Expo Go

`make mobile-dev APP=consumer CLIENT=go` serves the consumer app to **Expo Go**, for a quick look without building.
It works only as far as Expo Go's built-in native modules go: browsing, search, the cart, the in-app sign-in and the
fake payment should work; push notifications, the system-browser sign-in (Expo Go can't receive
`ca.northline.app:/oauth2redirect`), the app's French display name and permission texts, and possibly Stripe's
PaymentSheet (Expo Go ships one Stripe version) don't. Expo Go also has to support SDK 57 — the App Store's Expo Go
supports only the newest SDK. The courier app always needs the development build (camera, background location, its
redirect). When Expo Go misbehaves, use the development build (§ 3): it is what the apps are built and tested for.

## 9. How the phone reaches your Mac

Everything below is the `local` profile or the dev server only; dev, staging and production are unchanged.

- **The api and auth** listen on every interface (Spring Boot's default), so `http://192.168.1.20:8080` and `:9000`
  answer from the phone. Tokens keep the configured issuer (`AUTH_ISSUER`, `http://localhost:9000`), which is what the
  api checks; the app doesn't compare issuers, and DPoP proofs are checked against the address the request came in
  on.
- **northline-auth** (`northline.auth.local-network-devices: true` in `application-local.yml`,
  `LocalNetworkDevices`): a redirect URI registered on `localhost` also matches the same scheme, port and path on a
  private-network host (`10.x`, `172.16–31.x`, `192.168.x`, `169.254.x`, `*.local`) — so the consumer app's
  `http://192.168.1.20:3000/app/oauth2redirect` is accepted; an authorization request that came in on such a host is
  sent to the sign-in page on that host (`http://192.168.1.20:3000/sign-in`, not `localhost`); and the JSON sign-in
  API's CORS and Origin check accept that host's twin of an allowed origin. Public hosts, other ports and other paths
  stay refused.
- **The consumer web dev server** with `NL_DEV_HOST=0.0.0.0` listens on the LAN, and the sign-in page it renders for
  a request on a private-network host tells the browser the auth server's address on that host.
- **The development variants** allow cleartext http to local addresses (iOS `NSAllowsLocalNetworking`, Android
  `usesCleartextTraffic`); preview and production builds refuse anything but https.

## 10. Troubleshooting

| symptom | cause | fix |
|---|---|---|
| `make mobile-dev` says the api or auth "answers on localhost but not on 192.168.…" | the macOS firewall blocks Java | System Settings › Network › Firewall › Options: allow incoming connections for `java` (and `node`), or turn the firewall off while testing; then open `http://192.168.1.20:8080/actuator/health` in the phone's browser |
| the consumer site answers on localhost only | its dev server wasn't started for the LAN | let `make mobile-dev` restart it, or set `NL_DEV_HOST=0.0.0.0` in `web/apps/consumer/.env` and run `make up-all` |
| the phone can't open anything on the Mac, the browser test fails too | different networks: guest Wi-Fi, client isolation, the phone on mobile data, a VPN on the Mac | same Wi-Fi without isolation; turn the VPN off; or use the Simulator / emulator |
| wrong address detected (two networks, Ethernet + Wi-Fi, a VPN) | the default route isn't the Wi-Fi | `MOBILE_HOST=192.168.1.20 make mobile-dev APP=consumer` (System Settings › Wi-Fi › Details shows the address) |
| the Mac's address changed (DHCP) | a new lease | run `make mobile-dev` again (the address is inlined when Metro starts); nothing on the server side needs to change |
| sign-in: "We couldn't reach Northline" right after the code | the redirect to the consumer site (`:3000`) failed from the phone | the consumer site must answer on the LAN (row 2); check `http://192.168.1.20:3000/app/oauth2redirect` in the phone's browser |
| sign-in: `invalid_request` / "redirect_uri" in the auth log | auth runs without the change, or not with the `local` profile | pull, then `make up-all` (or `make up SERVICES=auth`); `grep -n local-network-devices server/auth/src/main/resources/application-local.yml` |
| the courier's browser sign-in shows "Origin not allowed" | the same: an older auth server | as above |
| the courier's browser stays on the sign-in page after the code | the page talks to `localhost:9000` | the consumer site must run with `NL_DEV_HOST` (it then gives the browser the LAN address) |
| iOS: "Local network" prompt, or nothing loads in the development build | iOS asks before an app talks to the LAN | allow it (Settings › Privacy & Security › Local Network › Northline (Dev)) |
| Android emulator: nothing loads | the emulator reaches the Mac as `10.0.2.2`, not `localhost` | `TARGET=android`; for Metro itself Expo runs `adb reverse tcp:8081 tcp:8081` (run it by hand if the app shows "Unable to load script") |
| product pictures don't show on the phone | an image URL the api gives with `localhost` | relative image paths are loaded from the api's address; absolute `localhost` URLs from object storage stand-ins only work in the Simulator |
| "Something went wrong" about fonts or an unknown module after pulling | native dependencies changed (fonts, Stripe, Expo modules) | rebuild the development build (§ 3) |
| Xcode: signing errors, "No profiles for ca.northline.app.dev" | no team selected | pick your team in Signing & Capabilities (§ 3); a free team can't sign the push entitlement |
| paying asks to "Add a second factor" | the account signed in without one | sign in as Jas Gill with the authenticator, or add an authenticator to the account (§ 5) |
| "Feedback" button or Wallet & points missing | hidden in the redesign's demo navigation | `EXPO_PUBLIC_TESTING_TOOLS=1` / `EXPO_PUBLIC_FULL_NAV=1` in the shell before `make mobile-dev` ([mobile.md § Variants and build variables](mobile.md#variants-and-build-variables)) |
