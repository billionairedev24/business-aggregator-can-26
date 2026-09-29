-- Operations workstream (range V040–V049): orders — the seller's packing list. Additive only.
-- See docs/DECISIONS.md "Operations".

-- Order reference shown to both sides (NL-48213), timestamps, and the delivery area shown on the packing list.
ALTER TABLE orders.orders
  ADD COLUMN ref text,
  ADD COLUMN placed_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN delivered_at timestamptz,
  ADD COLUMN delivery_area text;
CREATE UNIQUE INDEX ux_orders_ref ON orders.orders(ref) WHERE ref IS NOT NULL;
CREATE INDEX ix_orders_customer ON orders.orders(customer_id);
CREATE INDEX ix_orders_state_window ON orders.orders(state, window_id);

-- Lines: title snapshot at checkout, who packed when, and the issue reported on a line ("wrong size").
ALTER TABLE orders.order_lines
  ADD COLUMN title text,
  ADD COLUMN packed_at timestamptz,
  ADD COLUMN packed_by text,
  ADD COLUMN issue_note text,
  ADD CONSTRAINT chk_line_qty CHECK (qty > 0);
CREATE INDEX ix_order_lines_order ON orders.order_lines(order_id);
CREATE INDEX ix_order_lines_merchant_state ON orders.order_lines(merchant_id, state);

-- The run code couriers and sellers use ("R-611"); fulfilment.runs has no human reference.
ALTER TABLE orders.delivery_windows ADD COLUMN run_label text;
CREATE INDEX ix_delivery_windows_zone_start ON orders.delivery_windows(zone_id, starts_at);
