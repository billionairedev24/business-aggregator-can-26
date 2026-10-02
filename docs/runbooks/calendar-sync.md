# Google and Microsoft calendar two-way sync (S-32)

Availability › Calendar sync & team: each team member connects **their own** Google Calendar or Outlook /
Microsoft 365 calendar. Busy times in the calendars they choose block their Northline slots; their confirmed Northline
bookings are written to their main calendar and moved or removed when the booking changes. How it behaves and why:
[DECISIONS.md § S-32](../DECISIONS.md). Code: `ca.northline.availability` (`application/Calendar*`,
`integration/*CalendarGateway`), envelope encryption `ca.northline.shared.crypto`.

> **Status (2026-09-30):** written against Google's and Microsoft's documentation and tested with WireMock stand-ins
> (`CalendarProvidersWireMockTest`). **Nothing has run against the real Google Calendar API or Microsoft Graph** — no
> Google Cloud project or Entra app registration exists yet. Do the first real connection in dev and walk through
> [Checking an environment](#checking-an-environment) before staging.

## How it works

```
Studio ── POST …/availability/calendars/google ──▶ api: PKCE request (state, verifier) → consent URL
browser ──▶ accounts.google.com / login.microsoftonline.com (consent) ──▶ 302
browser ──▶ https://studio.<zone>/api/v1/calendar/oauth/google/callback?code&state   (through the studio-bff: signed in)
api: state = the one this member started (single use, 10 min) → code + verifier → tokens → refresh token sealed (KMS)
    → 303 /b/<merchant>/availability?calendar=google&result=connected
after commit (outbox): first read of the chosen calendars · notification channel per calendar · bookings written
Google push / Graph notification ──▶ https://api.<zone>/api/v1/webhooks/calendar/{google|microsoft}  (public, verified)
every 5 min (CALENDAR_SYNC_INTERVAL): incremental read of every calendar (safety net) + write-back
hourly: channels renewed before they expire; old dedupe rows purged
```

| | Google | Microsoft |
|---|---|---|
| OAuth | authorization code + PKCE (S256), `access_type=offline`, `prompt=consent`, `include_granted_scopes=true` | v2.0 endpoints, authorization code + PKCE (S256), tenant `common` |
| scopes on Connect | `openid email https://www.googleapis.com/auth/calendar.events.owned` | `openid profile offline_access https://graph.microsoft.com/Calendars.ReadWrite` |
| incremental consent | "Choose calendars" asks for `calendar.calendarlist.readonly` the first time | not needed (`Calendars.ReadWrite` lists calendars) |
| busy times | `events.list` (`singleEvents=true`) with `syncToken`; 410 → read again | `calendarView/delta` (window: yesterday … +180 days, slid weekly) with the delta link |
| not busy | cancelled, `transparency: transparent`, declined by the member, Northline's own events | `@removed`, cancelled, `showAs` free / workingElsewhere, declined, Northline's own |
| notifications | `events.watch` push channel, token = a per-channel secret; renewed by opening a new channel | subscription on `me/calendars/{id}/events` with `clientState`; `PATCH` to renew; lifecycle notifications |
| bookings written to | `primary` (event id derived from the booking → a retried insert is a 409, not a duplicate) | the default calendar (`transactionId = nl-<booking>`) |
| disconnect | channels stopped, upcoming Northline events deleted, **grant revoked** (`oauth2.googleapis.com/revoke`) | subscriptions deleted, upcoming events deleted; Microsoft has **no endpoint an app can call to revoke one user's consent**: the token is destroyed, and the member removes "Northline" at https://myapps.microsoft.com (work/school) or https://account.live.com/consent/Manage (personal) |

What Northline keeps of someone's own events: **the provider's event id, start and end** (`availability.calendar_busy_blocks`). Never the title, attendees, location or description.
What Northline writes (`BookingEventText`): *"Brake inspection · Amara"*, the job address as location, and
*"Northline booking BK-1234 · réservation Northline"* with a link to the Studio's Appointments. The customer's first
name and address are what the design promises ("…written to your calendar with the customer's first name and
address"); last name, phone, email, notes, access codes and vehicle never leave Northline.

A refused refresh (`invalid_grant` — the member removed Northline in their account, changed their password, or the
token expired) or a second 401 turns the link to **reconnect**: the Studio shows "Access expired or was removed ·
reconnect to keep syncing" with **Reconnect** (also in Settings › Integrations), nothing is read or written, and the
last known busy times keep blocking slots until the member reconnects or disconnects.

## Variables (api)

| variable | required | value |
|---|---|---|
| `CALENDAR_PROVIDER` | staging, prod (`local` refused there) | `local` (fake Google and Outlook) · `oauth` |
| `GOOGLE_CALENDAR_CLIENT_ID`, `GOOGLE_CALENDAR_CLIENT_SECRET` | no — empty: Google Calendar shows "Not available yet" | the OAuth client below; the secret → secrets manager `google-calendar-client-secret` |
| `MICROSOFT_CALENDAR_CLIENT_ID`, `MICROSOFT_CALENDAR_CLIENT_SECRET` | no — empty: Outlook shows "Not available yet" | the app registration below; the secret → secrets manager `microsoft-calendar-client-secret` |
| `MICROSOFT_CALENDAR_TENANT` | no (`common`) | `common` = work, school and personal accounts; a tenant id limits it to one organisation |
| `KMS_PROVIDER`, `KMS_ENCRYPTION_KEY_ID` | staging, prod | the cloud key service and the `tokens` key (Terraform `config_env`) — [below](#the-envelope-key-kms_encryption_key_id) |
| `STUDIO_ORIGIN` | yes (already) | builds the redirect URIs `<STUDIO_ORIGIN>/api/v1/calendar/oauth/{google,outlook}/callback` |
| `API_PUBLIC_URL` | staging, prod (already, S-13) | builds the notification URLs `<API_PUBLIC_URL>/api/v1/webhooks/calendar/…`; must be `https://` — with `http://` (a laptop) no channel is opened and the 5-minute read does the work |
| `CALENDAR_SYNC_INTERVAL`, `CALENDAR_WEBHOOK_RATE_LIMIT` | no | `PT5M`, `600` notifications per minute and client address |

Secrets are listed in Terraform's `app_secrets` (created empty in AWS Secrets Manager / Google Secret Manager; Azure
Key Vault can't hold an empty secret, so the operator creates them) and in the chart's `externalSecrets.secretNames`;
map them in the environment's values once set (`apps.api.secretEnv.GOOGLE_CALENDAR_CLIENT_SECRET: true` and
`externalSecrets.optionalKeys`). The client ids are not secret: put them in the operator's `configEnv`.

## Redirect and notification URIs per environment

| environment | OAuth redirect URIs (both providers) | notification endpoints (api host, public) |
|---|---|---|
| local | `http://localhost:3100/api/v1/calendar/oauth/google/callback`, `…/outlook/callback` (Vite proxies `/api` to the api) | none (no public HTTPS) |
| dev | `https://studio.dev.northline.ca/api/v1/calendar/oauth/google/callback`, `…/outlook/callback` | `https://api.dev.northline.ca/api/v1/webhooks/calendar/google`, `…/microsoft`, `…/microsoft/lifecycle` |
| staging | `https://studio.staging.northline.ca/api/v1/calendar/oauth/google/callback`, `…/outlook/callback` | `https://api.staging.northline.ca/api/v1/webhooks/calendar/…` |
| prod | `https://studio.northline.ca/api/v1/calendar/oauth/google/callback`, `…/outlook/callback` | `https://api.northline.ca/api/v1/webhooks/calendar/…` |

The redirect URIs are on the **Studio** host on purpose: the browser comes back through the studio-bff with the
member's session, so the callback knows who is signed in and refuses a state started by someone else. The
notification endpoints are on the **api** host (the Gateway routes `/api/v1/webhooks/calendar` there — S-17,
[edge.md](edge.md)); they need no session and are verified per channel.

## Google Cloud

Once per environment (one project per environment keeps quotas and consent screens apart; a single project with
three clients works too). Do it in a project owned by the platform account, in the organisation's Canadian policy
folder if you have one — the Calendar API itself is a global Google service (see [Residency](#residency)).

1. **APIs & Services › Library:** enable **Google Calendar API**.
2. **OAuth consent screen** (Google Auth Platform › Branding / Audience / Data access):
   - User type **External**; app name "Northline", support email, logo, home page `https://northline.ca`, privacy
     policy `https://northline.ca/legal/privacy.html`, terms `https://northline.ca/legal/terms.html`; authorised
     domain `northline.ca`.
   - Data access: add `openid`, `…/auth/userinfo.email`, `…/auth/calendar.events.owned` and
     `…/auth/calendar.calendarlist.readonly`. Both calendar scopes are **sensitive** (not restricted): production use
     by more than 100 accounts needs Google's **app verification** (brand + sensitive-scope review, a few days to
     weeks, a YouTube demo of the consent flow). Start it before the launch; until it passes, Google shows the
     "unverified app" screen and caps users at 100.
   - While in *Testing*, add the team's Google accounts as test users (refresh tokens of apps in Testing expire after
     7 days — expect "reconnect" in dev).
3. **Credentials › Create credentials › OAuth client ID › Web application**, name "Northline calendar (<env>)":
   - Authorised redirect URI: `https://studio.<zone>/api/v1/calendar/oauth/google/callback` (dev also
     `http://localhost:3100/api/v1/calendar/oauth/google/callback` if the team uses the dev client locally).
   - No JavaScript origins (the browser never calls Google's APIs).
4. Copy the **client ID** → `GOOGLE_CALENDAR_CLIENT_ID` (configEnv) and the **client secret** → secrets manager
   `google-calendar-client-secret` → `GOOGLE_CALENDAR_CLIENT_SECRET`.
5. **Push notifications:** Google's current guide asks only for an HTTPS address with a valid certificate
   (cert-manager's Let's Encrypt one is fine). Older guides also required verifying the domain; if `events.watch`
   answers `push.webhookUrlUnauthorized`, verify `api.<zone>` in Search Console and add it under the project's
   domain verification. Until channels work, the 5-minute read keeps busy times current.

The sign-in client of S-18 ([federation.md](federation.md)) is a different client (other redirect URI, northline-auth
uses it); keep them separate so the calendar scopes never appear on the sign-in consent screen.

## Microsoft Entra

Once, in the platform's Entra tenant (one registration per environment):

1. **Microsoft Entra admin center › App registrations › New registration**, name "Northline calendar (<env>)".
   - Supported account types: **Accounts in any organizational directory and personal Microsoft accounts**
     (multitenant + personal, matches `MICROSOFT_CALENDAR_TENANT=common`).
   - Redirect URI: platform **Web**, `https://studio.<zone>/api/v1/calendar/oauth/outlook/callback`.
2. **API permissions › Add › Microsoft Graph › Delegated:** `Calendars.ReadWrite`, `offline_access`, `openid`,
   `profile` (remove the default `User.Read` — not used). No admin consent is required for these; an organisation
   that blocks user consent will need its admin to consent once.
3. **Certificates & secrets › New client secret** (24 months max; note the expiry in the platform calendar) → the
   **Value** → secrets manager `microsoft-calendar-client-secret` → `MICROSOFT_CALENDAR_CLIENT_SECRET`.
   **Overview › Application (client) ID** → `MICROSOFT_CALENDAR_CLIENT_ID` (configEnv).
4. **Branding & properties:** publisher domain `northline.ca` (verify it), logo, terms and privacy URLs. For
   organisations outside ours, **publisher verification** (Microsoft Partner Network id) removes the "unverified"
   warning and is required for multitenant consent in many tenants — do it before the launch.
5. Graph change notifications need nothing in the registration: Graph calls
   `https://api.<zone>/api/v1/webhooks/calendar/microsoft?validationToken=…` while a subscription is created and
   expects the token back as `text/plain` within 10 seconds. The api answers only while one of its own subscriptions
   is being created (a pending row, 2 minutes) and otherwise refuses with 403.

## The envelope key (`KMS_ENCRYPTION_KEY_ID`)

Refresh tokens are sealed with envelope encryption (`ca.northline.shared.crypto.SecretSealer`): a fresh AES-256-GCM
data key per token, bound to the link's id; the data key is wrapped by the cloud key service with the environment's
**`tokens`** key, and only the wrapped key, the ciphertext and the key reference are stored
(`availability.calendar_links.refresh_token_key`, `refresh_token_enc`, `token_ref`). The key never leaves the key
service. Terraform creates `tokens` next to `data` and `signing` (module `kms`) and lets **only the api's workload
identity** use it:

| cloud | key | api may | `KMS_ENCRYPTION_KEY_ID` |
|---|---|---|---|
| AWS | symmetric KMS key, yearly rotation | `kms:Encrypt`, `kms:Decrypt`, `kms:ReEncrypt*`, `kms:GenerateDataKey*`, `kms:DescribeKey` (encryption context `northline=<link id>`) | key ARN |
| Google Cloud | `ENCRYPT_DECRYPT`, `GOOGLE_SYMMETRIC_ENCRYPTION`, yearly rotation | `roles/cloudkms.cryptoKeyEncrypterDecrypter` (AAD = link id) | crypto key name `projects/…/cryptoKeys/tokens` |
| Azure | RSA 3072 (RSA-HSM in prod), `wrapKey`/`unwrapKey`, rotation policy | "Key Vault Crypto User" on the key | versioned key URL (the version that wrapped each token is stored with it) |

`KMS_PROVIDER=local` (laptops, the kind rehearsal) wraps with `KMS_LOCAL_KEY` (base64 of 32 bytes; a fixed development
key under `local`/`test`); refused under staging/prod.

**Rotating:** automatic rotation (AWS, Google Cloud: new backing key version; Azure: new key version) keeps old
versions for decryption, so nothing is re-sealed. Never disable or destroy a version that sealed live tokens: every
member's calendar would drop to "reconnect". Changing `KMS_ENCRYPTION_KEY_ID` to another key: keep the old key enabled
— tokens sealed with it still name it — until every link was re-sealed (a reconnect re-seals, and Microsoft's rotating
refresh tokens re-seal on refresh). Since S-115 the api's re-wrap job moves every stored data key to the current key
(or key version) within the hour — [key-rotation.md § 2](key-rotation.md#2-kms-data-keys-and-envelope-re-wrap); disable
the old one only after its check query shows nothing left.

## Rotating

- **Client secrets:** Google and Microsoft both allow two secrets at once: add the new one, update the secrets manager,
  restart the api, delete the old one. Stored refresh tokens stay valid (they belong to the client id).
- **Client id / new registration:** every member has to reconnect (their grants belong to the old client).

## Operations

- **Health:** `availability.calendar_links` (`state = 'reconnect'` count; `last_sync_at` older than 15 min for a
  `connected` link means reads are failing — see the api log `Calendar sync step …`), `calendar_channels`
  (`expires_at` in the past = renewal failing), `calendar_sources.synced_at`.
- **Force a full re-read** of one calendar: `update availability.calendar_sources set sync_cursor = null where link_id
  = '…' and calendar_id = '…'` (the next run reads it from scratch).
- **A member's events are wrong in their calendar:** `delete from availability.calendar_event_mirrors where link_id =
  '…'` makes the next write-back create them again (Google: same event ids, so no duplicates; Graph: `transactionId`
  dedupes within Graph's retry window — delete the old events first).
- **Notifications stopped:** check the Gateway route (`kubectl -n northline-<env> get httproute`), the api log for
  `invalid_notification` 403s (a channel we no longer know is harmless — it expires), and the hourly renewal.
  Reads continue every 5 minutes regardless.
- **Quotas:** Google Calendar API: 1,000,000 queries/day per project by default, per-user rate limits; each calendar
  costs about 12 incremental reads an hour plus pushes. Graph: per-app/per-mailbox throttling (429 with
  `Retry-After`); the job retries on its next run.

## Residency

Busy times (start/end only) and booking events pass through Google's and Microsoft's calendar services, wherever the
member's account lives — that's the member's own calendar, which they connect by choice. Northline stores only the
sealed refresh token, event ids and times in its Canadian database; the event text written to the member's calendar
is limited to the service, the customer's first name, the job address and the booking reference (the privacy policy's
"calendar integrations" paragraph should say so — open question for legal).

## Checking an environment

1. Studio › Availability › Calendar sync & team › **Connect** Google Calendar as a test member: Google's consent screen
   lists "See, create, change and delete events on Google calendars you own", back on Availability with "Google
   Calendar is connected".
2. Within a minute: the member's row shows "Two-way · last sync just now · Blocks slots from: Google Calendar";
   a busy event in that calendar tomorrow blocks the matching slots in Weekly hours › Preview (the note says
   "1 busy time from your calendar").
3. Add an event in Google Calendar: the api log shows the push (`POST /api/v1/webhooks/calendar/google` 200) and the
   slot disappears without waiting 5 minutes.
4. A confirmed booking for the member appears in their calendar as "<service> · <first name>" with the address;
   cancel it: the event disappears on the next run.
5. **Choose calendars** asks Google once for the calendar list, then lists them.
6. Remove "Northline" at https://myaccount.google.com/permissions: within 5 minutes the row says "Access expired or
   was removed" with **Reconnect**.
7. Repeat 1–6 with Outlook / Microsoft 365 (step 6: https://account.live.com/consent/Manage or My Apps).

## Checklist

- [ ] Google Calendar API enabled, consent screen with the four scopes, verification submitted (prod)
- [ ] Google OAuth client (Web) with this environment's redirect URI; id in configEnv, secret in the secrets manager
- [ ] Entra app registration (multitenant + personal) with the redirect URI and `Calendars.ReadWrite`, `offline_access`, `openid`, `profile`; publisher verified (prod); secret expiry in the platform calendar
- [ ] `CALENDAR_PROVIDER=oauth`, `KMS_ENCRYPTION_KEY_ID` = Terraform's `tokens` key, api workload identity may encrypt/decrypt with it
- [ ] `apps.api.secretEnv` / `externalSecrets.optionalKeys` map the two client secrets
- [ ] `https://api.<zone>/api/v1/webhooks/calendar/google` answers 403 `invalid_notification` to an empty POST (route and TLS work)
- [ ] [Checking an environment](#checking-an-environment) done with a Google and a Microsoft test account
