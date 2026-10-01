-- S-70: the last "TODO indexes/constraints" of the V002–V015 baseline. Every TODO is now materialised here or by an
-- earlier migration, or dropped with its reason in DECISIONS.md (2026-09-30 — S-70). Additive only.

-- identity.addresses: GiST(geom) — nearest-zone and service-area lookups by a customer's saved address.
CREATE INDEX IF NOT EXISTS ix_addresses_geom ON identity.addresses USING gist (geom) WHERE geom IS NOT NULL;

-- catalogue.categories: index(parent_id) (the editor's cascading dropdowns, the shop's departments) ·
-- gin(search_terms) (type-ahead synonyms).
CREATE INDEX IF NOT EXISTS ix_categories_parent ON catalogue.categories (parent_id);
CREATE INDEX IF NOT EXISTS ix_categories_search_terms ON catalogue.categories USING gin (search_terms);

-- catalogue.services: index(merchant_id) (the listings table; the (merchant_id, sku) unique index is partial) ·
-- index(category_id) (category medians for vetting, the services landing).
CREATE INDEX IF NOT EXISTS ix_services_merchant ON catalogue.services (merchant_id);
CREATE INDEX IF NOT EXISTS ix_services_category ON catalogue.services (category_id);

-- orders.group_orders: unique(link_code) — the share link resolves to one group order.
CREATE UNIQUE INDEX IF NOT EXISTS ux_group_orders_link_code ON orders.group_orders (link_code) WHERE link_code IS NOT NULL;

-- fulfilment.runs: index(courier_id, state) — a courier's current run · fulfilment.stops: index(run_id, seq) — a
-- run's stops in order.
CREATE INDEX IF NOT EXISTS ix_runs_courier_state ON fulfilment.runs (courier_id, state);
CREATE INDEX IF NOT EXISTS ix_stops_run_seq ON fulfilment.stops (run_id, seq);

-- messaging.notifications: index(user_id, sent_at) — a person's notifications, newest first.
CREATE INDEX IF NOT EXISTS ix_notifications_user_sent ON messaging.notifications (user_id, sent_at DESC);

-- developer.audit_log: append-only. A privileged action's record is never changed; the only removal is the 7-year
-- retention purge, which must say so for its transaction (SET LOCAL northline.audit_retention = 'on') and may only
-- remove rows older than seven years. No purge job exists yet (DECISIONS.md).
CREATE FUNCTION developer.audit_log_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' AND current_setting('northline.audit_retention', true) = 'on'
     AND OLD.at < now() - interval '7 years' THEN
    RETURN OLD;
  END IF;
  RAISE EXCEPTION 'developer.audit_log is append-only (% refused)', TG_OP USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON developer.audit_log
  FOR EACH ROW EXECUTE FUNCTION developer.audit_log_append_only();
