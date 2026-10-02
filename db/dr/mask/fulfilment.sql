-- fulfilment: drop-off addresses and instructions, the hand-off PIN, route stops.
update fulfilment.deliveries set dropoff = pg_temp.nl_scrub(dropoff), pin = '0000';
update fulfilment.runs set route = pg_temp.nl_scrub(route) where route is not null;
