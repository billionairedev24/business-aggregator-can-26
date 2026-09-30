# Google and Apple sign-in (S-18)

"Continue with Google / Apple" on the Studio's sign-in and "Create account" tabs. northline-auth is the OIDC client of
Google and Apple; the browser never sees their tokens. How it behaves: [DECISIONS.md § S-18](../DECISIONS.md).

## What the person gets

| what the provider says | where the Studio continues | linked when |
|---|---|---|
| a Google/Apple account already linked to a Northline account | factor step of that account (passkey, authenticator, backup code) | — |
| a **verified** email of an existing Northline account | factor step of that account, notice "Confirm it's you … to link your Google account" | that second factor succeeds in the same browser |
| anything else (new email, unverified email, Apple relay address) | "Create account" pre-filled (first/last name, verified email), then phone code + second factor as for every registration | the account is created |

A Google/Apple login alone never signs anyone in: business accounts always need their passkey or authenticator
(`acr=mfa`), and an email match only links after that second factor. Links live in `auth.federated_identities`
(provider + the provider's `sub`, so a changed email doesn't matter); linking writes `auth.federated_linked` to
`developer.audit_log`. Unlinking isn't in the Studio yet: `DELETE FROM auth.federated_identities WHERE user_id = :u`.

Errors land on `/sign-in?error=…`: `federation_cancelled` (the person pressed Cancel), `federation_unavailable` (the
provider isn't configured in this environment, is down, or refused our client — check the auth log), `federation`
(anything else, e.g. an expired or replayed answer), `rate_limited` (S-9 lookup limits).

## Variables (auth)

| variable | secret | example | notes |
|---|---|---|---|
| `GOOGLE_CLIENT_ID` | no | `1234567890-abc…apps.googleusercontent.com` | empty = Google off |
| `GOOGLE_CLIENT_SECRET` | **yes** | `GOCSPX-…` | secrets manager key `google-client-secret` |
| `APPLE_CLIENT_ID` | no | `ca.northline.auth` (staging: `ca.northline.auth.staging`) | the **Services ID**, not the App ID; empty = Apple off |
| `APPLE_TEAM_ID` | no | `A1B2C3D4E5` | Apple Developer → Membership |
| `APPLE_KEY_ID` | no | `KEY1234567` | the Sign in with Apple key's id |
| `APPLE_PRIVATE_KEY` | **yes** | the `.p8` file's PEM | secrets manager key `apple-private-key`; multi-line, or one line with `\n` |
| `APPLE_CLIENT_SECRET_TTL` | no | `30d` (default) | life of each generated client secret; max `180d` |

Staging and prod refuse to start without all six (`northline.required-env.federation`); dev and local may leave any
provider off — its button then says "not available right now". The two secrets go through External Secrets
([secrets.md](secrets.md)); the ids go in the operator's `configEnv` (like the SMS settings). Start-up logs
`Federated sign-in (S-18): google=on apple=on`; a malformed Apple key stops start-up listing every problem.

**No Apple client secret to rotate by hand:** northline-auth signs it (ES256 JWT, `iss` = team id, `sub` = Services
ID, `aud` = `https://appleid.apple.com`) from the `.p8` key at the first token request, and makes a new one when
less than a quarter of its life is left. To replace the key itself: create a new key in the Apple console, set
`APPLE_KEY_ID` + `APPLE_PRIVATE_KEY`, roll out, then revoke the old key (Apple allows two keys per team at once).

## Redirect URIs per environment

`<public auth URL>/login/oauth2/code/<google|apple>` — the URL as the browser sees it (the ingress must send
`X-Forwarded-Proto/Host`, and be in `TRUSTED_PROXIES`):

| environment | Google | Apple |
|---|---|---|
| local | `http://localhost:9000/login/oauth2/code/google` | not possible (Apple needs https and a registered domain; use a tunnel such as `cloudflared` with its https host, or test Apple in dev) |
| dev | `https://auth.dev.northline.ca/login/oauth2/code/google` | `https://auth.dev.northline.ca/login/oauth2/code/apple` |
| staging | `https://auth.staging.northline.ca/login/oauth2/code/google` | `https://auth.staging.northline.ca/login/oauth2/code/apple` |
| prod | `https://auth.northline.ca/login/oauth2/code/google` | `https://auth.northline.ca/login/oauth2/code/apple` |

## Google Cloud console (once per environment)

1. Pick (or create) the environment's project, e.g. `northline-staging`. **APIs & Services → OAuth consent screen**:
   user type *External*; app name "Northline"; support email; logo; app domain `northline.ca`, privacy policy
   `https://studio.northline.ca/legal/privacy.html`, terms `…/legal/terms.html`; authorised domain `northline.ca`;
   scopes `openid`, `…/auth/userinfo.email`, `…/auth/userinfo.profile` (non-sensitive — no Google verification
   review needed); **Publish app** (Testing mode only lets listed test users in and expires refresh after 7 days).
2. **Credentials → Create credentials → OAuth client ID**, type *Web application*, name `northline-auth-<env>`.
   Authorised redirect URI: the table above (no JavaScript origins needed — the browser never talks to Google's APIs).
3. Copy the client id → `GOOGLE_CLIENT_ID`; the client secret → secrets manager `google-client-secret`. Rotating:
   **Add secret** in the same client, deploy the new one, then disable the old secret.

## Apple Developer (once for all environments, plus a Services ID per environment)

Needs the Apple Developer Program membership of Northline Marketplace Inc. (Account Holder or Admin).

1. **Certificates, Identifiers & Profiles → Identifiers → App IDs**: an App ID (e.g. `ca.northline.app`, the future
   mobile app's) with the **Sign in with Apple** capability enabled as a *primary App ID*.
2. **Identifiers → Services IDs → +**: `ca.northline.auth` (prod), `ca.northline.auth.staging`,
   `ca.northline.auth.dev`; description "Northline". Enable **Sign in with Apple → Configure**: primary App ID from
   step 1; **Domains and Subdomains** `auth.northline.ca` (resp. `auth.staging…`, `auth.dev…`); **Return URLs** from
   the table above. Save. The Services ID is `APPLE_CLIENT_ID`.
3. **Keys → +**: name "Northline Sign in with Apple", enable **Sign in with Apple**, configure it with the primary App
   ID, register, **download the `.p8` once** (Apple never shows it again). Its Key ID → `APPLE_KEY_ID`; the file's
   contents → secrets manager `apple-private-key`; the team id (top right, or Membership) → `APPLE_TEAM_ID`. One key
   can serve every environment, or use one per environment.
4. **Hide My Email / private relay:** people may share a `…@privaterelay.appleid.com` address. Apple forwards mail to
   it only from registered senders: **Services → Sign in with Apple for Email Communication → Configure**, add the
   sending domain (`northline.ca`, and `staging.northline.ca` if it sends mail) and the `EMAIL_FROM` address, with SPF
   passing for that domain ([email.md](email.md)). Until then those people get no email (SMS still works).
5. Apple sends the person's name **only on their first authorization** (as a form field northline-auth reads into
   "Create account"). Testing again with the same Apple ID: remove Northline in the Apple ID settings (Sign in with
   Apple → Stop using) to get the name again.

## Checking an environment

- `curl -sI https://auth.staging.northline.ca/oauth2/authorization/google` → `302` to `accounts.google.com` with
  `redirect_uri=https%3A%2F%2Fauth.staging.northline.ca%2Flogin%2Foauth2%2Fcode%2Fgoogle` (a `302` back to the
  Studio with `error=federation_unavailable` means the provider isn't configured).
- Same for `/oauth2/authorization/apple` → `appleid.apple.com/auth/authorize?response_mode=form_post…`.
- Sign in once with each in the Studio; the auth log shows `… account linked to user …` after the factor step, and
  `Apple client secret generated (key …, valid until …)` on the first Apple sign-in after each start.
- `redirect_uri_mismatch` (Google) / `invalid_request … redirect_uri` (Apple): the URI in the console differs from the
  one in the redirect above — usually the ingress not forwarding `X-Forwarded-Proto: https`.
- `invalid_client` from Apple: wrong team id / key id / Services ID combination, or the key was revoked.
