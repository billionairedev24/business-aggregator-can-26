-- S-158 (range V462–V471): two-way stock with Shopify, Square and Lightspeed. Additive only; see docs/DECISIONS.md
-- "S-158" and docs/runbooks/commerce-sync.md § Two-way stock.

-- The platform location (Shopify location, Square location, Lightspeed outlet) Northline sells from: its stock is
-- what the listing shows, and Northline's sales, cancellations and returns adjust it there. NULL = not chosen yet
-- (a store with a single location uses that one; several → the merchant picks in Settings › Integrations).
ALTER TABLE catalogue.integrations
  ADD COLUMN location_id   text,
  ADD COLUMN location_name text;

-- One stock adjustment to send to a connected platform: a Northline sale (delta < 0), or a cancellation / return the
-- shop restocked (delta > 0). Written in the transaction that changed Northline's stock (or by the order listener),
-- unique per idempotency key, then sent by the stock-push job with retries. Ids, SKUs and counts only.
CREATE TABLE catalogue.commerce_stock_pushes (
  id                  text PRIMARY KEY,
  merchant_id         text NOT NULL,
  provider            text NOT NULL CHECK (provider IN ('shopify', 'square', 'lightspeed')),
  integration_id      text NOT NULL,                     -- catalogue.integrations.id (logical)
  offer_id            text NOT NULL,                     -- catalogue.offers.id (logical)
  sku                 text NOT NULL,
  external_variant_id text NOT NULL,                     -- the platform variant (catalogue.commerce_variants)
  stock_ref           text,                              -- what the platform's inventory API names
  delta               integer NOT NULL CHECK (delta <> 0),
  reason              text NOT NULL CHECK (reason IN ('sale', 'cancellation', 'return')),
  source_ref          text NOT NULL,                     -- order / cancellation / return id
  idempotency_key     text NOT NULL,                     -- also sent to the platform where it takes one
  state               text NOT NULL DEFAULT 'pending'
                        CHECK (state IN ('pending', 'done', 'conflict', 'failed', 'dismissed')),
  conflict            text CHECK (conflict IS NULL OR conflict IN ('oversold', 'scope_missing', 'choose_location',
                        'not_found', 'reconnect', 'disconnected', 'rejected', 'ambiguous')),
  attempts            integer NOT NULL DEFAULT 0,
  next_attempt_at     timestamptz NOT NULL,
  last_error          text,
  location_id         text,                              -- where it was applied
  platform_after      integer,                           -- the platform's count after it, when it says
  created_at          timestamptz NOT NULL,
  done_at             timestamptz,
  resolved_by         text,                              -- who retried or dismissed a conflict
  resolved_at         timestamptz,
  CONSTRAINT ux_commerce_stock_pushes_key UNIQUE (idempotency_key),
  CHECK ((state = 'conflict') = (conflict IS NOT NULL) OR state IN ('done', 'dismissed'))
);
CREATE INDEX ix_commerce_stock_pushes_due ON catalogue.commerce_stock_pushes (next_attempt_at) WHERE state = 'pending';
CREATE INDEX ix_commerce_stock_pushes_merchant ON catalogue.commerce_stock_pushes (merchant_id, provider, created_at DESC);
CREATE INDEX ix_commerce_stock_pushes_sku ON catalogue.commerce_stock_pushes (merchant_id, provider, sku) WHERE state = 'pending';
