# Shopify, Square and Lightspeed catalogue sync (S-35)

Studio › Products › Bulk upload › **Or connect**: a seller connects the store they already run on Shopify, Square or
Lightspeed Retail (X-Series). The catalogue is imported into Northline as **drafts** (they go through vetting like any
listing), and from then on **price and stock follow the platform**: webhooks as they happen, and a full read every
hour when there are no webhooks. A product removed or archived on the platform **hides** its Northline listing — it is
never deleted. How it behaves and why: [DECISIONS.md § S-35](../DECISIONS.md). Code: `ca.northline.catalogue`
(`application/Commerce*`, `adapters/commerce/*`), tokens sealed with `ca.northline.shared.crypto` (S-32).

> **Status (2026-09-30):** written against each platform's public documentation and tested with WireMock stand-ins
> (`CommerceSourcesWireMockTest`). **Nothing has run against real Shopify, Square or Lightspeed** — no Partner,
> Developer or add-on account exists. The Lightspeed X-Series docs site was also unreachable from the build
> environment, so its field names follow the 2.0 reference as last known. Do the first real connection of each
> platform in dev, with a development store / sandbox, and walk through [Checking an environment](#checking-an-environment).

## How it works

```
Studio ── POST …/listings/integrations/{shopify|square|lightspeed}/connect {shop?} ──▶ api: state (256 bit, stored
          as SHA-256, single use, 10 min, bound to the owner and business) → the platform's consent URL
browser ──▶ {shop}.myshopify.com/admin/oauth/authorize · connect.squareup.com/oauth2/authorize ·
            secure.retail.lightspeed.app/connect   (consent)
browser ──▶ https://api.<zone>/api/v1/commerce/oauth/{platform}/callback?code&state[&shop&hmac…|&domain_prefix]
api: state → owner still allowed to manage the business → (Shopify: hmac + shop checked) → code exchanged →
     tokens sealed (KMS envelope key) → 303 https://studio.<zone>/b/<merchant>/listings/bulk?commerce=…&result=connected
after commit (outbox): webhooks registered (HTTPS api only) → full import
platform webhook ──▶ https://api.<zone>/api/v1/webhooks/commerce/{platform}  (public, HMAC-verified, deduplicated)
every 5 min: connections due for a full read — without webhooks every COMMERCE_POLL_INTERVAL (1 h),
             with webhooks every COMMERCE_RECONCILE_INTERVAL (1 day: missed deliveries, deletions)
hourly: expired OAuth requests and week-old webhook receipts purged
```

| | Shopify | Square | Lightspeed Retail (X-Series) |
|---|---|---|---|
| API | Admin GraphQL `2026-07` (`SHOPIFY_API_VERSION`) | Catalog + Inventory, `Square-Version: 2025-10-16` | 2.0 (1.0 for token and webhooks) |
| OAuth | app install, offline token (doesn't expire), callback signed with `hmac` | code flow; 30-day access token refreshed when < 7 days remain | code flow; short access token, **rotating** refresh token (re-sealed) |
| scopes | `read_products,read_inventory` | `ITEMS_READ INVENTORY_READ MERCHANT_PROFILE_READ` | none requested (add `northline.commerce.lightspeed.scopes` if the add-on needs them) |
| products | `products(query: "status:active")`, 50 a page, variants + up to 9 images | `catalog/search` ITEM + related IMAGE; archived skipped | `products?after=<version>`, families by `variant_parent_id`; inactive/deleted skipped |
| stock | variant `inventoryQuantity` (all locations) | `inventory/counts/batch-retrieve` IN_STOCK, summed over locations | `inventory?after=`, `inventory_level` summed over outlets |
| price | variant `price` | variation `price_money.amount` | `price_excluding_tax` |
| rate limits | cost-based: `THROTTLED` → wait `(requested − available) / restoreRate`; pace when the bucket runs low | 429 → `Retry-After` or 0.5 s, 1 s, 2 s … | 429 → `Retry-After` (ISO instant) |
| webhooks | registered per shop: `products/create`, `products/update`, `products/delete`, `inventory_levels/update`, `app/uninstalled` (+ compliance topics in the app settings) | **app-level** subscription in the Developer Console: `catalog.version.updated`, `inventory.count.updated`, `oauth.authorization.revoked` | registered per store: `product.update`, `inventory.update` |
| signature | `X-Shopify-Hmac-Sha256` = base64 HMAC-SHA256(body, app secret) | `x-square-hmacsha256-signature` = base64 HMAC-SHA256(notification URL + body, subscription signature key) | `X-Signature: signature=…, algorithm=HMAC-SHA256` over the raw body with the client secret |
| dedupe key | `X-Shopify-Event-Id` | `event_id` | SHA-256 of the body (no delivery id is sent) |
| disconnect | `DELETE /admin/api_permissions/current.json` (uninstalls the app) | `POST /oauth2/revoke` | **no revocation endpoint**: tokens destroyed; the merchant removes the add-on in Lightspeed (Setup › Add-ons) |

**What is imported:** title (≤ 80 characters), description (HTML reduced to text, ≤ 4000), vendor as brand, variants
(option values; "Size"/"Colour"/"Length" pick the variation theme), SKUs (the platform's, else one derived from the
variant id), a GTIN when the barcode has a valid check digit (the offer then attaches to the shared catalogue record,
as the editor does), price, stock and images (downloaded only from the platforms' image hosts, over HTTPS, ≤ 15 MB;
those under 1000 px are skipped). Category, fulfilment, compliance attestations and country of origin can't come from
a platform: the merchant adds them before submitting. A product the editor's rules refuse (e.g. a promo word in the
title) is listed in the Studio under "N products couldn't be imported" with the editor's message.

**Existing SKUs** (a single-variant product whose SKU the merchant already sells on Northline) are linked to that
listing and only its price and stock change. **Later changes:** price and stock always follow the platform; title,
description, images and variants follow it only while the listing is still a draft — once submitted, Northline's
vetted content stays. A listing the merchant deleted in Northline is not imported again.

**Stock is one-way.** The platform is the source of truth; Northline never writes stock back. Merchants must record
Northline sales in their POS (or accept that the next sync puts back the platform's number).

A refused refresh (`invalid_grant`) or a 401 turns the connection to **reconnect**: the Studio shows "Access expired or
was removed · reconnect to keep syncing" with **Reconnect**; nothing is read until then. Shopify's `app/uninstalled`
and Square's `oauth.authorization.revoked` disconnect it at once.

## Variables (api)

| variable | required | value |
|---|---|---|
| `COMMERCE_PROVIDER` | staging, prod (`local` refused there) | `local` (fakes with fixture catalogues) · `oauth` |
| `SHOPIFY_CLIENT_ID`, `SHOPIFY_CLIENT_SECRET` | no — empty: Shopify shows "Not available yet" | the app's Client ID / Client secret; the secret → secrets manager `shopify-client-secret` |
| `SHOPIFY_API_VERSION` | no (`2026-07`) | a supported Admin API version; move it forward every quarter-year (Shopify supports each for 12 months) |
| `SQUARE_CLIENT_ID`, `SQUARE_CLIENT_SECRET` | no — empty: Square shows "Not available yet" | Application ID / OAuth Application secret; secret → `square-client-secret` |
| `SQUARE_WEBHOOK_SIGNATURE_KEY` | no — empty: Square updates hourly only (webhooks refused) | the webhook subscription's signature key → `square-webhook-signature-key` |
| `SQUARE_BASE_URL` | no (`https://connect.squareup.com`) | `https://connect.squareupsandbox.com` for the sandbox (dev) |
| `LIGHTSPEED_CLIENT_ID`, `LIGHTSPEED_CLIENT_SECRET` | no — empty: Lightspeed shows "Not available yet" | the add-on's client id / secret; secret → `lightspeed-client-secret` |
| `API_PUBLIC_URL` | staging, prod (already) | builds the redirect URIs `<API_PUBLIC_URL>/api/v1/commerce/oauth/{shopify,square,lightspeed}/callback` and the webhook URLs `<API_PUBLIC_URL>/api/v1/webhooks/commerce/{…}`; with `http://` (a laptop) no webhook is registered and the hourly read does the work |
| `STUDIO_ORIGIN` | yes (already) | where the callback sends the browser back |
| `KMS_PROVIDER`, `KMS_ENCRYPTION_KEY_ID` | staging, prod (already, S-32) | the envelope key that seals the tokens ([calendar-sync.md](calendar-sync.md#the-envelope-key-kms_encryption_key_id)) |
| `COMMERCE_POLL_INTERVAL`, `COMMERCE_RECONCILE_INTERVAL`, `COMMERCE_WEBHOOK_RATE_LIMIT` | no | `PT1H`, `P1D`, `600` deliveries per minute and client address |

Secrets are listed in Terraform's `app_secrets` for AWS, Google Cloud and Azure (created empty in Secrets Manager /
Secret Manager; Azure Key Vault can't hold an empty secret, so the operator creates them) and in the chart's
`externalSecrets.secretNames`; once a value is set, map it in the environment's values
(`apps.api.secretEnv.SHOPIFY_CLIENT_SECRET: true` and `externalSecrets.optionalKeys`). The client ids are not secret:
put them in the operator's `configEnv`.

## Redirect and webhook URLs per environment

All on the **api** host: the platforms' app settings take one fixed redirect URL and one webhook URL per app (the
Square app and its redirect URL also serve the kitchens' menu import, S-36: the shared callback hands each state to
the module that started it — [pos-menu-import.md](pos-menu-import.md)), the callback needs no session (the single-use state identifies the owner and business), and the Gateway routes
`/api/v1/commerce/oauth` and `/api/v1/webhooks/commerce` there even with `apps.api.tokenClients: false`
([edge.md](edge.md)).

| environment | redirect URIs | webhook URLs |
|---|---|---|
| local | `http://localhost:8080/api/v1/commerce/oauth/{shopify,square,lightspeed}/callback` (fakes: the browser goes straight there) | none (no public HTTPS; hourly reads) |
| dev | `https://api.dev.northline.ca/api/v1/commerce/oauth/{platform}/callback` | `https://api.dev.northline.ca/api/v1/webhooks/commerce/{platform}` |
| staging | `https://api.staging.northline.ca/api/v1/commerce/oauth/{platform}/callback` | `https://api.staging.northline.ca/api/v1/webhooks/commerce/{platform}` |
| prod | `https://api.northline.ca/api/v1/commerce/oauth/{platform}/callback` | `https://api.northline.ca/api/v1/webhooks/commerce/{platform}` |

## App registration (once per platform and environment)

### Shopify

1. In the Shopify **Partner Dashboard** (or the Dev Dashboard), create an app per environment ("Northline dev",
   "Northline staging", "Northline") with **public distribution** (merchants of any shop install it).
2. **Configuration:** App URL `https://studio.<zone>/`; allowed redirection URL
   `https://api.<zone>/api/v1/commerce/oauth/shopify/callback`; Admin API access scopes `read_products,read_inventory`;
   webhook API version = `SHOPIFY_API_VERSION`.
3. **Compliance webhooks** (mandatory for public apps): customer data request, customer redact and shop redact, all
   to `https://api.<zone>/api/v1/webhooks/commerce/shopify`. Northline keeps no Shopify customer data; shop redact
   forgets the shop's product links.
4. Copy **Client ID** → `SHOPIFY_CLIENT_ID` (configEnv) and **Client secret** → secrets manager
   `shopify-client-secret`. The secret also verifies the callback `hmac` and every webhook.
5. dev: install on a **development store** to try it. Before prod: App Store listing and review (Shopify checks the
   compliance webhooks and the install flow), protected customer data not requested.

### Square

1. In the **Square Developer Console**, create an application per environment. dev uses the **sandbox**
   (`SQUARE_BASE_URL=https://connect.squareupsandbox.com`, sandbox credentials); staging and prod production.
2. **OAuth:** Redirect URL `https://api.<zone>/api/v1/commerce/oauth/square/callback`. Copy Application ID →
   `SQUARE_CLIENT_ID`, Application secret → `square-client-secret`.
3. **Webhooks → Subscriptions:** add one with URL `https://api.<zone>/api/v1/webhooks/commerce/square`, API version
   as above, events `catalog.version.updated`, `inventory.count.updated`, `oauth.authorization.revoked`. Copy its
   **Signature key** → `square-webhook-signature-key`. The subscription covers every seller who authorises the app.
4. If the listed URL and `API_PUBLIC_URL` differ in any way (trailing slash, host), signatures won't verify: the
   signed text is the notification URL + body.

### Lightspeed Retail (X-Series)

1. In the **Lightspeed X-Series developer portal**, register an add-on (application) per environment; the
   production add-on needs Lightspeed's approval before other retailers can install it.
2. Redirect URI `https://api.<zone>/api/v1/commerce/oauth/lightspeed/callback`. Copy the client id →
   `LIGHTSPEED_CLIENT_ID`, the client secret → `lightspeed-client-secret` (it also signs webhooks).
3. Webhooks are registered by Northline per store after connect (`product.update`, `inventory.update` →
   `https://api.<zone>/api/v1/webhooks/commerce/lightspeed`); nothing to set in the portal.
4. Disconnect can't revoke the grant: tell merchants to remove "Northline" under Setup › Add-ons in Lightspeed.

## Local

`COMMERCE_PROVIDER=local` (the default) fakes all three: **Connect** goes straight back to the callback, the
catalogue is `server/api/src/main/resources/commerce-fixtures/{shopify,square,lightspeed}.json` (Prairie Wrench Parts'
goods; Shopify's has "Clearance brake cleaner" to show a product that can't be imported), images are drawn
locally, and stock moves by the hour so "Sync now" / the hourly read visibly update it. To try the webhook path by
hand, post a body `{"account":"prairie-parts.myshopify.com","deliveryId":"d1","type":"removed","id":"gid://shopify/Product/7003"}`
with `X-Fake-Signature` = base64 HMAC-SHA256 of the body with `local-commerce-webhook-secret`.

## Operations

- **"Importing…" never ends / sync failed:** `select provider, sync_status, last_error, last_polled_at from
  catalogue.integrations where merchant_id = …`. A failed read is retried at the next due time; "Sync now" retries at once.
- **Reconnect:** `connection_state = 'reconnect'` — the merchant uninstalled or the refresh token was refused. Only the
  owner can reconnect (Studio).
- **Webhook 403s** in the api log (`invalid_webhook`): wrong secret / signature key in this environment, or (Square) a
  subscription URL that isn't exactly `API_PUBLIC_URL` + path.
- **Rate limits:** the adapters wait and retry (up to 5 times, each wait ≤ 30 s); a read that still fails is retried
  at the next due time.
- **Rotating an app secret:** Shopify and Lightspeed sign webhooks with the secret — update the secret, restart the
  api; deliveries signed with the old one in between are retried by the platform.

## Checking an environment

- [ ] App registrations per platform with this environment's redirect and webhook URLs; ids in configEnv, secrets in
      the secrets manager and mapped (`secretEnv`, `optionalKeys`)
- [ ] `COMMERCE_PROVIDER=oauth`, `API_PUBLIC_URL` HTTPS, `KMS_ENCRYPTION_KEY_ID` set (S-32)
- [ ] Studio › Bulk upload shows the three platforms without "Not available yet"
- [ ] Connect a development store / sandbox seller: drafts appear, "Price and stock follow … as they change"
- [ ] Change a price on the platform: the listing follows within a minute; delete a product: the listing is hidden
