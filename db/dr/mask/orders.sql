-- orders: delivery addresses, recipients and instructions inside the food checkout document, and notes about a line.
-- Grocery/shop checkouts and orders point at identity.addresses (masked there); delivery and delivery_proof are modes.
update orders.food_checkouts set delivery = pg_temp.nl_scrub(delivery) where delivery is not null;
update orders.order_lines set issue_note = null where issue_note is not null;
