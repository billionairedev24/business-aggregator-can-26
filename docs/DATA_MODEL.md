# Northline — Data model (generated from 08 Data Model.dc.html)

One Postgres schema per module. ULIDs stored as text. Enums are text + CHECK for now (promote to native enums once stable). Cross-schema references are logical (no FK) and listed as such.

## Schema `identity` — Java (java)

### `identity.users`
One identity for customers, merchant staff, couriers and admins; roles are per context.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| phone | text | unique · E.164 · verified |
| email | citext | unique |
| display_name | text |  |
| locale | text | en-CA \| fr-CA |
| mfa_primary | enum | passkey \| totp \| sms |
| reliability_score | numeric(3,2) | customer trust, 0–5 |
| status | enum | active \| suspended \| erased |
| created_at | timestamptz |  |
| updated_at | timestamptz |  |

- Indexes / constraints: unique(phone), unique(email); RLS: self or admin
- Events: user.created · user.mfa_changed

### `identity.passkeys`
WebAuthn credentials per user/device.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| user_id | ulid | FK users |
| credential_id | bytea | unique |
| public_key | bytea |  |
| device_label | text |  |
| last_used_at | timestamptz |  |

- Indexes / constraints: index(user_id)
- Events: —

### `identity.sessions`
Refresh tokens and device list (Redis holds access tokens).

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| user_id | ulid | FK users |
| device | text |  |
| ip | inet |  |
| city | text |  |
| revoked_at | timestamptz |  |

- Indexes / constraints: index(user_id) · TTL job
- Events: session.revoked

### `identity.households`
Plus membership unit; up to 4 members.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| name | text |  |
| plus_plan | enum | none \| monthly \| annual |
| renews_at | timestamptz |  |
| stripe_subscription_id | text |  |

- Events: household.plus_changed

### `identity.household_members`
M:N users ↔ households.

| column | type | notes |
|---|---|---|
| household_id | ulid | FK |
| user_id | ulid | FK |
| role | enum | owner \| member |

- Indexes / constraints: PK(household_id,user_id)

### `identity.addresses`
Customer addresses resolved via Google Places; zone precomputed.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| user_id | ulid | FK users |
| place_id | text | Google |
| street | text |  |
| unit | text |  |
| city | text |  |
| province | char(2) |  |
| postal | text |  |
| geom | geography(Point) |  |
| zone_id | ulid | FK region.zones (by id) |
| access_note | text | shared 2 h around visit |
| is_default | bool |  |

- Indexes / constraints: GiST(geom) · index(user_id)
- Events: address.changed

## Schema `region` — Java (java)

### `region.regions`
A province or sub-market with its rollout stage.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| province | char(2) | AB, BC… |
| name_i18n | jsonb |  |
| stage | enum | off \| waitlist \| pilot \| live |
| tax_profile_id | ulid | FK |
| languages | text[] | en, fr |
| courier_model | enum | own \| contracted \| hybrid |

- Indexes / constraints: unique(province)
- Events: region.activated · region.stage_changed

### `region.tax_profiles`
GST/PST/HST/QST rates by effective date.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| gst | numeric |  |
| pst | numeric |  |
| hst | numeric |  |
| qst | numeric |  |
| effective_from | date |  |


### `region.zones`
Delivery zones (PostGIS polygons) with pooled-run pricing.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| region_id | ulid | FK |
| name | text |  |
| polygon | geography(Polygon) |  |
| runs_per_day | int |  |
| fee_std_cents | bigint |  |
| fee_plus_cents | bigint |  |
| min_basket_cents | bigint |  |

- Indexes / constraints: GiST(polygon)
- Events: zone.pricing_changed
- Search projection: listings.zone_ids (geo filter)

### `region.feature_flags`
Capabilities scoped by region/city (mirrors Unleash).

| column | type | notes |
|---|---|---|
| key | text | PK part |
| region_id | ulid | PK part |
| enabled | bool |  |
| variant | jsonb |  |

- Indexes / constraints: PK(key,region_id)
- Events: flag.changed

## Schema `merchants` — Java (java)

### `merchants.merchants`
Provider, seller or kitchen; one row per business.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| type | enum | provider \| seller \| kitchen \| both |
| display_name | text | not null · 2–80 chars |
| legal_name | text | not null |
| structure | enum | sole \| partnership \| corp_ab \| corp_fed \| corp_ex \| coop \| nonprofit |
| legal_details | jsonb | per-structure fields · JSON-schema validated by structure |
| business_number | text | CRA BN · 9 digits |
| gst_number | text | check ^\d{9}RT\d{4}$ · required unless sole/partnership |
| registry_ref | text | corporate access # / registration # |
| registry_jurisdiction | text | AB \| CA \| BC \| … |
| tier | enum | registered \| trusted \| master |
| take_rate_bps | int | 1500 → 900 |
| quality_score | int |  |
| stripe_account_id | text | acct_… |
| status | enum | applicant \| pending \| active \| paused \| suspended |
| region_id | ulid |  |
| languages | text[] |  |
| created_at | timestamptz |  |
| updated_at | timestamptz |  |

- Indexes / constraints: index(type,status) · index(tier) · unique(business_number) where not null
- Events: merchant.approved · merchant.tier_changed · merchant.suspended
- Search projection: merchants index · denormalised onto listings

### `merchants.merchant_principals`
Owners, partners, directors, board members — whoever the structure requires (FINTRAC ≥ 25 % beneficial owners).

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| merchant_id | ulid | FK |
| legal_name | text | not null |
| role | enum | owner \| partner \| partner_signing \| director \| officer \| shareholder \| chair \| president \| treasurer \| secretary |
| ownership_pct | numeric(5,2) | check 0–100 · sum ≤ 100 per merchant |
| kyc_verification_id | ulid | FK verifications |
| user_id | ulid | FK identity.users · nullable |

- Indexes / constraints: index(merchant_id) · check(role in allowed set for merchants.structure)
- Events: principal.added · principal.kyc_passed

### `merchants.merchant_categories`
Services / departments a merchant is approved to list in; limit per type enforced here, not just in UI.

| column | type | notes |
|---|---|---|
| merchant_id | ulid | FK |
| category_id | ulid | FK catalogue.categories |
| status | enum | requested \| approved \| rejected · regulated categories start requested |
| suggested_name | text | free text when no category matched |
| registry_verification_id | ulid | FK verifications · nullable |

- Indexes / constraints: PK(merchant_id,category_id) · trigger: count ≤ limit(type) → provider 10 · seller 5 · both 10 · kitchen 3
- Events: merchant.categories_changed → re-run registry checks
- Search projection: facets

### `merchants.merchant_members`
Staff with roles (owner, technician, bookkeeper, cook).

| column | type | notes |
|---|---|---|
| merchant_id | ulid | FK |
| user_id | ulid | FK identity.users |
| role | enum |  |
| bookable | bool |  |
| mfa_ok | bool |  |

- Indexes / constraints: PK(merchant_id,user_id)

### `merchants.verifications`
Every check: KYC, registry, permit, insurance, inspection, attestation.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| merchant_id | ulid | FK |
| check_type | enum | kyc \| registry \| licence \| ahs_permit \| food_cert \| inspection \| insurance \| wcb \| attestation \| bank \| mfa \| site_visit |
| registry | text | AMVIC, RECA, AHS… |
| reference | text |  |
| status | enum | todo \| submitted \| verified \| expired \| rejected |
| document_media_id | ulid |  |
| verified_by | ulid | agent |
| expires_at | timestamptz |  |
| rechecked_at | timestamptz |  |

- Indexes / constraints: index(merchant_id,status) · index(expires_at) for 30-day reminders
- Events: verification.passed · verification.expired (pauses instant book / ordering)

### `merchants.storefronts`
Business page / store / menu page: brand, ordered sections, slug, custom domain.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| merchant_id | ulid | FK unique |
| slug | text | unique · ^[a-z0-9-]{3,40}$ |
| page_kind | enum | business_page \| store \| menu_page · derived from merchants.type |
| brand_color | text | hex · must pass 4.5:1 with white |
| logo_media_id | ulid |  |
| tagline_i18n | jsonb | ≤ 80 chars |
| cta_label | enum | book_visit \| request_quote \| order_now \| reserve |
| announcement_i18n | jsonb |  |
| custom_domain | text | CNAME → pages.northline.ca · verified_at |
| published_at | timestamptz |  |

- Indexes / constraints: unique(slug) · unique(custom_domain)
- Events: storefront.published

### `merchants.storefront_sections`
Ordered, toggleable sections of a page. Content is derived from other tables, never duplicated here.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| storefront_id | ulid | FK |
| kind | enum | hero \| cta \| about \| services \| reviews \| area \| gallery \| faq \| featured \| catalogue \| delivery \| policies \| menu \| hours \| fulfil \| permit |
| position | int | 0-based · unique per storefront |
| enabled | bool | hero and cta are always true (check) |
| settings | jsonb | kind-specific · e.g. featured offer_ids, faq pairs, gallery media_ids |
| created_at | timestamptz |  |
| updated_at | timestamptz |  |

- Indexes / constraints: unique(storefront_id,position) · check(kind allowed for page_kind)
- Events: storefront.sections_changed → CDN purge

### `merchants.service_areas`
Zones a merchant serves.

| column | type | notes |
|---|---|---|
| merchant_id | ulid |  |
| zone_id | ulid |  |

- Indexes / constraints: PK(merchant_id,zone_id)
- Search projection: listings.zone_ids

## Schema `catalogue` — Java (java)

### `catalogue.categories`
Two roots (services, shop) + food; grouped taxonomy (~120 services) with regulator and tax code per category.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| parent_id | ulid | self · group → leaf |
| root | enum | service \| shop \| food |
| name_i18n | jsonb |  |
| booking_type | enum | visit \| home \| event \| appointment \| consult \| null |
| regulated_registry | text | AMVIC · Safety Codes · RECA · ProServe · RMT · null |
| requires_vs_check | bool | vulnerable-sector |
| tax_code | text |  |
| required_attributes | jsonb |  |
| search_terms | text[] | synonyms for type-ahead |

- Indexes / constraints: index(parent_id) · gin(search_terms)
- Events: category.changed
- Search projection: facets

### `catalogue.catalog_products`
Shared record for standard goods (GTIN-matched, Amazon-ASIN style).

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| gtin | text | unique |
| brand | text |  |
| title_i18n | jsonb |  |
| category_id | ulid | FK |
| attributes | jsonb |  |
| image_set | ulid[] | media |
| brand_owner_merchant_id | ulid | can lock content |
| locked | bool |  |

- Indexes / constraints: unique(gtin) · GIN(attributes)
- Events: product.created
- Search projection: listings (title, attrs, images)

### `catalogue.offers`
A merchant's sellable offer on a product (own or shared).

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| product_id | ulid | FK |
| merchant_id | ulid |  |
| sku | text |  |
| price_cents | bigint |  |
| compare_at_cents | bigint |  |
| cost_cents | bigint | private |
| stock | int |  |
| low_stock_at | int |  |
| condition | enum |  |
| fulfilment | text[] | pooled \| install \| pickup |
| vetting | enum | draft \| pending \| approved \| rejected |
| status | enum | live \| hidden |

- Indexes / constraints: unique(merchant_id,sku) · index(product_id)
- Events: listing.created · listing.updated · listing.stock_changed
- Search projection: listings (price, stock, merchant, zones)

### `catalogue.variants`
Size/colour variants under an offer.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| offer_id | ulid | FK |
| sku | text |  |
| gtin | text |  |
| attrs | jsonb | {size, colour} |
| price_cents | bigint |  |
| stock | int |  |
| image_set | ulid[] |  |

- Indexes / constraints: unique(offer_id,sku)
- Events: listing.updated
- Search projection: listings.variants

### `catalogue.services`
Bookable services with pricing mode, duration, buffer.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| merchant_id | ulid |  |
| category_id | ulid | FK |
| name_i18n | jsonb |  |
| desc_i18n | jsonb |  |
| pricing_mode | enum | fixed \| quote \| hourly |
| price_cents | bigint |  |
| duration_min | int |  |
| buffer_min | int |  |
| instant_book | bool |  |
| vetting | enum |  |
| status | enum |  |

- Indexes / constraints: index(merchant_id) · index(category_id)
- Events: listing.created · listing.updated
- Search projection: listings (service docs)

### `catalogue.media`
Images/documents with perceptual hash for duplicate detection.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| owner_type | text |  |
| owner_id | ulid |  |
| url | text | S3 key |
| phash | bytea | dupe index |
| exif_ok | bool |  |
| kind | enum | main \| gallery \| proof \| document |

- Indexes / constraints: index(phash) · index(owner_type,owner_id)
- Events: media.flagged_duplicate

## Schema `food` — Java (java)

### `food.menus`
A kitchen's menus with schedules (dinner, lunch, catering).

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| merchant_id | ulid |  |
| name_i18n | jsonb |  |
| schedule | jsonb | weekday windows |
| status | enum | draft \| live \| hidden |

- Indexes / constraints: index(merchant_id)
- Events: menu.published

### `food.menu_sections`
Ordered sections in a menu.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| menu_id | ulid | FK |
| name_i18n | jsonb |  |
| sort | int |  |


### `food.menu_items`
Dishes with allergens, dietary tags, prep, limits, availability.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| section_id | ulid | FK |
| merchant_id | ulid |  |
| name_i18n | jsonb |  |
| desc_i18n | jsonb |  |
| price_cents | bigint |  |
| allergens | text[] | Health Canada 11 |
| dietary | text[] |  |
| prep_add_min | int |  |
| daily_limit | int |  |
| sold_today | int |  |
| available | bool |  |
| vetting | enum |  |

- Indexes / constraints: index(merchant_id,available)
- Events: food.item_availability · listing.created
- Search projection: listings (food docs: allergens, dietary, eta)

### `food.modifier_groups`
Reusable option groups (size, spice, extras) with rules.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| merchant_id | ulid |  |
| name_i18n | jsonb |  |
| min_select | int |  |
| max_select | int |  |
| required | bool |  |


### `food.modifier_options`
Options with price deltas.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| group_id | ulid | FK |
| name_i18n | jsonb |  |
| price_delta_cents | bigint |  |
| is_default | bool |  |
| sold_out | bool |  |


### `food.item_modifiers`
M:N items ↔ groups.

| column | type | notes |
|---|---|---|
| item_id | ulid |  |
| group_id | ulid |  |
| sort | int |  |

- Indexes / constraints: PK(item_id,group_id)

### `food.combos`
Bundles with slot rules and a price or discount.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| merchant_id | ulid |  |
| name_i18n | jsonb |  |
| rules | jsonb | slots: any 2 mains… |
| price_cents | bigint |  |
| discount_bps | int |  |
| schedule | jsonb |  |
| status | enum |  |

- Events: listing.created
- Search projection: listings

### `food.kitchen_settings`
Prep time, throttles, fulfilment modes, pause state.

| column | type | notes |
|---|---|---|
| merchant_id | ulid | PK |
| default_prep_min | int |  |
| max_orders_per_15 | int |  |
| paused_until | timestamptz |  |
| fulfilment | text[] |  |
| radius_km | numeric |  |
| group_orders | bool |  |

- Events: kitchen.paused
- Search projection: listings.open_now / eta

## Schema `availability` — Java (java)

### `availability.availability_rules`
Weekly hours per bookable member; multiple ranges per day.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| merchant_id | ulid |  |
| member_user_id | ulid |  |
| weekday | smallint |  |
| ranges | jsonb | [[start,end],…] |
| effective_from | date |  |

- Indexes / constraints: index(merchant_id,member_user_id)
- Events: availability.changed
- Search projection: listings.next_slot

### `availability.booking_rules`
Interval, buffer, notice, horizon, max/day, acceptance mode.

| column | type | notes |
|---|---|---|
| merchant_id | ulid | PK |
| interval_min | int |  |
| buffer_min | int |  |
| min_notice_min | int |  |
| horizon_days | int |  |
| max_jobs_per_day | int |  |
| accept_mode | enum | instant \| approve \| request |
| reschedule_free_min | int |  |
| late_cancel_fee_cents | bigint |  |

- Events: availability.changed

### `availability.time_off`
Closures and special hours per member or whole team.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| merchant_id | ulid |  |
| member_user_id | ulid | null = all |
| starts_on | date |  |
| ends_on | date |  |
| kind | enum | closed \| special |
| special_ranges | jsonb |  |
| reason | text | private |

- Indexes / constraints: index(merchant_id,starts_on)
- Events: availability.changed

### `availability.calendar_links`
Google/Outlook two-way sync tokens and busy blocks.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| member_user_id | ulid |  |
| provider | enum |  |
| token_ref | text | KMS |
| last_sync_at | timestamptz |  |


## Schema `booking` — Java (java)

### `booking.bookings`
A service appointment of any type; the central aggregate for escrow release.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| customer_id | ulid |  |
| merchant_id | ulid |  |
| member_user_id | ulid |  |
| service_id | ulid |  |
| type | enum | visit \| home \| event \| appointment \| consult |
| state | enum | requested \| confirmed \| en_route \| on_site \| completed \| signed_off \| disputed \| cancelled |
| starts_at | timestamptz |  |
| ends_at | timestamptz |  |
| address_id | ulid | null for appointment/video |
| details | jsonb | vehicle / home / event / goal |
| quote_id | ulid |  |
| escrow_id | ulid |  |
| price_cents | bigint |  |
| deposit_cents | bigint | events 25% |

- Indexes / constraints: index(merchant_id,starts_at) · index(customer_id) · exclusion constraint on member/time
- Events: booking.requested · booking.confirmed · booking.completed · booking.signed_off · booking.cancelled
- Search projection: merchants.stats

### `booking.booking_events`
Append-only transitions with GPS and photo proof.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| booking_id | ulid | FK |
| type | enum |  |
| at | timestamptz |  |
| actor_id | ulid |  |
| geom | geography(Point) |  |
| media_id | ulid | completion photos |
| note | text |  |

- Indexes / constraints: index(booking_id,at)
- Events: —

### `booking.quote_requests`
Customer's description sent to up to 3 merchants.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| customer_id | ulid |  |
| category_id | ulid |  |
| details | jsonb |  |
| media | ulid[] |  |
| merchant_ids | ulid[] |  |
| expires_at | timestamptz |  |
| respond_by | timestamptz | 2 h SLA for response score |

- Events: quote.requested

### `booking.quotes`
A merchant's itemized reply. Honoured as written (Alberta CPA ±10 % rule); any change needs an approval row.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| request_id | ulid | FK |
| merchant_id | ulid |  |
| ref | text | QT-#### shown to both sides |
| version | int | revisions create a new row, prior marked superseded |
| scope | text | not null · what is included |
| exclusions | text | what could change the price |
| proposed_at | timestamptz |  |
| duration_min | int |  |
| warranty | enum | none \| labour_90d \| parts_labour_12m \| manufacturer |
| deposit_kind | enum | none \| parts_upfront \| pct |
| deposit_bps | int |  |
| subtotal_cents | bigint | = sum(lines) − discounts · trigger-checked |
| tax_cents | bigint | from region tax profile |
| total_cents | bigint |  |
| attachments | ulid[] | media |
| valid_hours | int | 24 \| 72 \| 168 \| 336 |
| valid_until | timestamptz |  |
| state | enum | draft \| sent \| viewed \| accepted \| declined \| expired \| superseded |
| viewed_at | timestamptz |  |

- Indexes / constraints: index(request_id) · index(merchant_id,state)
- Events: quote.sent · quote.viewed · quote.accepted (→ booking + escrow hold) · quote.expired

### `booking.quote_lines`
Every line the customer sees. At least one line per quote; each needs a description and, unless discount, a positive amount.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| quote_id | ulid | FK |
| position | int |  |
| kind | enum | labour \| part \| fee \| travel \| discount |
| description | text | not null · 1–160 chars |
| note | text | e.g. remanufactured, 12-mo warranty |
| qty | numeric(8,2) | > 0 |
| unit_cents | bigint | ≥ 0 · > 0 unless discount |
| amount_cents | bigint | = qty × unit · negative for discount |
| taxable | bool | default true |

- Indexes / constraints: unique(quote_id,position) · check(kind=discount or amount_cents>0)

### `booking.approvals`
Extra parts / scope changes approved in-app mid-job.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| booking_id | ulid |  |
| amount_cents | bigint |  |
| description | text |  |
| state | enum |  |
| decided_at | timestamptz |  |

- Events: booking.scope_changed

## Schema `orders` — Java (java)

### `orders.carts`
Persisted cart per customer (guest carts keyed by device).

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| customer_id | ulid | null = guest |
| device_key | text |  |
| lines | jsonb |  |
| expires_at | timestamptz |  |

- Indexes / constraints: index(customer_id)

### `orders.orders`
Goods (pooled) or food (direct) orders; multi-merchant.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| customer_id | ulid |  |
| type | enum | goods \| food |
| state | enum | placed \| accepted \| packing \| ready \| picked_up \| delivered \| confirmed \| refunded \| cancelled |
| address_id | ulid |  |
| window_id | ulid | pooled |
| scheduled_for | timestamptz | food |
| substitution_policy | enum |  |
| subtotal_cents | bigint |  |
| delivery_fee_cents | bigint |  |
| service_fee_cents | bigint |  |
| tax_cents | bigint |  |
| tip_cents | bigint |  |
| payment_intent_id | ulid |  |
| group_order_id | ulid |  |

- Indexes / constraints: index(customer_id) · index(state,window_id)
- Events: order.placed · order.packed · order.delivered · order.confirmed
- Search projection: merchants.stats

### `orders.order_lines`
Lines per merchant; food lines carry chosen modifiers.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| order_id | ulid | FK |
| merchant_id | ulid |  |
| offer_id | ulid |  |
| variant_id | ulid |  |
| menu_item_id | ulid |  |
| qty | int |  |
| unit_cents | bigint |  |
| modifiers | jsonb |  |
| substituted_with | ulid |  |
| state | enum | pending \| packed \| short \| refunded |

- Indexes / constraints: index(order_id) · index(merchant_id,state)
- Events: order.line_refunded

### `orders.delivery_windows`
Pooled runs on offer per zone with cut-off.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| zone_id | ulid |  |
| starts_at | timestamptz |  |
| ends_at | timestamptz |  |
| cutoff_at | timestamptz |  |
| capacity | int |  |
| run_id | ulid | fulfilment |

- Indexes / constraints: index(zone_id,starts_at)
- Events: window.closing
- Search projection: listings.on_tonights_run

### `orders.group_orders`
Shared food orders with per-person payment.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| host_user_id | ulid |  |
| merchant_id | ulid |  |
| link_code | text | unique |
| member_user_ids | ulid[] |  |
| locks_at | timestamptz |  |

- Indexes / constraints: unique(link_code)

## Schema `fulfilment` — Go (go)

### `fulfilment.runs`
A courier's pooled or direct run.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| zone_id | ulid |  |
| window_id | ulid |  |
| courier_id | ulid |  |
| kind | enum | pooled \| direct |
| state | enum | planned \| loading \| en_route \| done |
| route | jsonb | ordered stops + ETAs |

- Indexes / constraints: index(courier_id,state)
- Events: delivery.en_route

### `fulfilment.stops`
Pickups and drop-offs with proof.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| run_id | ulid | FK |
| order_id | ulid |  |
| kind | enum | pickup \| dropoff |
| seq | int |  |
| eta | timestamptz |  |
| arrived_at | timestamptz |  |
| proof_media_id | ulid |  |
| scan_ok | bool | sealed bag |

- Indexes / constraints: index(run_id,seq)
- Events: delivery.delivered

### `fulfilment.couriers`
Own-fleet and contracted couriers.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| user_id | ulid |  |
| vehicle | enum | bike \| ebike \| car \| van |
| status | enum | offline \| available \| on_run |
| rating | numeric(3,2) |  |

- Events: courier.offline

### `fulfilment.positions`
Live GPS — not in Postgres; 24 h retention, fanned out by the tracking gateway.

| column | type | notes |
|---|---|---|
| courier_id | ulid |  |
| lat/lng | float |  |
| at | ms |  |
| speed | float |  |

- Indexes / constraints: Redis Streams · TTL 24 h
- Events: position.updated (WebSocket)

## Schema `payments` — Java (java)

### `payments.payment_intents`
Mirror of Stripe PaymentIntents (manual capture for escrow).

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| stripe_pi | text | unique |
| customer_id | ulid |  |
| amount_cents | bigint |  |
| currency | char(3) | CAD |
| capture_method | enum | manual \| automatic |
| state | enum | requires_action \| authorized \| captured \| refunded \| failed |
| payment_method_ref | text | pm_… token |
| three_ds | bool |  |

- Indexes / constraints: unique(stripe_pi)
- Events: payment.held · payment.captured

### `payments.escrows`
The hold: what is owed to whom, when it releases.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| payment_intent_id | ulid | FK |
| ref_type | enum | booking \| order_line |
| ref_id | ulid |  |
| merchant_id | ulid |  |
| amount_cents | bigint |  |
| release_at | timestamptz | auto 48 h / 24 h |
| released_at | timestamptz |  |
| state | enum | held \| released \| refunded \| disputed |

- Indexes / constraints: index(release_at) for the auto-release job · index(merchant_id,state)
- Events: payment.released

### `payments.transfers`
Stripe transfers to connected accounts, net of take rate.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| escrow_id | ulid | FK |
| stripe_transfer | text |  |
| gross_cents | bigint |  |
| fee_cents | bigint |  |
| net_cents | bigint |  |
| at | timestamptz |  |


### `payments.payouts`
Scheduled or instant payouts per merchant.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| merchant_id | ulid |  |
| stripe_payout | text |  |
| amount_cents | bigint |  |
| kind | enum | scheduled \| instant |
| fee_cents | bigint |  |
| state | enum |  |
| arrives_at | timestamptz |  |

- Indexes / constraints: index(merchant_id,at)
- Events: payout.sent

### `payments.payout_settings`
Schedule, reserve, bank change hold.

| column | type | notes |
|---|---|---|
| merchant_id | ulid | PK |
| schedule | enum | daily \| weekly \| monthly \| manual |
| weekday | smallint |  |
| reserve_cents | bigint |  |
| bank_changed_at | timestamptz | 24 h hold |

- Events: payout_settings.changed

### `payments.ledger_entries`
Double-entry ledger reconciled daily against Stripe.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| account | text | escrow \| revenue \| merchant:<id> \| stripe_fees \| tax_payable |
| debit_cents | bigint |  |
| credit_cents | bigint |  |
| ref_type | text |  |
| ref_id | ulid |  |
| at | timestamptz |  |

- Indexes / constraints: index(account,at) · append-only

### `payments.refunds`
Auto (< $25) or agent-decided refunds.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| payment_intent_id | ulid |  |
| order_line_id | ulid |  |
| booking_id | ulid |  |
| amount_cents | bigint |  |
| reason | enum |  |
| charged_to | enum | merchant \| platform |
| state | enum | requested \| seller_review \| agent_review \| approved \| denied \| paid |

- Indexes / constraints: index(state)
- Events: refund.requested · refund.decided

### `payments.disputes`
Cases with evidence from both sides and an agent decision.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| ref_type | enum |  |
| ref_id | ulid |  |
| opened_by | ulid |  |
| evidence | jsonb |  |
| state | enum | open \| seller_replied \| agent \| decided \| appealed |
| decision | enum | full_refund \| partial \| release \| goodwill |
| decided_by | ulid |  |
| note_i18n | jsonb |  |

- Indexes / constraints: index(state)
- Events: dispute.opened · dispute.decided

## Schema `trust · loyalty` — Java (java)

### `trust · loyalty.reviews`
Two-way reviews, only on paid jobs/orders.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| ref_type | enum |  |
| ref_id | ulid |  |
| author_id | ulid |  |
| target_type | enum | merchant \| customer \| courier |
| target_id | ulid |  |
| rating | smallint |  |
| tags | text[] |  |
| text | text |  |
| reply | text |  |
| lang | text |  |

- Indexes / constraints: unique(ref_id,author_id) · index(target_type,target_id)
- Events: review.created
- Search projection: merchants.rating · listings.merchant_rating

### `trust · loyalty.quality_scores`
Nightly score components per merchant (from ClickHouse).

| column | type | notes |
|---|---|---|
| merchant_id | ulid | PK part |
| date | date | PK part |
| score | int |  |
| components | jsonb | on_time, photos, response, rebook, disputes |

- Indexes / constraints: PK(merchant_id,date)
- Events: trust.score_changed
- Search projection: listings.trust_boost

### `trust · loyalty.tier_history`
Promotions/demotions with reasons.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| merchant_id | ulid |  |
| tier | enum |  |
| from_at | timestamptz |  |
| to_at | timestamptz |  |
| reason | text |  |

- Events: merchant.tier_changed

### `trust · loyalty.flags`
Trust & safety flags with actions taken.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| target_type | enum |  |
| target_id | ulid |  |
| rule | text | off_platform_payment \| floor_breach \| no_show… |
| evidence | jsonb |  |
| state | enum |  |
| action | text |  |
| actor_id | ulid |  |

- Indexes / constraints: index(state)
- Events: trust.flagged

### `trust · loyalty.points_ledger`
Points earned, redeemed, expired; provider-funded multipliers.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| user_id | ulid |  |
| delta | int |  |
| ref_type | text |  |
| ref_id | ulid |  |
| funded_by_merchant_id | ulid |  |
| expires_at | timestamptz |  |

- Indexes / constraints: index(user_id)
- Events: points.credited

### `trust · loyalty.merchant_rewards`
Merchant-funded multipliers and budgets.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| merchant_id | ulid |  |
| scope | jsonb | listing ids or all |
| multiplier | numeric |  |
| starts_at | timestamptz |  |
| ends_at | timestamptz |  |
| budget_cents | bigint |  |
| spent_cents | bigint |  |

- Search projection: listings.reward_multiplier

## Schema `messaging · support` — Java (java)

### `messaging · support.threads`
Conversation per booking/order/quote/ticket; retained for disputes.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| ref_type | enum |  |
| ref_id | ulid |  |
| participant_ids | ulid[] |  |
| last_message_at | timestamptz |  |

- Indexes / constraints: index(ref_type,ref_id)

### `messaging · support.messages`
Messages with attachments; phone numbers masked.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| thread_id | ulid | FK |
| sender_id | ulid |  |
| body | text |  |
| attachments | ulid[] |  |
| template_key | text |  |
| at | timestamptz |  |
| flagged | bool | off-platform payment detector |

- Indexes / constraints: index(thread_id,at)
- Events: message.sent · trust.flagged

### `messaging · support.notifications`
Every push/SMS/email with locale and delivery result.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| user_id | ulid |  |
| channel | enum |  |
| template_key | text |  |
| locale | text |  |
| payload | jsonb |  |
| sent_at | timestamptz |  |
| delivered | bool |  |
| read_at | timestamptz |  |

- Indexes / constraints: index(user_id,sent_at)

### `messaging · support.notification_prefs`
Per-user channel matrix and quiet hours.

| column | type | notes |
|---|---|---|
| user_id | ulid | PK |
| matrix | jsonb |  |
| quiet_from | time |  |
| quiet_to | time |  |


### `messaging · support.tickets`
Helpdesk cases from any party with SLA.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| requester_type | enum | customer \| merchant \| courier |
| requester_id | ulid |  |
| topic | enum |  |
| priority | enum |  |
| state | enum | new \| in_progress \| waiting \| resolved |
| agent_id | ulid |  |
| sla_due_at | timestamptz |  |
| ref_type | text |  |
| ref_id | ulid |  |
| lang | text |  |
| csat | smallint |  |

- Indexes / constraints: index(state,sla_due_at) · index(agent_id)
- Events: ticket.opened · ticket.resolved

### `messaging · support.macros`
Canned replies per locale.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| key | text |  |
| body_i18n | jsonb |  |
| topic | enum |  |


## Schema `i18n` — Java (java)

### `i18n.translations`
UI strings: key → locale → text (ICU MessageFormat).

| column | type | notes |
|---|---|---|
| key | text | PK part |
| locale | text | PK part |
| text | text |  |
| source | enum | human \| mt |
| approved_by | ulid |  |
| updated_at | timestamptz |  |

- Indexes / constraints: PK(key,locale) · published as CDN bundles
- Events: i18n.bundle_published
- Search projection: per-language analyzers

### `i18n.content_translations`
Seller-authored text in other locales (MT draft → approved).

| column | type | notes |
|---|---|---|
| owner_type | text | PK part |
| owner_id | ulid | PK part |
| field | text | PK part |
| locale | text | PK part |
| text | text |  |
| source | enum |  |
| approved | bool |  |

- Indexes / constraints: PK(owner_type,owner_id,field,locale)
- Events: listing.updated
- Search projection: listings.*_fr

### `i18n.synonyms`
Search synonyms per language (fr↔en, colloquial).

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| locale | text |  |
| terms | text[] |  |
| category_id | ulid |  |

- Events: search.synonyms_changed
- Search projection: analyzer synonym filter

## Schema `developer · audit` — Java (java)

### `developer · audit.api_keys`
Scoped keys per merchant (hashed).

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| merchant_id | ulid |  |
| name | text |  |
| scopes | text[] |  |
| key_hash | bytea |  |
| last_used_at | timestamptz |  |
| rate_limit | int |  |
| revoked_at | timestamptz |  |

- Indexes / constraints: index(key_hash)

### `developer · audit.webhook_endpoints`
HMAC-signed endpoints and subscribed events.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| merchant_id | ulid |  |
| url | text |  |
| secret_ref | text | KMS |
| events | text[] |  |
| active | bool |  |


### `developer · audit.webhook_deliveries`
Attempts with response codes; retried with backoff.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| endpoint_id | ulid |  |
| event_id | ulid |  |
| attempt | int |  |
| status_code | int |  |
| at | timestamptz |  |

- Indexes / constraints: index(endpoint_id,at)

### `developer · audit.outbox`
Transactional outbox → Debezium → Kafka.

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| aggregate | text |  |
| aggregate_id | ulid |  |
| type | text |  |
| payload | jsonb |  |
| trace_id | text |  |
| at | timestamptz |  |

- Indexes / constraints: Debezium reads WAL; rows purged after publish
- Events: everything

### `developer · audit.audit_log`
Immutable record of every privileged action (Console, Studio owners).

| column | type | notes |
|---|---|---|
| id | ulid | PK |
| actor_id | ulid |  |
| role | text |  |
| action | text |  |
| target_type | text |  |
| target_id | ulid |  |
| before | jsonb |  |
| after | jsonb |  |
| at | timestamptz |  |

- Indexes / constraints: append-only · nightly export to cold storage · 7-year retention

