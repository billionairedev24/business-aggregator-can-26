# Mobile auth — the consumer and courier apps (S-29)

How the Northline consumer app and the courier app sign people in and call the api: OAuth 2.1 authorization code with
PKCE in the phone's browser, tokens bound to a key the app holds (DPoP, RFC 9449), refresh tokens that rotate on every
use and end the sign-in when an old one comes back. For mobile developers first, operators second.

> **Status (2026-10-01):** northline-auth and the api implement everything below and it is tested end to end with
> generated P-256 keys (`MobileDpopApiTest`, `DpopResourceServerTest`, `ReplayStoreTest`). The courier app (S-87,
> [courier-app.md](courier-app.md)) implements the app side in `@northline/mobile-kit` (`mobile/packages/mobile-kit`),
> tested in Jest and in a headless-browser build against an in-app stand-in of the servers; it has **not run on a real
> iPhone or Android device**, and its key is a software key in the Keychain / Keystore-encrypted storage, not yet a
> Secure Enclave / StrongBox key (§ 1). The consumer app (S-97, [mobile.md](mobile.md)) uses the same kit. Since S-97
> the consumer site serves the App Link / Universal Link association files once the Apple team id and the Play
> signing certificate are configured (see [Redirects](#redirects)).

Other runbooks: [README § OAuth clients](README.md#oauth-clients-s-122) · [README § Sessions](README.md#sessions-s-19)
· [edge](edge.md) · [local](local.md)

## The clients

| | consumer app | courier app |
|---|---|---|
| `client_id` | `mobile-consumer` (shown as "Northline") | `courier-app` (shown as "Northline Courier") |
| type | public: no secret, PKCE S256 required | same |
| DPoP | required on every token request | same |
| redirect URIs | `https://<consumer host>/app/oauth2redirect`, `ca.northline.app:/oauth2redirect` | `https://<consumer host>/courier/oauth2redirect`, `ca.northline.courier:/oauth2redirect` |
| scopes | `openid profile orders bookings offline_access` | `openid courier deliveries` |
| access token | JWT ES256, 10 min, `token_type: DPoP`, `cnf.jkt` = your key | same |
| refresh token | opaque, rotates on every use, **30 days** | opaque, rotates, **12 hours** (one shift — design 05: "device-bound, short refresh") |
| `acr=mfa` needed | no | no (only business and staff tokens need it — ARCHITECTURE.md § Identity) |

Hosts per environment ([edge.md](edge.md)): auth `https://auth.<zone>`, api `https://api.<zone>/api/v1`, consumer
`https://<zone>` (`<zone>` = `dev.northline.ca`, `staging.northline.ca`, `northline.ca`). Discovery:
`https://auth.<zone>/.well-known/openid-configuration` (it lists `dpop_signing_alg_values_supported`).

```
app ──(1) system browser: /oauth2/authorize?…&code_challenge=…──────────────► northline-auth
                         (not signed in → the sign-in page → back to the same request)
    ◄─(2) redirect https://<zone>/app/oauth2redirect?code=…&state=… ─────────
    ──(3) POST /oauth2/token  code + code_verifier, DPoP: <proof>────────────► northline-auth
    ◄──── 400 use_dpop_nonce, DPoP-Nonce: n1 ────────────────────────────────  (first time only)
    ──(3') the same with a proof that carries nonce n1 ─────────────────────►
    ◄──── {access_token (cnf.jkt), refresh_token, id_token, token_type: DPoP}, DPoP-Nonce: n2
    ──(4) GET https://api.<zone>/api/v1/…  Authorization: DPoP <access>, DPoP: <proof with ath>──► api
    ──(5) every ≤ 10 min: POST /oauth2/token grant_type=refresh_token + proof (nonce n2) ──► northline-auth
```

## 1. The key

- One EC **P-256** key pair per app installation, created on first sign-in, **not exportable**: iOS Secure Enclave
  (`SecKeyCreateRandomKey` with `kSecAttrTokenIDSecureEnclave`, `ecdsaSignatureMessageX962SHA256`), Android Keystore
  (`KeyPairGenerator` `EC` / `secp256r1`, `setIsStrongBoxBacked(true)` where available, `PURPOSE_SIGN`). Sign with
  **ES256** (the JWS signature is `R || S`, 64 bytes — convert from the platforms' DER signatures).
- The tokens only work with this key: keep it for the life of the sign-in. A new key means a new sign-in. Delete it
  at sign-out.
- Its public part goes in every proof's header as a JWK (`kty`, `crv`, `x`, `y` — never `d`). The server binds tokens
  to its RFC 7638 thumbprint (`cnf.jkt`).

## 2. Sign-in

Open the authorization request in the **system browser session** — `ASWebAuthenticationSession` (iOS) or Custom Tabs
(Android), never an embedded WebView (RFC 8252):

```
https://auth.<zone>/oauth2/authorize?response_type=code
  &client_id=mobile-consumer
  &redirect_uri=https%3A%2F%2F<zone>%2Fapp%2Foauth2redirect
  &scope=openid%20profile%20orders%20bookings%20offline_access
  &state=<random, check it on return>
  &code_challenge=<BASE64URL(SHA-256(code_verifier))>&code_challenge_method=S256
```

- `code_verifier`: 43–128 characters of `[A-Za-z0-9-._~]`, random per request. `plain` is refused; a request without
  a challenge gets no code.
- Not signed in yet: the browser goes to the client's sign-in page — the consumer site's (`CONSUMER_ORIGIN/sign-in`)
  for both apps: `courier-app` since S-87 and `mobile-consumer` since S-97 (both are in
  `northline.auth.consumer-clients`). After the sign-in or "Create account" succeeds, the page **goes back to your
  authorization request** (northline-auth answers the sign-in with `continueTo`), and northline-auth redirects to your
  redirect URI with `code` and `state`. Already signed in on this phone's browser: the code comes back at once.
- Every sign-in today uses a second factor (passkey, authenticator app or backup code), so the tokens carry
  `acr=mfa` and `amr`; the consumer and courier apps must not depend on it.

### Redirects

- **Preferred: the claimed https link** — iOS Universal Link / Android App Link on the consumer host
  (`/app/oauth2redirect`, `/courier/oauth2redirect`). The consumer host must serve
  `/.well-known/apple-app-site-association` and `/.well-known/assetlinks.json` naming the apps (team id + bundle id;
  package + signing certificate SHA-256) and claiming those paths. **Served since S-97** by the consumer web
  (`web/apps/consumer/server/app-links.mjs`) from the chart's `mobileApps` values (`NL_APPLE_TEAM_ID`,
  `NL_IOS_BUNDLE_IDS`, `NL_ANDROID_PACKAGES`, `NL_ANDROID_CERT_SHA256`); each answers 404 until its team id /
  certificate is configured ([mobile.md § App Links](mobile.md#app-links)). With iOS 17.4+ use
  `ASWebAuthenticationSession.Callback.https(host:path:)`.
- **Fallback: the custom scheme** `ca.northline.app:/oauth2redirect` / `ca.northline.courier:/oauth2redirect`
  (RFC 8252 § 7.1). Works today; another app could claim the same scheme, which PKCE makes harmless (it can't redeem
  the code without your `code_verifier`).
- Redirect URIs match exactly (no extra path, query or trailing slash). A new one is a configuration change in
  `northline.oauth.clients` ([README § OAuth clients](README.md#oauth-clients-s-122)).

## 3. DPoP proofs

A proof is a JWT you sign with the app's key, sent in the `DPoP` header of **every** token request and api call — a
new one each time:

```
header  {"typ":"dpop+jwt","alg":"ES256","jwk":{"kty":"EC","crv":"P-256","x":"…","y":"…"}}
claims  {"jti":"<random UUID>", "htm":"POST", "htu":"https://auth.<zone>/oauth2/token",
         "iat":<now, seconds>, "nonce":"<last DPoP-Nonce>",            ← northline-auth
         "ath":"<BASE64URL(SHA-256(access token))>"}                   ← api calls only
```

| claim | rule |
|---|---|
| `htm`, `htu` | the request's method and URL **exactly**, without query or fragment (`https://api.<zone>/api/v1/me/orders`, not `…?page=2`) |
| `iat` | within **30 s** of the server's clock. Phones drift: compute it from the server time (the `Date` header of any answer + elapsed time) |
| `jti` | never reused: every proof works **once** across every server instance |
| `nonce` | northline-auth only: the value of the last `DPoP-Nonce` header it sent you (every token-endpoint answer has one). Kept for 5–10 min; when it's stale you get `400 use_dpop_nonce` with a new one — sign a new proof with it and retry once |
| `ath` | api calls only: hash of the access token the call carries |

## 4. Tokens

**Code exchange** (form-encoded, no client secret):

```
POST https://auth.<zone>/oauth2/token
DPoP: <proof: htm POST, htu …/oauth2/token, nonce>
grant_type=authorization_code&client_id=mobile-consumer&code=…&redirect_uri=…&code_verifier=…
→ 200 {"access_token":"…","token_type":"DPoP","expires_in":599,"refresh_token":"…","id_token":"…","scope":"…"}
```

The first request of a fresh install has no nonce yet: send it without `nonce`, read `DPoP-Nonce` from the
`400 use_dpop_nonce` answer and send the same request with a new proof (the code isn't spent by a refused proof).

**Refresh** — before the access token expires, or on a 401 from the api:

```
POST https://auth.<zone>/oauth2/token
DPoP: <new proof with the latest nonce>
grant_type=refresh_token&client_id=mobile-consumer&refresh_token=…
```

- The answer carries a **new refresh token; the old one is dead.** Store the new one (Keychain / EncryptedFile or
  Keystore-wrapped storage) before you use anything from the answer.
- **Never present a refresh token twice.** A rotated refresh token that comes back means someone else may hold a copy,
  so northline-auth revokes the whole chain (every refresh token of that sign-in) and ends the sign-in: the next
  refresh answers `invalid_grant` and the person signs in again. Run **one refresh at a time** (a single in-flight
  refresh that other requests wait for). If a refresh's answer is lost (time-out), don't retry it with the same token
  blindly: retrying is the same as reuse. Sign in again instead — or accept that a retry ends the sign-in.
- Proofs from another key, for another URL/method, reused proofs and proofs without a proof (`DPoP` missing) are all
  refused with `400 invalid_dpop_proof`, and spend nothing: the refresh token still works.

## 5. Calling the api

```
GET https://api.<zone>/api/v1/…
Authorization: DPoP <access token>
DPoP: <proof: htm GET, htu = this URL without query, ath = hash of the access token, no nonce>
```

- `Authorization: Bearer <DPoP-bound token>` is refused (401) — a copied token is useless without the key.
- A missing, reused, wrong-key or wrong-URL proof: `401` with `WWW-Authenticate: DPoP error="invalid_dpop_proof"`
  (or `invalid_token` for the token itself). Expired access token: refresh, then retry with a new proof.
- Scopes decide what an app token can reach; merchant (`/api/v1/merchants/**`) and console endpoints refuse app tokens
  (no `merchant` / `console` scope) whatever their `acr`.

## 6. Signing out

```
POST https://auth.<zone>/oauth2/revoke
client_id=mobile-consumer&token=<refresh token>&token_type_hint=refresh_token
```

This ends the whole sign-in (S-20: single sign-out — the phone's browser session too, so the next sign-in asks again).
Then delete the tokens and the key. Public clients can revoke; they cannot introspect.

## 7. The person's view: Settings › Security

Each app sign-in is listed in Studio Settings › Security (and later the consumer account's security page) as its own
session, with the app's name, for as long as its refresh token lives. "Sign out" there, or "sign out all other
sessions", revokes the app's refresh tokens at once; its current access token keeps working until it expires
(≤ 10 min) — and only with the app's key. A reuse-detected sign-in shows up in the audit log as
`auth.refresh_token_reused` and `auth.session_revoked` (`reason: refresh_token_reused`).

## Errors

| where | answer | meaning → what the app does |
|---|---|---|
| token | `400 use_dpop_nonce` + `DPoP-Nonce` | nonce missing or stale → new proof with this nonce, retry once |
| token | `400 invalid_dpop_proof` | no proof, two proofs, bad signature, wrong `htm`/`htu`, `iat` off by > 30 s, reused `jti`, or (refresh) another key → fix the proof; don't retry the same one |
| token | `400 invalid_grant` | code used/expired, or the refresh token is dead (rotated, revoked, sign-in ended, reuse detected) → sign in again |
| token | `401 invalid_client` | unknown `client_id` (a known app's request without `DPoP` gets `invalid_dpop_proof`) |
| token | `503 temporarily_unavailable`, `Retry-After: 30` | the proof store (Valkey) is down → wait and retry with a new proof |
| api | `401 WWW-Authenticate: DPoP …` / `Bearer error="invalid_token"` | see § 5 |
| api | `403 forbidden` | the token's scope doesn't allow this endpoint |

## Operations

- **State:** used proof ids and nonces in Valkey — northline-auth `nl:auth-replay:dpop-jti:<sha256>` (≤ 70 s) and
  `nl:auth-replay:dpop-nonce:<window>` (15 min), the api `nl:api-dpop:jti:<sha256>`. `REPLAY_STORE=redis` (default; `memory`
  only under `local`/`test`, refused under staging/prod), `DPOP_NONCE_LIFETIME` (default `5m`). Nothing here holds a
  token or a proof.
- **Valkey down:** fail closed. Token requests carrying DPoP answer `503` (logged `Replay store unavailable`);
  api calls with DPoP tokens answer `401` until Valkey is back. BFF (Studio) sign-ins are not affected.
- **Refresh-token families:** `auth.issued_refresh_tokens` (V024) keeps the SHA-256 of every refresh token issued to a
  public client, deleted with its authorization. `revoke_reason = refresh_token_reused` in `identity.sessions` counts
  reuse; a spike means a bug in an app's refresh handling (concurrent refreshes, retries) more often than theft — look
  at the app version first.
- **Edge:** the api host routes all of `/api/v1` (`apps.api.tokenClients`, [edge.md](edge.md)); the apps never use the
  BFF.
- **Ending a person's app sign-ins** (lost phone): Settings › Security, or the SQL in
  [README § Sessions](README.md#sessions-s-19).

## Try it locally

Run auth (`./gradlew :auth:bootRun --args='--spring.profiles.active=local'`) and sign in as a persona in the Studio
(README § Local sign-in). This Node 20+ script (`npm i jose`) plays the app: it prints the authorization URL, waits for
the `code` you paste from the redirect (the browser can't open `ca.northline.app:` — copy it from the address bar or
the network tab), then exchanges it with DPoP and refreshes once.

```js
// mobile-dpop.mjs — node mobile-dpop.mjs
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import readline from 'node:readline/promises';
import { SignJWT, exportJWK, generateKeyPair } from 'jose';

const AUTH = 'http://localhost:9000', CLIENT = 'mobile-consumer', REDIRECT = 'ca.northline.app:/oauth2redirect';
const b64 = (b) => Buffer.from(b).toString('base64url');
const { privateKey, publicKey } = await generateKeyPair('ES256');
const jwk = await exportJWK(publicKey);
const proof = (htm, htu, nonce) => new SignJWT({ htm, htu, jti: randomUUID(), ...(nonce && { nonce }) })
  .setProtectedHeader({ typ: 'dpop+jwt', alg: 'ES256', jwk }).setIssuedAt().sign(privateKey);
let nonce;
async function token(params) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const res = await fetch(`${AUTH}/oauth2/token`, { method: 'POST',
      headers: { DPoP: await proof('POST', `${AUTH}/oauth2/token`, nonce), 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ client_id: CLIENT, ...params }) });
    nonce = res.headers.get('dpop-nonce') ?? nonce;
    const body = await res.json();
    if (body.error !== 'use_dpop_nonce') return body;
  }
}
const verifier = b64(randomBytes(32));
console.log(`${AUTH}/oauth2/authorize?` + new URLSearchParams({ response_type: 'code', client_id: CLIENT,
  redirect_uri: REDIRECT, scope: 'openid profile orders', state: 'try',
  code_challenge: b64(createHash('sha256').update(verifier).digest()), code_challenge_method: 'S256' }));
const code = (await readline.createInterface({ input: process.stdin, output: process.stdout }).question('code: ')).trim();
const tokens = await token({ grant_type: 'authorization_code', code, redirect_uri: REDIRECT, code_verifier: verifier });
console.log(tokens.token_type, JSON.parse(Buffer.from(tokens.access_token.split('.')[1], 'base64url')).cnf);
console.log('refreshed:', (await token({ grant_type: 'refresh_token', refresh_token: tokens.refresh_token })).token_type);
process.exit(0);
```

Presenting `tokens.refresh_token` a second time after that refresh shows reuse detection: `invalid_grant`, and the
sign-in disappears from Settings › Security.
