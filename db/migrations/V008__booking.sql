-- schema: booking · owner: Java (java)
-- Generated from the Northline spec (docs/DATA_MODEL.md). Hand-edit freely; keep column names.
CREATE SCHEMA IF NOT EXISTS booking;

-- A service appointment of any type; the central aggregate for escrow release.
CREATE TABLE booking.bookings (
  -- PK
  id text PRIMARY KEY,
  customer_id text,
  merchant_id text,
  member_user_id text,
  service_id text,
  -- visit | home | event | appointment | consult
  type text CHECK (type IN ('visit', 'home', 'event', 'appointment', 'consult')),
  -- requested | confirmed | en_route | on_site | completed | signed_off | disputed | cancelled
  state text CHECK (state IN ('requested', 'confirmed', 'en_route', 'on_site', 'completed', 'signed_off', 'disputed', 'cancelled')),
  starts_at timestamptz,
  ends_at timestamptz,
  -- null for appointment/video
  address_id text,
  -- vehicle / home / event / goal
  details jsonb,
  quote_id text,
  escrow_id text,
  price_cents bigint,
  -- events 25%
  deposit_cents bigint
);
-- TODO indexes/constraints: index(merchant_id,starts_at) · index(customer_id) · exclusion constraint on member/time
-- outbox events: booking.requested · booking.confirmed · booking.completed · booking.signed_off · booking.cancelled
-- search projection: merchants.stats

-- Append-only transitions with GPS and photo proof.
CREATE TABLE booking.booking_events (
  -- PK
  id text PRIMARY KEY,
  -- FK
  booking_id text,
  type text,
  at timestamptz,
  actor_id text,
  geom geography(Point),
  -- completion photos
  media_id text,
  note text
);
-- TODO indexes/constraints: index(booking_id,at)
-- outbox events: —

-- Customer's description sent to up to 3 merchants.
CREATE TABLE booking.quote_requests (
  -- PK
  id text PRIMARY KEY,
  customer_id text,
  category_id text,
  details jsonb,
  media text[],
  merchant_ids text[],
  expires_at timestamptz,
  -- 2 h SLA for response score
  respond_by timestamptz
);
-- outbox events: quote.requested

-- A merchant's itemized reply. Honoured as written (Alberta CPA ±10 % rule); any change needs an approval row.
CREATE TABLE booking.quotes (
  -- PK
  id text PRIMARY KEY,
  -- FK
  request_id text,
  merchant_id text,
  -- QT-#### shown to both sides
  ref text,
  -- revisions create a new row, prior marked superseded
  version integer,
  -- not null · what is included
  scope text NOT NULL,
  -- what could change the price
  exclusions text,
  proposed_at timestamptz,
  duration_min integer,
  -- none | labour_90d | parts_labour_12m | manufacturer
  warranty text CHECK (warranty IN ('none', 'labour_90d', 'parts_labour_12m', 'manufacturer')),
  -- none | parts_upfront | pct
  deposit_kind text CHECK (deposit_kind IN ('none', 'parts_upfront', 'pct')),
  deposit_bps integer,
  -- = sum(lines) − discounts · trigger-checked
  subtotal_cents bigint,
  -- from region tax profile
  tax_cents bigint,
  total_cents bigint,
  -- media
  attachments text[],
  -- 24 | 72 | 168 | 336
  valid_hours integer,
  valid_until timestamptz,
  -- draft | sent | viewed | accepted | declined | expired | superseded
  state text CHECK (state IN ('draft', 'sent', 'viewed', 'accepted', 'declined', 'expired', 'superseded')),
  viewed_at timestamptz
);
-- TODO indexes/constraints: index(request_id) · index(merchant_id,state)
-- outbox events: quote.sent · quote.viewed · quote.accepted (→ booking + escrow hold) · quote.expired

-- Every line the customer sees. At least one line per quote; each needs a description and, unless discount, a positive amount.
CREATE TABLE booking.quote_lines (
  -- PK
  id text PRIMARY KEY,
  -- FK
  quote_id text,
  position integer,
  -- labour | part | fee | travel | discount
  kind text CHECK (kind IN ('labour', 'part', 'fee', 'travel', 'discount')),
  -- not null · 1–160 chars
  description text NOT NULL,
  -- e.g. remanufactured, 12-mo warranty
  note text,
  -- > 0
  qty numeric(8,2),
  -- ≥ 0 · > 0 unless discount
  unit_cents bigint,
  -- = qty × unit · negative for discount
  amount_cents bigint,
  -- default true
  taxable boolean
);
-- TODO indexes/constraints: unique(quote_id,position) · check(kind=discount or amount_cents>0)

-- Extra parts / scope changes approved in-app mid-job.
CREATE TABLE booking.approvals (
  -- PK
  id text PRIMARY KEY,
  booking_id text,
  amount_cents bigint,
  description text,
  state text,
  decided_at timestamptz
);
-- outbox events: booking.scope_changed

-- Foreign keys (in-module only)
-- logical ref (cross-module, no FK): booking.bookings.customer_id → identity.users.id
-- logical ref (cross-module, no FK): booking.bookings.merchant_id → merchants.merchants.id
-- logical ref (cross-module, no FK): booking.bookings.service_id → catalogue.services.id
-- logical ref (cross-module, no FK): booking.bookings.address_id → identity.addresses.id
ALTER TABLE booking.bookings ADD CONSTRAINT fk_bookings_quote_id FOREIGN KEY (quote_id) REFERENCES booking.quotes(id);
-- logical ref (cross-module, no FK): booking.bookings.escrow_id → payments.escrows.id
ALTER TABLE booking.booking_events ADD CONSTRAINT fk_booking_events_booking_id FOREIGN KEY (booking_id) REFERENCES booking.bookings(id);
-- logical ref (cross-module, no FK): booking.quote_requests.category_id → catalogue.categories.id
ALTER TABLE booking.quotes ADD CONSTRAINT fk_quotes_request_id FOREIGN KEY (request_id) REFERENCES booking.quote_requests(id);
ALTER TABLE booking.quote_lines ADD CONSTRAINT fk_quote_lines_quote_id FOREIGN KEY (quote_id) REFERENCES booking.quotes(id);
ALTER TABLE booking.approvals ADD CONSTRAINT fk_approvals_booking_id FOREIGN KEY (booking_id) REFERENCES booking.bookings(id);
