# POS menu import for kitchens (S-36)

Kitchen › Menu › **Import from POS / CSV** › *From your POS*: a kitchen brings its menu in from Square, Clover or Toast
instead of retyping it. Categories become sections, items become dishes with their prices, and modifier lists become
modifier groups. Nothing is written until the kitchen has **reviewed the changes** and pressed Apply. Imported dishes
arrive as **drafts with allergens not declared**: POS data can't be trusted for allergens, so the kitchen confirms them
in the item editor (the builder shows "Confirm allergens") before a dish can go live, then adds a photo. Importing
again shows a **diff**: new dishes, dishes the POS changed, dishes gone from the POS (hidden, never deleted), and
dishes that can't be imported. How it behaves and why: [DECISIONS.md § S-36](../DECISIONS.md). Code:
`ca.northline.food` (`application/Pos*`, `adapters/pos/*`), the shared plumbing `ca.northline.shared.integration`.

> **Status (2026-09-30):** written against each POS's public documentation and tested with WireMock stand-ins
> (`PosSourcesWireMockTest`). **Nothing has run against real Square, Clover or Toast.** **Toast's API is
> partner-gated:** Northline must be accepted into the Toast partner program before any restaurant can be read, and
> the adapter follows Toast's published menus API v2 and authentication docs as last known (the docs site wasn't
> reachable from the build environment). Until then keep `TOAST_CLIENT_ID` empty: Toast shows "Not available yet".

## How it works

```
Studio ── POST …/pos/{square|clover}/connect {menuId} ──▶ api: single-use state (hashed, 10 min, bound to the owner)
browser ──▶ Square / Clover consent ──▶ https://api.<zone>/api/v1/commerce/oauth/{square|clover}/callback
api (shared callback): each module checks whether the state is its own — the catalogue sync (S-35) and the kitchen
     import share the Square app and its single redirect URL → tokens sealed → 303 /b/<m>/kitchen/menu?pos=…&result=…
Studio ── POST …/pos/toast/connect {restaurantId} ──▶ api: Toast partner sign-in, the restaurant must be readable
Studio ── POST …/menus/{menuId}/pos-imports {provider} ──▶ api reads the POS menu, stores a preview + diff (1 h)
Studio ── POST …/pos-imports/{id}/apply ──▶ api re-plans against the kitchen's data now and writes it (one transaction)
```

| | Square | Clover | Toast |
|---|---|---|---|
| access | OAuth, **the S-35 Square app** (`SQUARE_CLIENT_ID`), scopes `ITEMS_READ MERCHANT_PROFILE_READ` | OAuth v2 (expiring tokens), `merchant_id` on the callback | partner credentials (machine client); the restaurant turns on the Northline integration and gives its GUID |
| menu read | `GET /v2/catalog/list?types=ITEM,CATEGORY,MODIFIER_LIST` | `/v3/merchants/{mId}/categories`, `/items?expand=categories,modifierGroups&filter=hidden=false`, `/modifier_groups?expand=modifiers` | `GET /menus/v2/menus` (`Toast-Restaurant-External-ID`) |
| sections | categories (none → "Other") | categories by sort order (none → "Other") | menu groups, nested ones flattened; "Dinner · Mains" when there are several menus |
| price | cheapest variation; several variations → a required "Size" group priced as the difference | `price` (`priceType` VARIABLE → can't import) | `price` (null = size/open pricing → can't import) |
| modifiers | modifier lists: SINGLE = up to 1, MULTIPLE = any, an item's minimum makes it required | `minRequired` / `maxAllowed` | `minSelections` / `maxSelections`, option price = what it adds |
| tokens | 30 days, refreshed when < 7 days remain | refreshed a minute before expiry (rotating refresh token, re-sealed) | partner token kept in memory |
| disconnect | `/oauth2/revoke` | no revocation endpoint: tokens destroyed; the merchant uninstalls in the Clover App Market | nothing to revoke: the owner turns the integration off in Toast |

**Rules → the builder:** min = max → "exactly N" (required); min > 0 → "at least N" (required); else "up to max"
(optional). Counts are clamped to the options and to 20. A group with no options or an option over $100 can't be
imported (listed; the dishes keep their other groups). Names are cut to the builder's limits (80 dishes, 60 sections,
40 groups/options; descriptions 500). A dish without a price can't be imported. Allergens, dietary tags, photos,
prep time and availability are never read from a POS.

**Re-import:** a dish imported before is *changed* only when the POS changed it since the last import **and** it
differs from Northline — then only name, description, price, modifiers and section are written (the kitchen's
allergens, photo, status, availability and tags stay). A dish renamed in Northline but unchanged in the POS stays as
the kitchen named it. A dish deleted in Northline is not imported again. A dish gone from the POS goes back to draft
(hidden from customers, `food.item_availability` when it was visible).

## Variables (api)

| variable | required | value |
|---|---|---|
| `POS_PROVIDER` | staging, prod (`local` refused there) | `local` (fakes with a fixture menu) · `oauth` |
| `SQUARE_CLIENT_ID`, `SQUARE_CLIENT_SECRET`, `SQUARE_BASE_URL` | no — S-35's variables, the same Square app | [commerce-sync.md § Square](commerce-sync.md#square) |
| `CLOVER_CLIENT_ID`, `CLOVER_CLIENT_SECRET` | no — empty: Clover shows "Not available yet" | App ID / App Secret; secret → secrets manager `clover-client-secret` |
| `CLOVER_AUTH_URL`, `CLOVER_API_URL` | no (`https://www.clover.com`, `https://api.clover.com`) | sandbox: `https://sandbox.dev.clover.com`, `https://apisandbox.dev.clover.com` (dev) |
| `TOAST_CLIENT_ID`, `TOAST_CLIENT_SECRET` | no — empty: Toast shows "Not available yet" | partner credentials from Toast; secret → `toast-client-secret` |
| `TOAST_API_URL` | no (`https://ws-api.toasttab.com`) | sandbox: `https://ws-sandbox-api.eng.toasttab.com` |
| `API_PUBLIC_URL`, `STUDIO_ORIGIN` | already required | the redirect URI `<API_PUBLIC_URL>/api/v1/commerce/oauth/<pos>/callback`, the Studio return |
| `KMS_PROVIDER`, `KMS_ENCRYPTION_KEY_ID` | staging, prod (S-32) | seal the POS tokens |

Secrets are in Terraform's `app_secrets` (AWS, Google Cloud, Azure) and the chart's `externalSecrets.secretNames`;
map them once set (`apps.api.secretEnv.CLOVER_CLIENT_SECRET: true`, `externalSecrets.optionalKeys`). Client ids go
in the operator's `configEnv`.

## Set-up per POS

### Square

Nothing new: the S-35 Square app serves kitchens too (same redirect URL
`https://api.<zone>/api/v1/commerce/oauth/square/callback`). Kitchens are asked only for `ITEMS_READ
MERCHANT_PROFILE_READ`. A business that is both a seller and a kitchen connects Square twice (catalogue and menu),
each with its own grant.

### Clover

1. In the Clover **developer dashboard** (sandbox for dev, production for staging/prod — North America), create an app
   per environment with the **Inventory: Read** and **Merchant: Read** permissions.
2. **App settings → REST configuration:** Site URL `https://api.<zone>`, and the redirect / alternate launch path
   `https://api.<zone>/api/v1/commerce/oauth/clover/callback`. Use the expiring-token OAuth flow (v2).
3. Copy the **App ID** → `CLOVER_CLIENT_ID` (configEnv), **App Secret** → secrets manager `clover-client-secret`.
4. Production: submit the app for Clover's approval and list it in the App Market (merchants install it there).

### Toast (partner-gated)

1. Apply to the **Toast partner program** as an integration partner (menus read access). Toast issues a sandbox and,
   after review, production **machine-client** credentials. Without them, Toast stays "Not available yet".
2. `TOAST_CLIENT_ID` (configEnv), `TOAST_CLIENT_SECRET` → secrets manager `toast-client-secret`; `TOAST_API_URL` = the
   sandbox in dev.
3. A restaurant enables "Northline" in Toast Web › Integrations; the owner copies the restaurant GUID into
   Northline (Kitchen › Menu › Import › Toast). Northline checks it can read that restaurant before saving it.
4. Toast's API terms apply to what Northline stores: only the menu structure is kept (the preview), no guest data.

## Local

`POS_PROVIDER=local` (the default) fakes all three: Connect comes back connected at once (Toast accepts any GUID except
`00000000-0000-0000-0000-000000000000`, which answers "turn on the Northline integration"), and the menu is
`server/api/src/main/resources/pos-fixtures/menu.json` (Pho Dau Bo; "Soup of the day" has no price, to show a dish
that can't be imported). Edit a dish in Northline and import again to see the diff.

## Operations

- **"Reconnect your POS to import"** (409 `reconnect_required`): the grant was refused on refresh; the owner reconnects.
- **Preview expired** (409 `preview_expired`): previews are valid for an hour; import again.
- **Rate limits:** 429 / 5xx are retried with back-off honouring `Retry-After` (up to 5 times, each wait ≤ 30 s).
- `select provider, status, account_label, last_import_at from food.pos_connections where merchant_id = …`; previews in
  `food.pos_imports` (status preview / applied / discarded, the POS menu read and the diff).

## Checking an environment

- [ ] `POS_PROVIDER=oauth`; Clover app with this environment's redirect URI; Square app from S-35
- [ ] Kitchen › Menu › Import › From your POS lists Square and Clover without "Not available yet"
- [ ] Connect a sandbox merchant, review the import, apply: sections, dishes (drafts, "Confirm allergens"), groups
- [ ] Change a price in the POS, import again: exactly that dish shows as changed
