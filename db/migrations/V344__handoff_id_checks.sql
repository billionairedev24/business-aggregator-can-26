-- Age-restricted purchases (owner decision 2026-10-04): the ID check at handoff and the return to the business when it
-- fails. Widens CHECKs (as V132 did); no column renamed or dropped. See docs/DECISIONS.md.

-- orders: the age the recipient must prove at handoff (the strictest class in the order under the delivery address's —
-- or, for pickup, the business's — province rules), null for an order without restricted items; and the state of an
-- order that went back to the business because nobody of age could take it.
ALTER TABLE orders.orders ADD COLUMN id_check_age integer CHECK (id_check_age IS NULL OR id_check_age BETWEEN 16 AND 25);
ALTER TABLE orders.orders DROP CONSTRAINT orders_state_check;
ALTER TABLE orders.orders ADD CONSTRAINT orders_state_check
  CHECK (state IN ('placed', 'accepted', 'packing', 'ready', 'picked_up', 'delivered', 'confirmed', 'refunded',
                   'cancelled', 'returned'));
-- a line's age class at checkout, so the refund rule can tell restricted lines from the rest
ALTER TABLE orders.order_lines ADD COLUMN age_class text CHECK (age_class IS NULL OR age_class IN ('alcohol', 'tobacco', 'cannabis'));

-- fulfilment: a delivery that needs the ID check, the refused drop-off and the courier's stop back at the business.
ALTER TABLE fulfilment.deliveries ADD COLUMN id_check_age integer CHECK (id_check_age IS NULL OR id_check_age BETWEEN 16 AND 25);
ALTER TABLE fulfilment.deliveries DROP CONSTRAINT deliveries_state_check;
ALTER TABLE fulfilment.deliveries ADD CONSTRAINT deliveries_state_check
  CHECK (state IN ('waiting', 'planned', 'picked_up', 'delivered', 'cancelled', 'returning', 'returned'));
ALTER TABLE fulfilment.stops DROP CONSTRAINT stops_kind_check;
ALTER TABLE fulfilment.stops ADD CONSTRAINT stops_kind_check CHECK (kind IN ('pickup', 'dropoff', 'return'));
ALTER TABLE fulfilment.stops DROP CONSTRAINT stops_proof_kind_check;
ALTER TABLE fulfilment.stops ADD CONSTRAINT stops_proof_kind_check
  CHECK (proof_kind IS NULL OR proof_kind IN ('photo', 'signature', 'pin', 'id_refused'));

-- food: a pickup the kitchen refused at the counter (no one of age with ID came for it).
ALTER TABLE food.kitchen_tickets DROP CONSTRAINT kitchen_tickets_stage_check;
ALTER TABLE food.kitchen_tickets ADD CONSTRAINT kitchen_tickets_stage_check
  CHECK (stage IN ('new', 'cooking', 'ready', 'handed_off', 'refused'));

-- food: a dish's age class. A kitchen marks a dish alcohol (it can't mark one less than its category says: the kitchen's
-- food category 'alcohol with food' is the licence it needs, V341/V342); null = not restricted.
ALTER TABLE food.menu_items ADD COLUMN age_class text CHECK (age_class IS NULL OR age_class IN ('alcohol', 'tobacco', 'cannabis'));
ALTER TABLE food.menu_items ADD COLUMN licence_hold boolean NOT NULL DEFAULT false;
