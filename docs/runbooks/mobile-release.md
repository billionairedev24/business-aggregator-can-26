# Mobile releases: App Store and Google Play (S-103)

How the consumer app (`mobile/apps/consumer`, "Northline") and the courier app (`mobile/apps/courier`, "Northline
Courier") go from a commit to the stores: EAS Build and Submit, TestFlight and the Play internal track, App Review and
the Play staged rollout, over-the-air updates, rollback, the store listings in English and French, the privacy answers,
and the push credentials. For release managers first, then mobile developers. Running the apps:
[mobile.md](mobile.md), [courier-app.md](courier-app.md); push: [push.md](push.md).

> **Status (2026-10-02): nothing has been submitted to Apple or Google, and no EAS build has run.** No Expo
> organisation, Apple Developer account, App Store Connect record, Google Play Console account or Firebase project
> exists yet. What is proven offline: the release checks (`make mobile-release-check`, part of `pnpm lint`, with unit
> tests), `expo prebuild` of the consumer app with the push module (`make mobile-consumer-native-check`), the runtime
> fingerprint (unchanged by a version bump or the `EXPO_PUBLIC_*` values, changed by a variant), `expo config` per
> profile, `actionlint` on the workflow and `ruby -c` on the Fastfile. Never exercised: `eas build`, `eas submit`,
> `eas update`, `eas metadata:push`, every fastlane lane, the stores' answers, push delivery to a phone. Do
> [the first release](#the-first-release-step-by-step) when the accounts exist, and record what happened in DECISIONS.

## Contents

1. [At a glance](#at-a-glance) · [Where everything lives](#where-everything-lives)
2. [Environments, profiles and channels](#environments-profiles-and-channels) · [Versions and build numbers](#versions-and-build-numbers)
3. [One-time setup](#one-time-setup) · [Secrets](#secrets) · [Push credentials](#push-credentials)
4. [The first release, step by step](#the-first-release-step-by-step) · [TestFlight and internal testing](#testflight-and-internal-testing)
5. [Going public: App Review and the staged rollout](#going-public-app-review-and-the-staged-rollout) · [Every later release](#every-later-release)
6. [Over-the-air updates](#over-the-air-updates) · [Rollback](#rollback)
7. [Store listings](#store-listings) · [Privacy answers](#privacy-answers) · [Age ratings](#age-ratings) · [Distribution and age-restricted goods](#distribution-and-age-restricted-goods) · [Screenshots](#screenshots)
8. [Checks and make targets](#checks-and-make-targets) · [CI](#ci) · [Troubleshooting](#troubleshooting)

## At a glance

```
commit ─► make mobile-version-set (package.json) ─► tag <app>-vX.Y.Z
   └─► EAS Build, profile production (iOS IPA + Android AAB, build numbers from EAS)
         └─► EAS Submit, profile internal ─► TestFlight (internal testers) + Play internal track
               └─► testers sign off on the device checklist
                     ├─► iOS: fastlane submit_review ─► App Review ─► a person releases ─► 7-day phased release
                     └─► Android: fastlane promote ─► production at 10 % ─► rollout 0.5 ─► rollout 1 (or halt)
JS-only fix ─► EAS Update to the channel (same runtime fingerprint only), optionally to a percentage first
```

Each app is released on its own (its own version, tag, store records and listing); the steps are the same.

## Where everything lives

| what | where |
|---|---|
| marketing version (the one source) | `mobile/apps/<app>/package.json` `version`; `app.config.ts` reads it (`APP_VERSION`) |
| build profiles, channels, submit profiles | `mobile/apps/<app>/eas.json` |
| runtime version (OTA compatibility) | `runtimeVersion: { policy: 'fingerprint' }` in `app.config.ts`; what counts: `fingerprint.config.js` |
| App Store listing, categories, age rating, release mode | `mobile/apps/<app>/store/store.config.json` (EAS Metadata, `en-CA` + `fr-CA`) |
| Play listing text and release notes | `mobile/apps/<app>/store/play/<locale>/{title,short_description,full_description}.txt`, `changelogs/default.txt` (fastlane supply layout) |
| Play settings without an upload (category, contact, target audience, content rating, app access) | `mobile/apps/<app>/store/play/details.json` |
| App Privacy and Data safety answers | `mobile/apps/<app>/store/privacy.json` (kept equal to the privacy manifest in `app.config.ts`) |
| screenshots to take, captions, placeholders | `mobile/apps/<app>/store/screenshots.json`; files in `store/screenshots/ios/<locale>/` and `store/play/<locale>/images/` |
| the offline checks | `mobile/packages/release` (`@northline/mobile-release`) |
| Play promotion / rollout / halt, iOS App Review | `mobile/fastlane/Fastfile` |
| make targets | `make/mobile-release.mk` (`make help`, § Mobile releases) |
| CI | `.github/workflows/mobile-release.yml`, `ci/gitlab/mobile-release.yml` (manual only) |

## Environments, profiles and channels

One EAS build profile family per environment; the profile sets the build variables (Metro inlines them) and the EAS
Update channel baked into the binary. `mobile-release-check` holds `eas.json` to this table.

| environment | build profile(s) | variant / bundle id (consumer · courier) | channel = EAS environment | `EXPO_PUBLIC_API_URL` | `EXPO_PUBLIC_AUTH_ISSUER` | `EXPO_PUBLIC_SITE_ORIGIN` (consumer only) | distribution |
|---|---|---|---|---|---|---|---|
| dev | `development` (simulator), `development-device` | `ca.northline.app.dev` · `ca.northline.courier.dev` | `development` | `https://api.dev.northline.ca/api/v1` | `https://auth.dev.northline.ca` | `https://dev.northline.ca` | internal, development client |
| staging | `preview` | `ca.northline.app.preview` · `ca.northline.courier.preview` | `preview` | `https://api.staging.northline.ca/api/v1` | `https://auth.staging.northline.ca` | `https://staging.northline.ca` | internal (registered iPhones, APK) |
| prod | `production` | `ca.northline.app` · `ca.northline.courier` | `production` | `https://api.northline.ca/api/v1` | `https://auth.northline.ca` | `https://northline.ca` | store (IPA + AAB) |

The three variants have their own bundle id and name, so they install side by side. Only `production` goes to the
stores; `preview` builds are shared from their EAS build page (or as TestFlight builds of the production variant —
see below). Nothing secret is in `eas.json` (the check refuses `*_TOKEN`, `*_SECRET`, `*_KEY` variables and key file
paths); a build that needs a secret reads it from the EAS environment (`eas env:create --environment production`).

## Versions and build numbers

| number | source | who changes it |
|---|---|---|
| marketing version `X.Y.Z` (CFBundleShortVersionString / versionName) | `package.json` of the app | a person, once per store release: `make mobile-version-set MOBILE_APP=consumer MOBILE_VERSION=1.0.0` (semver: minor for features, patch for fixes), in its own commit |
| build number (CFBundleVersion) / versionCode | EAS servers (`cli.appVersionSource: remote`) | EAS, `autoIncrement` on every `preview` and `production` build; `eas build:version:get` / `build:version:set` to read or correct it. The first build asks for a start value: 1 |
| runtime version | the native fingerprint | automatic — changes exactly when native code or config does |
| release tag | `consumer-vX.Y.Z` / `courier-vX.Y.Z` | the release workflow creates it on the commit it builds (GitLab: the person, before the run); a tag that names another commit is refused |

`NL_IOS_BUILD_NUMBER` / `NL_ANDROID_VERSION_CODE` exist only for local Xcode / Gradle builds. `make mobile-version`
prints both apps' versions and tags.

## One-time setup

Per app unless stated. Nothing here can be done in the repository alone; the release workflow is inert (skips with a
notice) until it is.

1. **Expo organisation** (owner: an organisation account, not a person). In each app directory: `npx eas-cli login`,
   `npx eas-cli init` → the project id. GitHub variables `EAS_OWNER`, `EAS_CONSUMER_PROJECT_ID`,
   `EAS_COURIER_PROJECT_ID` (the courier workflow's older `EAS_PROJECT_ID` is accepted); GitLab the same names. An
   access token (Expo › Access tokens, a robot user) → secret `EXPO_TOKEN`.
2. **EAS environments** `development`, `preview`, `production` (they exist by default). Push: the environment's
   `google-services.json` as a *file* variable `GOOGLE_SERVICES_JSON` in each ([Push credentials](#push-credentials)).
3. **Apple Developer Program** (organisation, D-U-N-S number). Note the **Team ID**.
4. **App Store Connect records**: *Apps › +* for `ca.northline.app` ("Northline") and `ca.northline.courier`
   ("Northline Courier"), primary language **English (Canada)**, SKU `northline-consumer` / `northline-courier`. Add
   the **French (Canada)** localisation. Copy each app's numeric **Apple ID** (App Information) into
   `submit.base.ios.ascAppId` of its `eas.json`, and the Team ID into `appleTeamId`, in a commit. Until then
   `eas submit -p ios` refuses the `REPLACE_WITH_…` placeholders and `check --strict` lists them.
5. **App Store Connect API key**: *Users and Access › Integrations › App Store Connect API*, role **App Manager**.
   Download the `.p8` once. Give it to EAS (`npx eas-cli credentials -p ios` › App Store Connect API Key) for
   submissions, and to CI as secret `ASC_API_KEY_P8` (the file's text) with variables `ASC_API_KEY_ID`,
   `ASC_API_ISSUER_ID` (fastlane's App Review step, EAS Metadata).
6. **Google Play Console** (organisation account; identity verification takes days). Create both apps
   (`ca.northline.app`, `ca.northline.courier`), default language English (Canada), add French (Canada), **Play App
   Signing on**. Note: a new *personal* developer account must run a closed test (12 testers, 14 days) before
   production — use an organisation account.
7. **Play service account**: Google Cloud › IAM › a service account in a project of ours, a JSON key; Play Console ›
   *Users and permissions* › invite it with *Release to testing tracks*, *Release to production*, *Manage store
   presence* for the two apps. Give the key to EAS (`npx eas-cli credentials -p android` › Google Service Account)
   and to CI as secret `PLAY_SERVICE_ACCOUNT_JSON`.
8. **Signing**: `npx eas-cli credentials` per platform — EAS creates and keeps the iOS distribution certificate and
   profiles and the Android upload keystore (`credentialsSource: remote`). Back up the upload keystore into the
   secrets manager. Then the App Links fingerprint: Play Console › App integrity › App signing key SHA-256 →
   `mobileApps.androidCertSha256` ([mobile.md § App Links](mobile.md#app-links)); the Team ID →
   `mobileApps.appleTeamId`.
9. **Capabilities on the bundle ids** (Certificates, Identifiers & Profiles; EAS syncs them on the first build, check
   them): Associated Domains (consumer), Push Notifications (both).
10. **The first Android upload is manual**: Google's API refuses an app that has never had a release. Download the
    first production AAB from its EAS build page and upload it once in Play Console › Testing › Internal testing ›
    Create release. After that `eas submit` works.

## Secrets

Release secrets live in the CI system and in EAS, **not** in the cluster's secrets manager (no app reads them at run
time); [secrets.md § Mobile release](secrets.md#mobile-release-ci-and-eas) lists them with rotation. Never commit a
`.p8`, `.p12`, `.mobileprovision`, keystore, `google-services.json` or service-account JSON; the examples in this
repository are fake.

| name | kind | used by | from |
|---|---|---|---|
| `EXPO_TOKEN` | secret (GitHub) / masked variable (GitLab) | every EAS step | Expo › Access tokens (robot user) |
| `ASC_API_KEY_P8` | secret / **File** variable | fastlane `submit_review`, `eas metadata:push` | App Store Connect API key `.p8` |
| `ASC_API_KEY_ID`, `ASC_API_ISSUER_ID` | variables | the same | the key's page (Key ID, Issuer ID) |
| `PLAY_SERVICE_ACCOUNT_JSON` | secret / **File** variable | fastlane `listing`, `promote`, `rollout`, `halt` | the service account's JSON key |
| `EAS_OWNER`, `EAS_CONSUMER_PROJECT_ID`, `EAS_COURIER_PROJECT_ID` | variables (not secret) | every EAS step | `eas init` |
| `GOOGLE_SERVICES_JSON` | EAS file variable per environment | EAS Build (push) | Firebase console |

An example of the shape only (fake): `ASC_API_KEY_ID=FAKEKEY123`, `ASC_API_ISSUER_ID=00000000-0000-0000-0000-000000000000`.

## Push credentials

S-102 built the server side and mobile-kit's registration; S-103 adds the native module: `expo-notifications` and its
config plugin are in both apps (the `aps-environment` entitlement — `production` for preview and store builds,
`development` for development builds —, the Android channel `updates`, `POST_NOTIFICATIONS`). The registrar
(`setPushRegistrar(pushRegistrar(...))` at start-up and the permission moment) is **not wired yet**: it is app code in
`app/_layout.tsx`, and because the native module is already in the binary it can ship as an over-the-air update.
When the accounts exist:

1. **Apple**: enable *Push Notifications* on `ca.northline.app` and `ca.northline.courier` (and `.dev` / `.preview`
   if they should get pushes); EAS regenerates the profiles on the next build. Create the APNs key and give it to the
   **worker**, not the app ([push.md § Apple](push.md#apple-apns)). EAS does not need the APNs key (we send with our own
   provider token, not Expo's push service).
2. **Firebase**: a project per environment; add the Android apps; download each environment's `google-services.json`
   and store it in EAS: `npx eas-cli env:create --environment production --name GOOGLE_SERVICES_JSON --type file
   --value ./google-services.json --visibility secret` (likewise `preview`, `development`). `app.config.ts` sets
   `android.googleServicesFile` from it. Without it builds still work; Android just gets no push token. The service
   account for sending goes to the **worker** ([push.md § Firebase](push.md#firebase-fcm)).
3. Wire the registrar, then do [push.md's first-send checklist](push.md#first-real-send-checklist).

## The first release, step by step

For one app (`MOBILE_APP=consumer`, then the same for `courier`). Prerequisites: [One-time setup](#one-time-setup),
the staging environment running the release candidate's server.

1. **Offline checks**: `make mobile-release-check` (or `pnpm lint` in `mobile/`) is green; `make
   mobile-release-check-strict` shows what is still pending (store ids, screenshots, account deletion).
2. **Version**: `make mobile-version-set MOBILE_APP=consumer MOBILE_VERSION=1.0.0`, commit ("consumer 1.0.0"),
   merge to `main`.
3. **Listing text** for this version: release notes in `store.config.json` (`releaseNotes`, both locales) and
   `store/play/<locale>/changelogs/default.txt`; `make mobile-release-check`.
4. **Build**: Actions › mobile-release › Run workflow: `app=consumer`, `action=build`, `profile=production`
   (GitLab: create the tag `consumer-v1.0.0` on the commit, then `PIPELINE_PART=mobile-release`, `RELEASE_ACTION=build`,
   and start the manual `mobile-release:eas` job). The workflow tags the commit and queues the EAS builds; when each
   finishes, EAS submits it with the `internal` profile: the IPA to **TestFlight**, the AAB to the **Play internal
   track** (completed). Locally: `make mobile-eas-build MOBILE_APP=consumer EAS_PROFILE=production`. Android, first
   time only: the manual upload (setup step 10), then `make mobile-eas-submit MOBILE_APP=consumer EAS_PLATFORM=android`.
5. **Listing**: `action=listing` pushes the App Store listing (`eas metadata:push`: texts, URLs, categories, age
   rating answers, release mode) and the Play texts (fastlane `listing`). It runs the checks with `--strict`, so
   real screenshots, the store ids and account deletion must be done first. In the consoles, by hand (no API): the
   screenshots and Play feature graphic ([Screenshots](#screenshots)), the App Privacy and Data safety answers
   ([Privacy answers](#privacy-answers)), Play's content rating questionnaire and target audience (`details.json`),
   Play's app access and App Review's sign-in details (a review account on production with the demo data — never in
   the repository), the export compliance answer (the app sets `ITSAppUsesNonExemptEncryption = false`).
6. **Test** ([below](#testflight-and-internal-testing)).
7. **Go public** ([below](#going-public-app-review-and-the-staged-rollout)).
8. Record in DECISIONS what ran for the first time and what failed.

## TestFlight and internal testing

- **TestFlight, internal testers** (up to 100 App Store Connect users with a role): the build appears after Apple's
  processing (15–30 min), no review. Create a group "Northline team", add the build. **External testers** (a pilot of
  customers or couriers) need a group and a short Beta App Review for the first build of each version.
- **Play internal testing**: Testing › Internal testing › Testers: an email list; share the opt-in link. Builds are
  available within minutes. **Closed testing** (the `alpha` track) for a wider pilot.
- **Staging builds**: the `preview` profile builds for registered iPhones and an APK (EAS build page link), against
  staging. Use them for QA before a production build; TestFlight / internal testing run the production variant
  against production.
- **Device checklist before going public** (both platforms, a real phone, production): fresh install, the
  permission texts in English and French, sign up / sign in (the code by SMS), the system-browser sign-in (passkey,
  Google, Apple), location ("Use my location"), browse as a guest, an order and a booking paid with a real card and
  3-D Secure, the deep links from an email and a push, offline banner and recovery, sign out. Courier: shift, run,
  pickup, drop-off with photo / signature / PIN, background location during a run, a weak network. Keep the result
  in the release's PR or ticket.

## Going public: App Review and the staged rollout

**iOS** — `action=submit-review`, `build_number=<the TestFlight build>` (fastlane `submit_review`): the build goes to
App Review with **manual release** and **phased release** (`store.config.json` `release`). After approval a person
presses *Release this version* in App Store Connect; phased release then reaches 1, 2, 5, 10, 20, 50, 100 % of
automatic-update users over 7 days (anyone can still download it from the store at once). Pause the phased release
from App Store Connect if something is wrong (up to 30 days).

**Android** — `action=promote`, `version_code=<the internal build>`, `rollout=0.1` (fastlane `promote`): the tested
internal release is promoted to **production at 10 %**, with the release notes. Watch Play Console › Android vitals
(crash and ANR rates) and the api's errors for a day, then `action=rollout` with `0.5`, then `1` (completes it).
`action=halt` stops it ([Rollback](#rollback)). Play may review the first production release for several days.
**The courier app** goes the same way to its **closed testing track** (`alpha`), not production (unlisted, owner
decision 2026-10-04): the Fastfile picks the track per app. On iOS, an approved courier version is released the same
way; after the first approval, request Unlisted App distribution ([§ Distribution](#distribution-and-age-restricted-goods)).

Release notes per locale: `store.config.json` `releaseNotes` (≤ 4000) and `changelogs/default.txt` (≤ 500); the
check holds them to the limits and to French being translated.

## Every later release

1. Bump the version (minor or patch), update the release notes, commit, merge.
2. `action=build` (production) → TestFlight + internal → device checklist on what changed.
3. `action=listing` if the listing changed; `action=submit-review` and `action=promote`; roll out as above.

A fix that is JavaScript only can go as an [over-the-air update](#over-the-air-updates) instead, to the binaries
already installed.

## Over-the-air updates

`expo-updates` is in both apps; every build carries its **channel** (the profile's) and its **runtime version** (the
native fingerprint). An update published to a channel reaches only the binaries on that channel with the same
runtime version:

- **Same fingerprint**: JavaScript, copy, styles, images bundled by Metro. **New fingerprint**: anything native — a
  new native module, a config plugin, a permission, the bundle id, an SDK upgrade. Old binaries keep running what they
  have; the update reaches only new builds.
- `fingerprint.config.js` skips `extra` (the `EXPO_PUBLIC_*` values and the project id) and the version numbers:
  bumping `package.json`'s version or publishing from a shell with other variables does not cut binaries off. Check
  before publishing: `make mobile-fingerprint MOBILE_APP=consumer EAS_PROFILE=production` must equal the
  fingerprint shown on the store build's EAS page.
- When they apply: `checkAutomatically: ON_LOAD`, `fallbackToCacheTimeout: 0` — the app starts on what it has,
  downloads in the background and runs the update at the **next** launch.
- **Publish**: `action=update`, `channel=production`, `message=…`, optionally `update_rollout=10` (a percentage
  first); locally `make mobile-update MOBILE_APP=consumer UPDATE_CHANNEL=production UPDATE_MESSAGE="Fix the cart
  count" UPDATE_ROLLOUT=10`. The make target exports the profile's build variables (an update bundles on the machine
  that publishes it: without them it would carry localhost URLs) and passes `--environment`.
- Store review rules: updates may fix bugs and change content, not change the app's purpose or add features that
  need review (App Review 3.3.1(B) / 2.5.2; Play's device-and-network-abuse policy). New screens go through a store
  release.

## Rollback

| what went wrong | do |
|---|---|
| a bad **update** | `action=update-republish`, `group=<the last good update group>` (`npx eas-cli update:list --branch production` lists them) — republishes it as the newest; or `action=update-rollback-embedded`, `channel=production`: everyone back to the JS inside their binary. Devices pick it up at their next launch. An update published with a percentage: `npx eas-cli update:revert-update-rollout` |
| a bad **Android** release during the rollout | `action=halt`, `version_code=…`: nobody new gets it (those who have it keep it). Fix, build a new version (higher versionCode) and promote it; Play cannot go back to an older versionCode for people who updated |
| a bad **iOS** release during phased release | App Store Connect › the version › *Pause Phased Release*; then a fixed build through App Review (ask for an *expedited review* for a critical bug). iOS cannot go back to an older build either |
| it is JavaScript | first choice for both stores: an over-the-air update with the fix (or the republished good one) — it reaches installed apps within a launch or two |
| the api broke the app | roll the server back (gitops, [deploy.md](deploy.md)); the apps keep working with the previous api |

## Store listings

Both stores, English (Canada) and French (Canada), files in the repository, region-neutral: Northline starts in one
province and is built for every province, so no listing, keyword, caption or release note names a province, territory,
city or time zone (the check refuses them).

| field | App Store (`store.config.json`, `apple.info.<locale>`) | limit | Google Play (`store/play/<locale>/`) | limit |
|---|---|---|---|---|
| name | `title` | 30 | `title.txt` | 30 |
| subtitle / short description | `subtitle` | 30 | `short_description.txt` | 80 |
| description | `description` | 4000 | `full_description.txt` (plain text) | 4000 |
| keywords | `keywords` (a list) | 100 bytes joined | — (Play has none) | |
| promotional text | `promoText` | 170 | — | |
| release notes | `releaseNotes` | 4000 | `changelogs/default.txt` | 500 |
| URLs | `supportUrl`, `privacyPolicyUrl`, `marketingUrl` (https) | | `details.json` `website`, `privacyPolicyUrl`, `contactEmail` (entered in Store settings) | |
| category | `apple.categories`: consumer `SHOPPING` + `LIFESTYLE`; courier `BUSINESS` + `PRODUCTIVITY` | | `details.json` `category`: consumer Shopping, courier Business | |

- **Privacy policy**: `https://northline.ca/legal/privacy.html` (the consumer web serves it, S-63 — English only, the
  verbatim design 10; the French listing points to the same page until a French policy exists). **Support**:
  `https://northline.ca/help` — **this page does not exist yet**; it must answer before the first submission (or
  change the URL in both stores' files).
- Push them: `action=listing` (or `make mobile-store-metadata MOBILE_APP=…` and `NL_APP=… fastlane android listing` in
  `mobile/`). Pull what someone changed in App Store Connect: `npx eas-cli metadata:pull` in the app directory, then
  review the diff.
- The App Review contact and demo account are never in `store.config.json` (the check refuses `apple.review`).
- **The courier app is unlisted** (owner decision 2026-10-04, [§ Distribution and age-restricted
  goods](#distribution-and-age-restricted-goods)). Its listing files still exist (App Review and the Play closed track
  show them to the couriers who have the link) and say a Northline courier account is needed. Google wants the
  background-location declaration and a short video (`details.json` `backgroundLocation`).

## Privacy answers

Neither console has an API for these: enter them by hand from `store/privacy.json`. The check keeps that file equal to
the iOS privacy manifest in `app.config.ts` (App Store Connect compares the manifest with the answers) and to the
Android permissions (a location, camera or notification permission without the matching data type fails).

**Both apps: no tracking** (no IDFA, no ad SDK, no data brokers), **no data shared** with third parties (Stripe,
APNs and FCM are service providers acting for Northline), **encrypted in transit** (https only; cleartext only in
development builds). Every data type is *linked to the person* and used for *app functionality* only.

| data type (App Store › Google Play) | consumer | courier | why |
|---|---|---|---|
| Contact info › Name; Personal info › Name | ✓ | | the account; the name on orders and bookings |
| Email address | ✓ | | the account; receipts |
| Phone number | ✓ | | sign-in codes; the courier or provider reaching the customer |
| Physical address › Address | ✓ | | delivery and visit address |
| Precise location | ✓ (optional, while the app is open) | ✓ (during a run, also in the background) | consumer: suggest the address; courier: customers follow the delivery, latest position only |
| User ID › User IDs | ✓ | ✓ | the account id |
| Device ID › Device or other IDs | ✓ (optional) | ✓ (optional) | push token and a random installation id, only when notifications are allowed |
| Purchases › Purchase history | ✓ | | orders, bookings, quotes, refunds |
| Financial info › Payment info | ✓ | | entered in Stripe's sheet (the SDK collects it); Northline keeps brand and last 4 |
| Customer support › Other in-app messages | ✓ (optional) | | problem reports |
| Photos | | ✓ (optional) | the proof-of-delivery photo |
| Other user content › Other user-generated content | ✓ (optional) | ✓ (optional) | consumer: booking notes; courier: the customer's signature |
| Other data › Other data types; Personal info › Other info | ✓ (optional) | | the age check's result for a customer who buys alcohol: "verified over N, on date, by method" (2026-10-04) — never the ID, photos or date of birth |

Data safety also asks: *Is all collected data required?* (no — the optional ones above), *Can users request deletion?*
— **yes (S-105)**: the consumer app deletes the account in the app (You › Personal details › Your data › Delete my
account: a privacy request confirmed with the texted code or the authenticator app, withdrawable during its grace
period — docs/runbooks/privacy-requests.md), and the web link Google asks for is the consumer site's same page,
`https://northline.ca/account?tab=profile#your-data` (`store/privacy.json` `accountDeletion`). What the law makes
Northline keep after deletion (receipts, tax and payment records, without the name) is the Data safety form's "some
data is retained" answer. The courier app creates no accounts: couriers use the same web page — ask App Review whether
5.1.1(v) applies — **owner decision 2026-10-04: courier deletion stays on the website** (`store/privacy.json`
`accountDeletion.webOnlyDecision`); answer App Review with that page if it asks.

**Stripe Identity** (consumer, 2026-10-04) is a service provider: the age check's photo ID, selfie and face match
happen in Stripe's hosted page in the in-app browser and stay with Stripe (the session is redacted once the age is
known). Northline declares only the result (*Other data types*). The face match is Stripe's, not Northline's: the
answer to *Sensitive info › biometric* stays "not collected" — counsel confirms (docs/legal/counsel-questions.md H4).

## Age ratings

- **Consumer: 18+.** The marketplace sells alcohol from licensed shops behind an ID check (2026-10-04; tobacco, vape
  and cannabis accessories are banned on the platform — the stores don't allow apps that facilitate their sale), and
  the Terms require the age of majority. App Store: `alcoholTobaccoOrDrugUseOrReferences: FREQUENT_OR_INTENSE`, the
  rest `NONE`, no gambling, no unrestricted web access (`store.config.json` `apple.advisory`). Play: the IARC
  questionnaire answers in `store/play/details.json` (`sellsAlcoholTobaccoOrAgeRestrictedGoods: true`); target
  audience 18 and over; not designed for children.
- **Courier: mild references** (an order on a stop may contain alcohol); Play target audience 18 and over (couriers are
  adults); record the rating IARC gives in `details.json`.
- App Store Connect's newer age-rating questions (in-app controls, user-generated content, messaging, advertising):
  none of these exist in either app — answer *No* in the console (EAS Metadata does not carry them yet).

## Distribution and age-restricted goods

`store/policy.json` per app (2026-10-04), held by the release check:

| | consumer | courier |
|---|---|---|
| App Store | public | **Unlisted App distribution**: after the first approval, request it at developer.apple.com/support/unlisted-app-distribution (the App Store Connect app id and why: Northline's couriers only); couriers install from the direct link. Not searchable, no charts. |
| Google Play | production track (staged rollout) | **closed testing track** (`alpha`): `eas.json` `submit.production.android.track` and the Fastfile's `track`; testers = the couriers' Google Group (Testing › Closed testing › Testers), the opt-in link goes to new couriers. Managed Google Play private apps would need the couriers' phones in a managed organisation — they are contractors' own phones. |
| age-restricted goods | **alcohol only** — App Store Review Guideline 1.4.3 and Play's Inappropriate Content policy don't allow facilitating tobacco or vape sales (Play: nor marijuana products), so those categories stay banned (`northline.catalogue.banned-categories`). Before unbanning one, the apps must hide it. | none (couriers sell nothing) |
| age gate | checkout's ID check (Stripe Identity), the courier's ID check at the door — [age-restricted.md](age-restricted.md) | the door check: three confirmations or a refusal reason |
| rating that goes with it | Apple `alcoholTobaccoOrDrugUseOrReferences: FREQUENT_OR_INTENSE` (18+ in the current scheme); Play IARC `sellsAlcoholTobaccoOrAgeRestrictedGoods: true`, target audience 18 and over only | unchanged (mild references) |

App Review notes for the consumer app (enter by hand with the review account): *"Alcohol is sold only by licensed
businesses, only where its sale and delivery are legal (provincial minimum age and delivery hours from our region
configuration), after a one-time ID check (Stripe Identity), and the courier checks photo ID at delivery. No tobacco,
vape or cannabis products are sold."* The review build talks to production, where the ID check is Stripe Identity in
live mode (a real ID): the reviewer's path (browse, book, buy a non-alcohol item) never meets it. If App Review asks to
see the age step, attach a screen recording of it from a staging build (Stripe Identity test mode, whose test
documents verify without a real ID) rather than weakening the check for a review account.

## Screenshots

`store/screenshots.json` lists the shots, their route, the state to capture and a caption in both languages. The
files in the repository are **flat placeholders** in the brand colours (`make mobile-store-placeholders` writes them
from the list; the check verifies each exists at the store's size) — they must never reach a store: the release
check refuses `--strict` while `"placeholder": true`, and the listing step never uploads images.

| size | where |
|---|---|
| iPhone 6.9" portrait, 1320 × 2868 (Apple scales it to the smaller iPhones) | `store/screenshots/ios/<locale>/NN-name.png` |
| Play phone, 1080 × 1920 | `store/play/<locale>/images/phoneScreenshots/NN-name.png` |
| Play feature graphic, 1024 × 500 | `store/play/<locale>/images/featureGraphic.png` |
| Play icon 512 × 512 | from `assets/icon.png` (still S-97's placeholder icon until design supplies one) |

Consumer: 01 Home · 02 Services · 03 a provider · 04 picking a time · 05 order tracking. Courier: 01 shift · 02 the run
· 03 pickup · 04 drop-off proof. Capture them on a device (or simulator) against staging with the demo data in each
language, add the captions in the design's frame, replace the files, set `"placeholder": false`, then upload them in
each console (App Store Connect › the version › Previews and Screenshots; Play › Main store listing › Graphics).

## Checks and make targets

`make help` § *Mobile releases*. **(offline)** = no account needed.

| target | what |
|---|---|
| `mobile-release-check` (offline) | both apps: `eas.json` (profiles per environment with the table's origins and channels, `appVersionSource: remote`, `autoIncrement`, store distribution, AAB, no secrets or key paths, submit profiles), App Store and Play listing limits, en/fr-CA parity and translation, region-neutral text, URLs, categories, age-rating answers, privacy answers ↔ privacy manifest ↔ permissions, screenshot list and sizes, the version source and the fingerprint runtime. Also the last step of `pnpm lint` |
| `mobile-release-check-strict` (offline) | the same, failing on what is pending before going public |
| `mobile-release-test` (offline) | the checks' unit tests (each rule broken once) |
| `mobile-version`, `mobile-version-set` (offline) | read / set the marketing version |
| `mobile-release-config` (offline) | `expo config --type public` with a profile's variables (`EAS_PROFILE`) |
| `mobile-fingerprint` (offline) | the runtime version a profile's build will have |
| `mobile-store-placeholders` (offline) | rewrite the placeholder images |
| `mobile-eas-build` | EAS Build (`MOBILE_APP`, `EAS_PROFILE`, `EAS_PLATFORM`); production adds `--auto-submit-with-profile internal` |
| `mobile-eas-submit` | EAS Submit of the latest build (`SUBMIT_PROFILE=internal|production`) |
| `mobile-store-metadata` | `eas metadata:push` (after the app's check) |
| `mobile-update`, `mobile-update-republish`, `mobile-update-rollback-embedded` | EAS Update and its rollbacks |

The older per-app targets (`mobile-consumer-eas-build`, `courier-eas-build`) still queue a build of any profile.

## CI

**Manual only** ([ci.md](ci.md)): Actions › *mobile-release* › Run workflow (`app`, `action` and the action's inputs),
or GitLab › Run pipeline with `PIPELINE_PART=mobile-release`, `RELEASE_APP`, `RELEASE_ACTION` (the EAS and store jobs
are further manual jobs). No push, pull request, tag or schedule trigger. Every run starts with the offline checks
(`--strict` for `listing`, `submit-review`, `promote`); EAS and store steps skip with a notice until their secrets
exist. Production builds create the tag `<app>-vX.Y.Z` (GitHub; on GitLab create it first — the job token cannot push
tags). The store keys are written to the runner's temp directory for the run and deleted after.

## Troubleshooting

| symptom | cause → fix |
|---|---|
| `eas submit -p ios` refuses the app id | `ascAppId` / `appleTeamId` still `REPLACE_WITH_…` → setup step 4 |
| Play: "Package not found" on the first `eas submit` | no release ever uploaded → the one manual upload (setup step 10) |
| Play: "Version code N has already been used" | an AAB is uploaded once; promote it (`action=promote`) instead of submitting it again to another track |
| an update does not reach the installed app | another runtime: the binary's fingerprint (EAS build page) ≠ `make mobile-fingerprint` → a store build is needed; or another channel; or the app was not restarted twice |
| an update shows localhost / the wrong api | published without the profile's variables → use `make mobile-update` (it exports them) |
| `ITMS-91053` mail after upload | an SDK uses a required-reason API not declared → add it to `ios.privacyManifests` in `app.config.ts` |
| App Review: "the privacy answers do not match the manifest" | `store/privacy.json` and the console disagree → re-enter from the file (the check keeps the file and the manifest equal) |
| `release check` fails on a province or city | store text must be region-neutral: name no place; say "province by province" |
| fastlane: "Google Api Error: forbidden" | the service account lacks the release permissions for that app (setup step 7) |
| no push token on Android | `GOOGLE_SERVICES_JSON` not set for that EAS environment ([Push credentials](#push-credentials)) |
