# Partner integrations — API clients with `private_key_jwt` (S-30)

How a partner (an accounting sync, an inventory tool, an embed) gets tokens for the Northline api, and how operators
register, rotate, limit and revoke partners. Partners are OAuth clients `partner:<name>` that use `client_credentials`
and authenticate with a JWT signed by their own private key (`private_key_jwt`, RFC 7523). Northline never holds a
partner secret.

> **Status (2026-09-30):** implemented and tested in northline-auth and the api (`PartnerClientsApiTest`,
> `OAuthClientCatalogTest`, `PartnerListingAccessTest`). No real partner exists yet: nothing has run against a
> partner's own JWK Set or signing stack, and no environment declares a partner.

Other runbooks: [README § OAuth clients](README.md#oauth-clients-s-122) · [README § Rate limits](README.md#rate-limits-s-9)
· [edge](edge.md) · [deploy](deploy.md)

## What a partner can do

| | |
|---|---|
| token | JWT ES256 from `https://auth.<zone>/oauth2/token`, **15 minutes** (design 05), `token_type: Bearer`, no refresh token (ask for a new one) |
| scopes | `api.read` (read the business's data), `api.write` (change it) — each partner is registered with a subset; a token asks for a subset of that (none asked = all of the partner's) |
| businesses | only those it is registered for: the token carries them in `merchants`, and the api refuses every other business (`403 not_bound`) |
| endpoints | `https://api.<zone>/api/v1/merchants/{merchantId}/…` endpoints marked for partners only (`403 partner_not_allowed` elsewhere); nothing outside a business |

Endpoints open to partners today:

| endpoint | scope |
|---|---|
| `GET /api/v1/merchants/{merchantId}/listings?kind=service\|product&limit=` | `api.read` |
| `GET /api/v1/merchants/{merchantId}/listings/{listingId}` | `api.read` |

New endpoints are opened with `@PartnerAccess(PartnerAccess.READ|WRITE)` next to `@RequiresMerchant` (never on a
handler that takes a `CurrentMember` — a partner is no team member).

## For the partner: getting a token

1. **Keys.** Generate an EC P-256 key (ES256) or an RSA key of 2048 bits or more (RS256 or PS256), with a `kid`.
   Keep the private key in your KMS/HSM. Give Northline either **a JWK Set URL** (https, serving your public keys —
   preferred: you rotate on your own) or **the public key(s) as JWK**.
2. **The assertion** — a JWT you sign for each token request:

   ```
   header  {"alg":"ES256","kid":"<your key id>"}
   claims  {"iss":"partner:acme", "sub":"partner:acme",
            "aud":"https://auth.<zone>",            ← the issuer (or https://auth.<zone>/oauth2/token)
            "jti":"<random UUID, never reused>",
            "iat":<now>, "exp":<now + at most 5 min>}
   ```

   `iat` must be within 60 s of our clock (use NTP), the assertion may live at most 5 minutes, and each `jti` works
   once — a replayed assertion is refused.
3. **The request:**

   ```sh
   curl -s https://auth.<zone>/oauth2/token \
     -d grant_type=client_credentials \
     -d client_id=partner:acme \
     -d client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer \
     -d client_assertion=<the signed JWT> \
     -d scope=api.read
   # → {"access_token":"…","token_type":"Bearer","expires_in":899,"scope":"api.read"}
   ```

   `client_id` is required (Spring Authorization Server insists, although RFC 7523 makes it optional).
4. **Calling the api:** `Authorization: Bearer <access token>` on `https://api.<zone>/api/v1/merchants/<id>/…`. Reuse
   the token until it expires (cache it for ~14 minutes); don't ask for one per call.

| answer | meaning |
|---|---|
| `401 invalid_client` | bad signature, unknown key/`kid`, wrong `iss`/`sub`/`aud`, missing `jti`/`iat`/`exp`, expired or too long-lived assertion, a replayed `jti`, or the partner is revoked |
| `400 invalid_scope` | a scope the partner isn't registered for |
| `429 rate_limited` + `Retry-After` | over the token limit (below) — wait, and cache tokens |
| `400 temporarily_unavailable` | the replay store (Valkey) is down: an assertion can't be checked for replay, so none is accepted — retry later |
| api `403 not_bound` / `partner_not_allowed` | another business, or an endpoint/scope not open to this partner |

## For operators: the lifecycle

Partners are configuration, `northline.oauth.partners.<name>` (no secret involved), reconciled into the OAuth client
table like every other client — at auth start-up and by the `oauthClients` Job/command
([README § OAuth clients](README.md#oauth-clients-s-122)).

**Kubernetes:** the chart's `partners` value (per environment, e.g. in `values-prod.yaml` or the Argo CD
application's values) becomes the ConfigMap `northline-auth-partners`, read by auth and the oauth-clients Job
(`SPRING_CONFIG_ADDITIONAL_LOCATION`); changing it re-runs the Job and restarts auth.

```yaml
partners:
  acme:
    name: Acme Books                                              # shown in the audit log / client list
    jwk-set-url: https://acme.example/.well-known/jwks.json       # or, exclusively:
    # public-keys: ['{"kty":"EC","crv":"P-256","x":"…","y":"…","kid":"acme-2026-10"}']
    scopes: [api.read]                                            # api.read | api.write
    merchants: [01J9ZD3V00000000000000PWM1]                       # merchants.merchants ids it acts for
    access-token-ttl: 15m                                         # default; at most 1h
    revoked: false
```

Locally: the same block in `server/auth/src/main/resources/application-local.yml` or a file passed with
`SPRING_CONFIG_ADDITIONAL_LOCATION`, then `./gradlew :auth:oauthClients --args='sync'`.

Checked before anything is written (the Job fails listing every problem): name `[a-z0-9-]`, exactly one of
`jwk-set-url` / `public-keys`, the URL https (staging/prod; http on loopback in dev, anything locally), keys public
only, EC P-256 or RSA ≥ 2048, each with its own `kid`; scopes from `api.read`, `api.write`; at least one business id
(ULID); TTL ≤ 1 h.

| task | how |
|---|---|
| **register** | add the block, deploy (the Job syncs). The partner uses `client_id=partner:<name>` |
| **bind more businesses / change scopes** | edit `merchants` / `scopes`, deploy. New tokens carry the change; tokens already issued keep theirs until they expire (≤ 15 min) |
| **rotate a key (JWK Set URL)** | the partner publishes the new key next to the old one, signs with the new one, then removes the old one — nothing to do here (an unknown `kid` makes northline-auth fetch the set again) |
| **rotate a key (registered keys)** | add the new key to `public-keys` (both valid), deploy; the partner switches; remove the old key, deploy — from then on it is refused |
| **revoke** | set `revoked: true`, deploy: no new token (`invalid_client`); tokens already issued end within their 15 minutes. **Don't just delete the block**: a client removed from configuration is left as it is in the database (reported as `stored but not in configuration`); delete it by hand afterwards if needed (`delete from auth.oauth2_registered_client where client_id = 'partner:acme'`) |
| **audit** | every token issued: `developer.audit_log` `auth.partner_token_issued`, actor and target = `partner:<name>`, with scopes, businesses and expiry. `select at, after from developer.audit_log where actor_id = 'partner:acme' order by at desc` |

**Rate limits** (S-9 limiter, Valkey): action `partner-token` — **60 tokens per hour per partner** (then locked 15
min, doubling on repeat), 600 per hour per IP; `429 rate_limited` with `Retry-After`. A partner that caches its
15-minute tokens needs 4 an hour. Change with `northline.auth.rate-limits.limits.partner-token.<account|ip>.max`
(e.g. `NORTHLINE_AUTH_RATELIMITS_LIMITS_PARTNERTOKEN_ACCOUNT_MAX=120`).

**Replay store:** assertion ids are kept in the auth replay store (`REPLAY_STORE`, Valkey:
`nl:auth-replay:assertion-jti:<sha256>`, until the assertion expires + 60 s). Valkey down = partners can't get tokens
(fail closed).

**Security notes:** northline-auth fetches a partner's JWK Set over https with 5 s time-outs and caches it; the URL is
operator configuration, never taken from a request. A partner token has no person behind it (`sub = partner:<name>`,
no `acr`), reaches only endpoints marked for partners, and only for the businesses listed at issuance.
