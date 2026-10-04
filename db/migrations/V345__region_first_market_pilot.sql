-- Owner decision 2026-10-04 ("Pilot onboarding directly in production", S-120): production starts hidden. V131 shipped
-- the first province's markets `live`; pilot businesses onboard in production while their market is `pilot` (visitors
-- join the waitlist, pilot businesses stay out of search), and the market goes live only through the two-person
-- go-live switch (S-118). So:
--   * the first market (the lowest sort among V131's markets, the pilot market) → `pilot`;
--   * the other markets V131 made live → `waitlist` (they aren't piloting; visitors there join the waitlist).
-- A market that already went live through the go-live switch (an approved launch request) is left alone, so this
-- never undoes a real launch. The province stays live (S-84: a province's own stage; markets carry the launch).
-- Local development and the e2e stack set them live again (db/seed-dev/V349, `local` profile only).
WITH v131 AS (
  SELECT id, row_number() OVER (ORDER BY sort, id) AS n
    FROM region.regions
   WHERE kind = 'market' AND id IN ('mkt-calgary', 'mkt-edmonton', 'mkt-airdrie') AND stage = 'live'
     AND NOT EXISTS (SELECT 1 FROM golive.launch_requests l WHERE l.market_id = region.regions.id AND l.state = 'approved')
)
UPDATE region.regions r
   SET stage = CASE WHEN v.n = 1 THEN 'pilot' ELSE 'waitlist' END
  FROM v131 v
 WHERE r.id = v.id;
