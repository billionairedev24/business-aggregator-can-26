# Push notifications and deep links (S-102)

How the consumer and courier apps get push notifications (APNs for iOS, FCM for Android), which events notify a
customer or a courier on which channel, how a notification opens the right screen, and how to set up the Apple and
Firebase credentials. Code: device registry `ca.northline.messaging` (api), sending `ca.northline.worker.notifications`
and `ca.northline.worker.push` (worker), the apps' side `@northline/mobile-kit` (`src/push`). Team notifications
(S-27) are in [notifications.md](notifications.md); the apps' sign-in in [mobile-auth.md](mobile-auth.md).

> **Status (2026-10-02): nothing has been sent to the real APNs or FCM.** There is no Apple developer key and no
> Firebase project yet. Both adapters follow Apple's and Google's documented APIs and are tested against WireMock
> stand-ins (`PushProvidersTest`, `PushEndToEndTest`); the HTTP/2 connection to APNs over TLS, Apple's and Google's
> real answers and delivery to a phone are unverified. Do the [first-send checklist](#first-real-send-checklist) when
> the accounts exist.

## 1. How a push travels

```
consumer / courier app ──PUT /api/v1/me/devices/{installationId}──► api ──► messaging.push_devices
   (DPoP-bound token)      {platform, token, locale, appVersion, permission}

domain event (Kafka) ──► worker: notifications / personal-notifications consumer groups
   └─ who (customer of the order / booking / quote / refund; courier of the run)
   └─ their Account › Notifications matrix and quiet hours ── held? → messaging.deferred_notifications
   └─ PushSender ──► northline.push.provider=native: each installation of the app that allows notifications
                       ios → APNs  POST /3/device/<token>          (HTTP/2, provider JWT from the .p8 key)
                       android → FCM POST /v1/projects/<p>/messages:send (OAuth 2.0, service account)
                     northline.push.provider=local: the worker log
```

- **Payloads carry the words, a deep link and ids — nothing else.** No name, email, phone number, address or amount
  owed by a person goes to Apple or Google. Order notices name no order number; booking and quote notices name the
  business (not personal data); refund notices name the case number and amount. `PushEndToEndTest` checks the APNs
  and FCM request bodies of a real customer event for the customer's name, email, phone and user id.
- **APNs:** `apns-topic` = the app's bundle id, `apns-push-type: alert`, `apns-priority: 10`, `apns-expiration` one
  day, `apns-collapse-id` = the subject (`order:<id>`, `booking:<id>`, `quote:<id>`, `case:<number>`, `run:<id>`: a
  newer update replaces the older one on the lock screen); `aps.thread-id` groups them.
- **FCM:** `notification` {title, body}, `data` {type, ids, link} (strings), `android.collapse_key` and
  `notification.tag` the same subject key, `priority: high`, `ttl: 86400s`, channel `updates` (the apps create it).
- **Language:** each installation shows the person's notification language (Account › Notifications "English" /
  "Français"); "Same as app" = each installation's own app language (the `locale` it registered). Couriers: their
  account language. Both languages are written for every push.

## 2. The device registry (api)

| call | body | answer |
|---|---|---|
| `PUT /api/v1/me/devices/{installationId}` | `{platform: ios\|android, token?, locale: en\|fr(-CA), appVersion, permission: granted\|provisional\|denied\|undetermined}` | 200 `{installationId, app, platform, locale, permission, refreshedAt}` (never the token) |
| `DELETE /api/v1/me/devices/{installationId}` | — | 204; 404 when it isn't the caller's installation of this app |

- **App tokens only.** The path needs a DPoP-bound access token (`cnf.jkt`, the proof checked — S-29); a browser
  session's, a Studio or a partner token is 403. The app is the token's: scope `courier` → the courier app, otherwise
  the consumer app. The person is the token's `sub`; nothing in the body says who.
- **One row per person, app and installation** (`installationId`: 16–64 of `[A-Za-z0-9_-]`, random per installation,
  never a hardware id). A token belongs to one installation: when someone else registers the same token (a shared
  phone, a new sign-in after an offline sign-out), it moves to them.
- **When the apps call it:** after every sign-in, at every start and return to the foreground, when the platform
  rotates the token, and when the person changes the notification permission or the app's language — only when
  something changed (mobile-kit keeps what it last sent). **At sign-out, before revoking the tokens:** `DELETE`.
- **Removed by the server:** when APNs or FCM says the token is dead (below), and — daily — installations that
  haven't refreshed for `PUSH_STALE_AFTER` (90 days; an app that was deleted or signed out offline). Those older than
  that get nothing in the meantime.
- **Stored:** person, app, installation id, platform, token, language, app version, permission, two timestamps.
  Nothing else (no device model, name, IP or location). Erasing a person (PIPEDA) deletes their rows:
  `delete from messaging.push_devices where user_id = '<id>'`.

## 3. Customer and courier notifications (worker)

| event (topic) | to | Account › Notifications row | push title (en) | deep link |
|---|---|---|---|---|
| `order.packed` with the whole order ready (`orders.order`) | customer | `order_updates` | Order packed — "ready to ship" | `/app/orders/<id>` |
| `delivery.picked_up` (`fulfilment.delivery`) | customer | `order_updates` | Out for delivery | `/app/orders/<id>` or `/app/food/orders/<id>` |
| `order.delivered` (`orders.order`) | customer | `order_updates` | Order delivered | same |
| `booking.confirmed` (`booking.booking`) | customer | `booking_reminders` | Booking confirmed | `/app/bookings/<id>` |
| the evening-before reminder (worker job, 18:00 the day before in the customer's zone) | customer | `booking_reminders` | Your booking is tomorrow | `/app/bookings/<id>` |
| `booking.en_route` — the ETA (`booking.booking`) | customer | `booking_reminders` | Your provider is on the way | `/app/bookings/<id>` |
| `booking.completed` (`booking.booking`) | customer | `sign_off` | Job done — please sign off | `/app/bookings/<id>` |
| `quote.sent` (`booking.quote`; version > 1 = revised) | customer | `quotes_messages` | New quote from <business> | `/app/quotes/<id>` |
| `refund.case_updated`, `refund.issued` (`payments.refund`) | customer | `refunds_cases` | Refund request RF-… / Refund RF-… paid | `/app/cases/<number>` |
| `delivery.assigned` (`fulfilment.run`) | courier | — always, push only | New run | `/courier/run` |
| `run.changed` (`fulfilment.run`, new in S-102: dispatch gave the run to another courier) | the courier who lost it | — always, push only | Run changed | `/courier/run` |

- **Channels:** push, SMS and email as the customer's matrix says (defaults: design 06, `docs/spec/
  notification-matrix-defaults.json` § `customer`, kept equal to the api's by `NotificationMatrixDefaultsSpecTest`).
  The SMS starts with "Northline:"; the email (template `customer-update`) is the same line with a button to the deep
  link and a one-click unsubscribe link that turns that row's email off (`customer.<row>` tokens, the api's S-13
  unsubscribe endpoint). Security alerts and offers are not sent by the worker (no such events yet).
- **Quiet hours** (Account › Notifications; the person's `quiet_from`/`quiet_to`, shared with their Studio matrix;
  `quiet_on` off = none) hold **push and SMS**, never email, until they end — in the time zone of the province the
  customer shops in (`account.preferences.province` → the region model's zone, S-134), else the platform zone. The
  held notification is re-checked against the matrix when it is sent. Couriers' run notices are never held.
- **Who:** the order's, booking's or quote request's customer and the refund's (its escrow's, else its booking's) are
  read from the api's tables when the event arrives (events carry ids only); an inactive account gets nothing.
- **Not built:** "order shipped" by a parcel carrier (Northline's couriers deliver: "packed and ready to ship" is the
  closest step), messages from providers (`quotes_messages` — no message event yet), the live ETA's minutes in the
  push (the app shows it), customer support cases other than refund cases (no case-updated event).

## 4. Failures and back-off

| what | APNs | FCM | what the worker does |
|---|---|---|---|
| dead token | 410 `Unregistered`/`ExpiredToken`, 400 `BadDeviceToken`/`DeviceTokenNotForTopic` | 404 `UNREGISTERED`, 403 `SENDER_ID_MISMATCH`, 400 naming the registration token | deletes the device (`Push device … removed: ios said Unregistered`); the push counts as sent to the others |
| throttled | 429 `TooManyRequests` | 429 `QUOTA_EXCEEDED` (+ `Retry-After`) | pauses that provider: `Retry-After`, else 30 s doubled per failure in a row, at most 15 min (`northline.push.backoff`, `max-backoff`); while paused it isn't called |
| down, time-out | 5xx, no answer | 5xx `UNAVAILABLE`/`INTERNAL` | same pause |
| our credentials | 403 `ExpiredProviderToken`/`InvalidProviderToken` → a new JWT, one more try | 401 → a new access token, one more try | then as "down" (check the key, § 6) |
| payload refused | other 4xx (`PayloadTooLarge`, `BadTopic`, …) | other 400s | ERROR `refused a … push for good` — a bug to fix; not retried |

- Delivered to one installation = sent. When none was reached because a provider was throttled or down, the push
  fails with `PushDeliveryFailed` and its claim is released:
  - **team notices** (S-27, consumer group `notifications`): the event's retry topics, then `.dlq`;
  - **customer and courier notices** (consumer group `personal-notifications`): written to
    `messaging.deferred_notifications` and sent by the deferred job (every minute; again every 5 min or at the
    provider's `Retry-After`; given up after 10 attempts with `DEAD-LETTERED deferred …`, ERROR). The same for their
    SMS and email outages. This group has **no Kafka retry topics** — 15 more would pass Event Hubs Premium's 100 per
    processing unit (`TopicCatalogueTest`) — so only an unexpected failure (a bug, the database) reaches its `.dlq`.
- The pause is per worker replica (in memory).
- **Metrics:** `northline_push_sent_total{platform,app,outcome=delivered|invalidtoken|throttled|unavailable|rejected|paused}`
  and S-27's `northline_notifications_sent_total{channel,type,outcome}`.

## 5. Deep links

| link (consumer host) | custom scheme | app screen | without the app (web) |
|---|---|---|---|
| `https://<zone>/app/orders/<id>` | `ca.northline.app://orders/<id>` | the order and its tracking | `/orders/<id>` |
| `https://<zone>/app/food/orders/<id>` | `ca.northline.app://food/orders/<id>` | the food order | `/food/orders/<id>` |
| `https://<zone>/app/bookings/<id>` | `ca.northline.app://bookings/<id>` | the booking (ETA, sign-off) | `/account/orders` |
| `https://<zone>/app/quotes/<id>` | `ca.northline.app://quotes/<id>` | the quote received | `/quotes/<id>` |
| `https://<zone>/app/cases/<number>` | `ca.northline.app://cases/<number>` | the refund case | `/account/orders?view=cases` |
| `https://<zone>/courier/run` | `ca.northline.courier://run` | the courier's run | `/` |

- **Universal links / App Links:** the consumer host's `/.well-known/apple-app-site-association` and
  `/.well-known/assetlinks.json` (S-97, `web/apps/consumer/server/app-links.mjs`; `NL_APPLE_TEAM_ID`,
  `NL_IOS_BUNDLE_IDS`, `NL_ANDROID_PACKAGES`, `NL_ANDROID_CERT_SHA256` — [mobile.md](mobile.md)) give `/app/*` to the
  consumer app and `/courier/*` to the courier app. On Android the app's manifest declares the same paths
  (`intentFilters` with `autoVerify`). S-102 adds no association of its own.
- **Without the app** the consumer web answers each `/app/…` link with a redirect to its web page
  (`server/deep-links.mjs`, site host only; `/app/oauth2redirect` stays S-29's).
- **In the apps** (`@northline/mobile-kit`): `parseDeepLink(link, hosts)` → `{screen, id}` for the paths above only
  (another host, another path or an id outside `[A-Za-z0-9_-]{1,64}` → ignored); `routeOf()` → the expo-router path;
  `handleNotificationTaps(platform, hosts, open)` routes taps, the one that launched the app included.
- The push's `link` is the https form (`CONSUMER_ORIGIN` + path); emails use the same link.

## 6. Set-up

### Apple (APNs)

1. Apple Developer → Certificates, Identifiers & Profiles → **Identifiers**: enable **Push Notifications** on
   `ca.northline.app` and `ca.northline.courier` (and the `.dev` / `.preview` variants that should get pushes).
2. **Keys** → "+" → APNs (team-scoped, sandbox and production). Download the `.p8` once (Apple keeps no copy). Note
   the **Key ID** (10 characters) and the **Team ID** (Membership).
3. Secrets manager: `push-apns-key` = the `.p8` file's whole PEM text. ConfigMap / overlay:
   `PUSH_APNS_KEY_ID`, `PUSH_APNS_TEAM_ID`. Development builds (installed from Xcode / EAS development) register
   **sandbox** tokens: in dev set `PUSH_APNS_URL=https://api.sandbox.push.apple.com` and the `.dev` bundle ids as
   `PUSH_APNS_CONSUMER_TOPIC` / `PUSH_APNS_COURIER_TOPIC`. TestFlight and App Store builds use production APNs.
4. EAS / the app config: the `aps-environment` entitlement comes with `expo-notifications`' config plugin — in both
   apps since S-103 (`production` for preview and store builds, `development` for development builds; checked by
   `make mobile-consumer-native-check`). EAS needs no APNs key: the worker sends with its own provider token.

### Firebase (FCM)

1. Firebase console → a project per environment (`northline-<env>`). APNs and FCM are global services with no
   Canadian region: that is why only tokens, words and ids are sent (§ 1). Add the Android apps
   (`ca.northline.app`, `ca.northline.courier`, the variants); the apps' `google-services.json` comes from here — store
   it per environment as the EAS file variable `GOOGLE_SERVICES_JSON` (`app.config.ts` passes it to
   `android.googleServicesFile`; never commit it — [mobile-release.md § Push credentials](mobile-release.md#push-credentials)).
2. Project settings → **Service accounts** → "Generate new private key" (a service account with the "Firebase Cloud
   Messaging API Admin" role, nothing more). Enable the **Firebase Cloud Messaging API (V1)**.
3. Secrets manager: `push-fcm-service-account` = the JSON key (one line). The project id is read from it.

### The worker

| variable | value |
|---|---|
| `PUSH_PROVIDER` | `native` (prod requires it; `local` = the log) |
| `PUSH_APNS_KEY_ID`, `PUSH_APNS_TEAM_ID` | from step 2 |
| `PUSH_APNS_KEY` (secret) | the `.p8` PEM |
| `PUSH_FCM_SERVICE_ACCOUNT` (secret) | the service account JSON |
| `PUSH_APNS_URL`, `PUSH_APNS_CONSUMER_TOPIC`, `PUSH_APNS_COURIER_TOPIC`, `PUSH_FCM_URL`, `PUSH_STALE_AFTER` | defaults `https://api.push.apple.com`, `ca.northline.app`, `ca.northline.courier`, `https://fcm.googleapis.com`, `90d` |
| `CONSUMER_ORIGIN` | set by the chart (`urls.consumer`): the deep links' host |

Terraform creates `push-apns-key` and `push-fcm-service-account` empty in all three clouds (`app_secrets`); the chart
maps them for the worker (`apps.worker.secretEnv`, required in `values-prod.yaml`, optional elsewhere — set them to
`true` in the overlay once the secret holds a value: one empty remote secret fails the whole ExternalSecret).
`PUSH_PROVIDER=native` with anything missing stops the worker at start-up naming every missing variable.

**Rotation:** [secrets.md](secrets.md) (two APNs keys may exist at once; add a service-account key, then delete the
old one).

### First real send checklist

1. A development build on a phone, signed in, notifications allowed: `select platform, permission, refreshed_at from
   messaging.push_devices where user_id = '<you>'` shows it.
2. Dev with `PUSH_PROVIDER=native` and the sandbox URL; trigger a notice (dispatch: give yourself a run → "New run").
3. `northline_push_sent_total{outcome="delivered"}` rises; on failure the worker logs APNs' `reason` / FCM's
   `errorCode`. `BadDeviceToken` from sandbox/production mix-ups is the usual first error.
4. Tap it: the app opens the run / order.
5. Record the result in DECISIONS (S-102 "never run against the real services").

## 7. Locally

`PUSH_PROVIDER=local` (the default): the worker logs `PUSH (local — no push provider) to user … (consumer app): Out
for delivery`. The apps' registrations are stored all the same (`PUT /api/v1/me/devices/…` with an app token — under
`local` the api's dev auth has no `cnf`, so use the apps against northline-auth, [mobile-auth.md](mobile-auth.md)).

## 8. Tests

api `PushDeviceApiTest` (register/refresh, courier vs consumer app, denied permission without a token, bearer and
Studio tokens 403, someone else's installation 404, shared phone, validation messages en/fr, a customer unsubscribe
link), `DpopResourceServerTest` (the registry with a real DPoP proof), `DispatchApiTest` (`run.changed`),
`NotificationMatrixDefaultsSpecTest`; worker `PushProvidersTest` (WireMock APNs and FCM: provider JWT and OAuth
assertion signatures, headers, payload shape, dead tokens deleted, throttling pauses and honours `Retry-After`, a
renewed provider token, stale and denied devices skipped), `PushEndToEndTest` (the real adapters behind a customer
event: no personal data in either payload, the dead Android token removed), `CustomerNotificationsTest` (matrix,
language, quiet hours in the province's zone, quiet hours off, outages retried from the table, bookings and quotes,
courier runs at night, the evening-before reminder once), `TopicCatalogueTest`; web `deepLinks.test.ts`; mobile-kit
`push.test.ts` (registration, offline retries, one sync at a time, sign-out, the hook registrar, deep links, the
expo-notifications adapter).
