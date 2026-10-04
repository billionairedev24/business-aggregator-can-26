-- Local development only (db/seed-dev, `local` profile): V345 starts production with the first market at `pilot` and
-- the others on the waitlist (owner decision 2026-10-04). On a developer's machine, the e2e stack (S-117), the pilot
-- dry run (S-120) and the go-live rehearsal (S-118 — it lowers the market to pilot itself, then launches it) the
-- launch markets are live as before, so the dev seed's shops, kitchens and providers can be browsed and ordered from.
UPDATE region.regions SET stage = 'live'
 WHERE kind = 'market' AND id IN ('mkt-calgary', 'mkt-edmonton', 'mkt-airdrie') AND stage IN ('pilot', 'waitlist');
