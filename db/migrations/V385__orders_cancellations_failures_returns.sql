-- S-151 (range V385–V394): order cancellation, failed deliveries and returns. Widens CHECKs (as V344 did) and adds
-- tables; no column renamed or dropped. See docs/DECISIONS.md "S-151".

-- An order whose courier couldn't hand it over (customer absent, unsafe, refused): the customer chooses re-delivery
-- or a refund; a closed order keeps `cancelled` / `refunded`.
ALTER TABLE orders.orders DROP CONSTRAINT orders_state_check;
ALTER TABLE orders.orders ADD CONSTRAINT orders_state_check
  CHECK (state IN ('placed', 'accepted', 'packing', 'ready', 'picked_up', 'delivered', 'confirmed', 'refunded',
                   'cancelled', 'returned', 'delivery_failed'));
ALTER TABLE orders.orders ADD COLUMN cancelled_at timestamptz;

-- A line a seller, the customer or Northline cancelled (its escrow is voided or refunded); `restocked` = its units went
-- back to the offer's stock; `return_id` = the return it is in now (one open return per line).
ALTER TABLE orders.order_lines DROP CONSTRAINT order_lines_state_check;
ALTER TABLE orders.order_lines ADD CONSTRAINT order_lines_state_check
  CHECK (state IN ('pending', 'packed', 'short', 'refunded', 'cancelled'));
ALTER TABLE orders.order_lines
  ADD COLUMN cancelled_at timestamptz,
  ADD COLUMN restocked boolean NOT NULL DEFAULT false,
  ADD COLUMN return_id text;

-- One row per cancellation: the whole order (customer, staff, a failed delivery's refund) or one shop's lines (seller).
-- `reason` is a code; `note` is the seller's or staff member's own words, shown in the Studio / console only — never in
-- events, notifications or the audit log.
CREATE TABLE orders.cancellations (
  id          text PRIMARY KEY,
  order_id    text NOT NULL,                          -- logical ref → orders.orders.id
  merchant_id text,                                   -- the shop whose lines were cancelled; null = the whole order
  actor_id    text,                                   -- identity.users.id; null = the system (auto-refund)
  actor_role  text NOT NULL CHECK (actor_role IN ('customer', 'merchant', 'staff', 'system')),
  reason      text NOT NULL,
  note        text CHECK (note IS NULL OR length(note) <= 500),
  restock     boolean NOT NULL,
  line_ids    text[] NOT NULL,
  whole       boolean NOT NULL,
  at          timestamptz NOT NULL
);
CREATE INDEX ix_cancellations_order ON orders.cancellations (order_id, at);

-- A failed drop-off and the customer's choice. A re-delivered order that fails again counts a second attempt.
CREATE TABLE orders.delivery_failures (
  order_id   text PRIMARY KEY,                        -- logical ref → orders.orders.id
  reason     text NOT NULL CHECK (reason IN ('absent', 'unsafe', 'refused', 'access', 'other')),
  failed_at  timestamptz NOT NULL,
  decide_by  timestamptz NOT NULL,                    -- after it, the order is refunded automatically
  attempts   integer NOT NULL DEFAULT 1,
  choice     text CHECK (choice IS NULL OR choice IN ('redeliver', 'refund', 'auto_refund')),
  chosen_at  timestamptz,
  fee_cents  bigint NOT NULL DEFAULT 0 CHECK (fee_cents >= 0),  -- kept from the delivery fee on a refund
  window_id  text,                                    -- the run chosen for re-delivery (pooled)
  run_id     text,                                    -- logical ref → fulfilment.runs.id (the failed run)
  CHECK ((choice IS NULL) = (chosen_at IS NULL))
);
CREATE INDEX ix_delivery_failures_due ON orders.delivery_failures (decide_by) WHERE choice IS NULL;

-- Returns of delivered goods: requested by the customer with a reason and a photo, approved or declined by the shop,
-- picked up on a run or dropped off, then refunded. A declined return can go to the disputes queue.
CREATE SEQUENCE orders.return_ref_seq START 10000;
CREATE TABLE orders.returns (
  id             text PRIMARY KEY,
  ref            text NOT NULL UNIQUE,                -- RT-10000…
  order_id       text NOT NULL,                       -- logical ref → orders.orders.id
  merchant_id    text NOT NULL,                       -- logical ref → merchants.merchants.id
  customer_id    text NOT NULL,                       -- logical ref → identity.users.id
  reason         text NOT NULL
                 CHECK (reason IN ('damaged', 'wrong_item', 'not_as_described', 'missing_parts', 'no_longer_needed', 'other')),
  note           text CHECK (note IS NULL OR length(note) <= 1000),
  photo_key      text,                                -- object storage key of the customer's photo
  photo_type     text,
  amount_cents   bigint NOT NULL CHECK (amount_cents > 0), -- the lines' price before tax (the refund's amount)
  state          text NOT NULL DEFAULT 'requested'
                 CHECK (state IN ('requested', 'approved', 'declined', 'escalated', 'collected', 'received', 'refunded',
                                  'withdrawn')),
  method         text CHECK (method IS NULL OR method IN ('pickup', 'dropoff')),
  restock        boolean NOT NULL DEFAULT false,
  decline_reason text CHECK (decline_reason IS NULL OR length(decline_reason) <= 500),
  decided_by     text,
  decided_at     timestamptz,
  collected_at   timestamptz,
  received_at    timestamptz,
  refunded_at    timestamptz,
  dispute_ids    text[] NOT NULL DEFAULT '{}',
  created_at     timestamptz NOT NULL,
  updated_at     timestamptz NOT NULL
);
CREATE INDEX ix_returns_merchant ON orders.returns (merchant_id, created_at DESC);
CREATE INDEX ix_returns_order ON orders.returns (order_id);
CREATE INDEX ix_returns_customer ON orders.returns (customer_id, created_at DESC);

CREATE TABLE orders.return_lines (
  return_id text NOT NULL REFERENCES orders.returns (id),
  line_id   text NOT NULL,                            -- logical ref → orders.order_lines.id
  PRIMARY KEY (return_id, line_id)
);
