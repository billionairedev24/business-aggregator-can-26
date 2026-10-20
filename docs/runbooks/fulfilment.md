# Fulfilment: runs, couriers, dispatch and proof of delivery (S-86)

The `fulfilment` module plans courier runs from the orders the `orders` module hands over, assigns couriers on shift,
and runs the courier's stops with proof of delivery. This page is the contract for:

- the courier app (S-87), which calls `/api/v1/courier/**`;
- the console's orders monitor and delivery ops map (S-81), which calls `/api/v1/console/fulfilment/**`.

The console part is also summarised in [CONSOLE_PLAN.md](../CONSOLE_PLAN.md) § Delivery and dispatch.

## How an order becomes a delivery

1. **Checkout** places the order (S-51 shop, S-57 food). On `order.placed`, `orders.application.DispatchHandover`
   calls `fulfilment.api.DeliveryRequests.request(...)`.
   - fulfilment never reads orders' tables. orders depends on fulfilment, never the reverse: food reads
     `CourierPickups`, and orders depends on food.
   - A pooled order arrives with its run's window: label, start, end, the customers' cut-off (`orderBy`) and the
     shops' cut-off (`packBy`). A direct goods order and a food delivery arrive as `direct`.
   - Pickup-at-the-counter orders are never sent.
   - Each delivery gets a 4-digit **drop-off PIN**, which the customer sees on their order (S-88).
2. **Packing:**
   - A shop's "Mark packed" (`order.packed`) marks that shop's pickup packed.
   - For food, the kitchen's `order.ready` does the same, and `order.accepted` sets the ready-by time.
3. **Planning** happens in `DispatchJobs` every minute (not under `test`), or on demand with `POST …/plan`:
   - **Pooled:** once a window's `orderBy` has passed, its orders become runs of at most
     `northline.fulfilment.max-drops-per-run` (12) drop-offs. A fuller window is split into parts 1, 2, …
     ("R-701 · 2"). Orders whose shops haven't packed yet still join the run, because shops pack by `packBy`; the
     courier's pickup checks it.
   - **Direct goods:** one run per order, once every shop has packed.
   - **Food:** one run per order, once the kitchen has accepted it. The pickup is due at ready-by.
4. **Stop order** follows a simple, deterministic heuristic, `nearest-neighbour-v1`, recorded on each run
   (`fulfilment.domain.RoutePlanner`):
   - every pickup first, then the drop-offs;
   - shops by nearest neighbour from the westernmost located shop, then unlocated shops by id;
   - drop-offs by nearest neighbour from the last shop over located addresses, then unlocated addresses by postal
     code, street and order id. Shop addresses aren't geocoded yet, so in practice this is postal order.
   - **ETAs:** a leg is 3 min/km on a straight line (at least 4 min), or 8 min when either end isn't located. A pickup
     adds 5 min and a drop-off 3 min. One shop with several orders counts as one place.
5. **Assignment** gives runs that start within `assign-lead` (60 min; direct runs at once) to a courier who is:
   - in the run's market;
   - `available` and active;
   - on a shift that is on and ends after the run starts;
   - the one longest without a run.

   The run row is locked (`for update skip locked`) and the courier is claimed with a conditional update
   (`available → on_run`). A unique index allows one open run per courier. Concurrent planners therefore never give
   a run two couriers or a courier two runs (tested).
6. **The courier's stops:**
   - The courier arrives, then picks up. Pickup needs the shop's packing (409 `not_packed`) and records the
     sealed-bag scan.
   - Drop-off needs proof:
     - a **photo** or **signature**, uploaded first (JPG/PNG/WebP up to 5 MB, type checked from the bytes, stored
       through the storage port); or
     - the customer's **PIN**.
   - The last pickup of an order publishes `delivery.picked_up`, and orders moves the order to `picked_up`.
   - A drop-off publishes `delivery.completed`. orders moves the order to `delivered`, which starts the goods escrow's
     7-day window and captures the delivery fee (S-78).
   - The run is `loading` from the first stop, `en_route` once every pickup is done and `done` after the last
     drop-off. The courier is then `available` again (or `offline` when off shift).

## Events (topics in `deploy/kafka/topics.yaml`; schemas in `server/api/src/main/resources/events`)

| event | topic, key | when |
|---|---|---|
| `run.planned` (`RunPlanned`) | `fulfilment.run`, run id | a run was planned: market, kind, window, orders, stop count, heuristic |
| `delivery.assigned` (`DeliveryAssigned`) | `fulfilment.run`, run id | a courier was given the run |
| `delivery.picked_up` (`DeliveryPickedUp`) | `fulfilment.delivery`, order id | the courier collected the order from every shop |
| `delivery.completed` (`DeliveryCompleted`, S-78) | `fulfilment.delivery`, order id | dropped off with proof `photo` / `signature` / `pin` |
| `delivery.failed` (`DeliveryFailed`, S-151) | `fulfilment.delivery`, order id | the courier couldn't hand it over: `reason`, `attempt` |
| `run.changed` (`RunChanged`) | `fulfilment.run`, run id | S-151 adds `cancelled` (no stops left), `stops_removed` (an order cancelled), `stops_moved` (a stuck run split) |
| `order.cancelled` (`OrderCancelled`, S-151) | `orders.order`, order id | the whole order or one shop's lines: `by`, `reason` code, `whole`, line ids |
| `order.delivery_failed` (`OrderDeliveryFailed`, S-151) | `orders.order`, order id | the customer is asked to choose by `decideBy` |
| `return.updated` (`ReturnUpdated`, S-151) | `orders.return`, return id | requested, approved, declined, escalated, collected, received, refunded, withdrawn |

All payloads are ids only. They never carry an address, a name or a photo.

## Courier app API (S-87)

- **Token:** scope `courier` on a **DPoP-bound** token from the `courier-app` client (S-29, [mobile-auth.md](mobile-auth.md)).
  A bearer token, or a token without the scope, gets 403 at the filter.
- **Who:** the person must be an active courier (403 `not_a_courier`).
- **Whose stops:** a courier sees and moves only their own run. Another courier's stop is 404.
- **Replays (S-87):** every stop action is idempotent by the stop's state, so the app's offline outbox can send an
  action again when it lost the answer: a done stop answers the run unchanged (no second event), also after the run
  is done; a proof upload for a done stop answers 409 `stop_done`, which the app counts as sent. The app sends an
  `Idempotency-Key` per action (the same on every retry); the api does not need it today.
- **The app:** [courier-app.md](courier-app.md) (`mobile/apps/courier`).

| call | answer |
|---|---|
| `GET /api/v1/courier/me` | `{courierId, market, vehicle, status: offline\|available\|on_run, shift}` |
| `GET /api/v1/courier/shifts` | `{items: [{id, startsAt, endsAt, state: scheduled\|on\|done, startedAt, endedAt}]}` |
| `POST /api/v1/courier/shifts/{id}/start` | from 15 min before its start (409 `shift_not_startable`); the courier becomes `available` |
| `POST /api/v1/courier/shifts/{id}/end` | 409 `run_open` while a run isn't done |
| `GET /api/v1/courier/run` | the run (`id, label, part, kind, state, market, startsAt, endsAt, stops[]`), or **204** without one |
| `POST /api/v1/courier/stops/{id}/arrive` | the run |
| `POST /api/v1/courier/stops/{id}/pickup` `{scanOk}` | the run; 409 `not_packed` |
| `POST /api/v1/courier/stops/{id}/proof` multipart `kind=photo\|signature`, `file` | the run; 422 "Upload a JPG, PNG or WebP image under 5 MB." |
| `POST /api/v1/courier/stops/{id}/dropoff` `{proof: photo\|signature\|pin, pin?}` | the run; 409 `not_picked_up` / `proof_missing`; 422 for a missing or wrong PIN |
| `POST /api/v1/courier/stops/{id}/fail` `{reason: absent\|unsafe\|refused\|access\|other, note?}` (S-151) | after a `photo` proof upload: the run, with a `return` stop per shop; 409 `proof_missing` / `not_picked_up`; 422 for no reason, or `other` without a note |
| `POST /api/v1/courier/stops/{id}/collect` (S-151) | a customer's return collected at their door: the run |
| `POST /api/v1/courier/stops/{id}/returned` | the goods are back at the business (a refusal, a failed drop-off or a return) |
| `GET /api/v1/courier/earnings` (S-151) | `{zone, today, week, runs[], balanceCents, payoutAccount, payouts[]}` |
| `POST /api/v1/courier/payout-account/onboarding` `{returnUrl?}` (S-151) | `{url}`: Stripe's onboarding; the return URL is always `fulfilment.courier-payout-return` |

A stop answered 409 `stop_removed` was taken off the run (its order was cancelled, or dispatch moved it).

A stop has these fields:

- `id, seq, kind: pickup|dropoff|return|collect, state: pending|arrived|done, orderId, orderRef, eta, arrivedAt, doneAt`;
- `place`, for a pickup: `{merchantId, name, address, lat, lng}`;
- `dropoff`, for a drop-off: `{street, unit, city, postal, note, lat, lng}`;
- `packed`, for a pickup;
- `proofKind` (`failed` after a failed drop-off);
- S-151: `failReason`, and `returnRef` on a `collect` or `return` stop of a customer's return. A `collect` stop's
  address is in `dropoff`.

## Console API (S-81's orders monitor and delivery ops map)

`/api/v1/console/**` needs role staff and a second factor (403 `mfa_required`), and S-90's console roles:

- runs and couriers need the delivery screen (dispatch, admin);
- changes also need its `dispatch` action;
- an order's delivery needs the orders screen (dispatch, support, admin).

Changes are written to the platform audit log (`developer.audit_log`, no business).

| call | answer |
|---|---|
| `GET /api/v1/console/fulfilment/runs?market=&from=&to=` | `{items: [RunSummary]}`; the default range is yesterday to 2 days ahead |
| `GET /api/v1/console/fulfilment/runs/{runId}` | `{run: RunSummary, stops: [stop as above]}` |
| `POST /api/v1/console/fulfilment/runs/{runId}/assign` `{courierId}` | RunSummary; the previous courier is freed. 409 `courier_busy` (also a paused courier, S-81) / `run_started` |
| `GET /api/v1/console/fulfilment/orders/{orderId}` | `{orderId, orderRef, orderType, kind, market, state, orderBy, packBy, run, pickups: [{merchantId, name, packedAt, pickedUpAt}], dropoffEta, deliveredAt, proofKind}` |
| `GET /api/v1/console/fulfilment/couriers?market=` | `{items: [{id, userId, name, market, vehicle, status, active, shift, runId}]}` |
| `POST /api/v1/console/fulfilment/couriers` `{userId, market, vehicle: bike\|ebike\|car\|van}` | 201; 409 `already_a_courier` |
| `POST /api/v1/console/fulfilment/couriers/{courierId}/shifts` `{startsAt, endsAt}` | 201; at most 12 h |
| `POST /api/v1/console/fulfilment/plan` `{market?}` | `{runs, assigned}` |
| `POST /api/v1/console/fulfilment/couriers/{courierId}/pause` `{reason}` | CourierSummary with `active: false`: no new run, automatic or by hand (S-81); the run they have stays theirs. Audited `fulfilment.courier_paused` with the reason |
| `POST /api/v1/console/fulfilment/couriers/{courierId}/resume` | CourierSummary with `active: true`. Audited `fulfilment.courier_resumed` |
| `POST /api/v1/console/fulfilment/runs/{runId}/move-remaining` `{courierId}` (S-151) | a stuck run: the orders not picked up yet move to another courier as a new part of the same window (RunSummary of the new run). 409 `run_not_started` (use assign) / `run_ended` / `nothing_to_move` / `courier_busy`. Audited `fulfilment.run_split` |

`RunSummary` is:

`{id, label, part, market, kind, state, startsAt, endsAt, packBy, courier: {id, userId, name}, orders, stopsDone, stopsTotal, nextEta, late, heuristic}`

`late` means a pending stop is more than 15 minutes past its ETA.

## Configuration (`northline.fulfilment`, defaults in `FulfilmentProperties`; no new environment variables)

| property | default | meaning |
|---|---|---|
| `max-drops-per-run` | 12 | drop-offs per pooled run before the window is split |
| `assign-lead` | 60m | how long before a pooled run starts its courier is assigned |
| `minutes-per-km`, `min-leg`, `unknown-leg` | 3.0, 4m, 8m | ETA legs |
| `pickup-dwell`, `dropoff-dwell` | 5m, 3m | time at each stop |
| `address-retention` | 30d | when a delivered order's drop-off address is cleared |
| `proof-dir` | `$TMPDIR/northline-proofs` | proofs under `local` / `test` |
| `dispatch-interval` | PT1M | the planner job |
| `courier-pay.per-run-cents`, `…per-pickup-cents`, `…per-dropoff-cents`, `…per-failed-cents`, `…per-collect-cents`, `…per-return-cents` | 400, 100, 300, 300, 300, 100 | S-151: the platform's courier pay model; regions override it (below) |
| `return-join-within` | 24h | S-151: a return pickup rides on the market's planned pooled run starting this soon |
| `return-direct-after` | 24h | S-151: a return pickup no pooled run took gets a run of its own after this long |
| `courier-payout-return` | `${CONSUMER_ORIGIN}/courier/payouts` | S-151: where Stripe's onboarding sends a courier back (the courier app claims `/courier/*`) |

Proof photos and signatures use the object storage of [object-storage.md](object-storage.md) (`STORAGE_PROVIDER`):
prefix `fulfilment/proofs/<runId>/<stopId>-<kind>`. Outside `local`/`test` with `STORAGE_PROVIDER=local`, uploads
fail loudly.

## Privacy

- **Drop-off addresses** are personal data. They are copied into `fulfilment.deliveries.dropoff` for the courier and
  cleared 30 days after the delivery ends (hourly job). Only the assigned courier and staff see them.
- **Events** carry ids only.
- **The PIN** is stored in clear, so that the customer's order can show it (S-88). It proves a hand-over, not an
  identity.

## Live tracking (S-88)

- **Courier pings:** `POST /api/v1/courier/location` with `{lat, lng, heading?}` returns `{acceptedAt, nextAfterMs}`.
  - The courier must be on shift (409 `not_on_shift`).
  - At most one ping every `ping-interval` (2 s) per courier, across replicas. A faster ping gets 429
    `too_many_pings` with `Retry-After`.
  - The app should send every 4 s while a run is open. Customers then see updates at most 5 s apart.
- **Storage:** only the latest position is kept, in Valkey `nl:courier-pos:<courierId>` with a TTL of
  `position-ttl` (5 min). Each ping replaces the last one. There is **no history**, and nothing is ever written to
  Postgres; a test checks the fulfilment schema has no coordinate column. Proof of delivery is the stop's
  photo/signature/PIN and time (S-86), not a GPS trail.
- **Who sees it:**
  - The customer, only while their order is **on its way** (picked up, not yet delivered).
  - Ops, on the console's couriers list (`position`), for the delivery ops map.
  - Before pickup the customer sees the courier's first name, the planned ETA and their PIN. After delivery they see
    neither the position nor the PIN.
- **Fan-out:** every accepted ping publishes "moved" on Valkey channel `nl:courier:<orderId>` for each order on the
  run that is on its way. Every api replica wakes its open tracking streams for that order; each stream re-reads the
  order with the customer's own rights. Locally (`LIVE_BUS=memory`) this all happens in memory.
- **Streams:**
  - Goods: `GET /api/v1/me/orders/{id}/events` (S-52; event `order`).
  - Food: new `GET /api/v1/me/food-orders/{id}/events` (event `food`; the page polled every 15 s before).
  - Both go through the consumer-bff's streaming relay (`SseRelayTest`).
  - The JSON gains `courier: {state, runLabel, courierName, eta, stopsBefore, lat, lng, positionAt, pin}`. The ETA
    is live from the position: the run's leg rules from the position through the drop-offs still before this one.
- **Kitchen and shop screens:** `delivery.assigned` (now with `merchantIds`, `orderType`) and the new
  `delivery.courier_arrived` (topic `fulfilment.delivery`) signal the Studio's live stream (`kitchen` / `orders`).
  The kitchen display and Orders show "courier assigned / arriving / here" at once, instead of after the 60 s safety
  refresh.
- **Configuration:** `northline.fulfilment.ping-interval` (2s) and `position-ttl` (5m). The adapter follows `LIVE_BUS`
  (S-68), so there is no new variable.

## Promo codes, points and tips (mobile gaps part 2)

Money rules and the reasons are in [DECISIONS.md § Mobile gaps part 2](../DECISIONS.md); open legal and tax points are
[counsel-questions.md § K](../compliance/legal/counsel-questions.md#k-promo-codes-points-courier-tips-and-reviews-mobile-gaps-part-2).

- **Making a code:** console › Finance › Promo codes (`finance` or `admin`, second factor). Kind (percent with an
  optional cap, or amount), minimum spend, window, per-customer and total limits, where it applies (shop, food,
  services) and who funds it (Northline, or one merchant — then only that merchant's lines are discounted). "Switch
  off" stops it at once; reservations already made are honoured. Every change is in the audit log
  (`promotions.code_created`, `promotions.code_on` / `promotions.code_off`). Api: `GET|POST /api/v1/console/promotions/codes`,
  `PATCH /api/v1/console/promotions/codes/{id}` `{active}`.
- **What the ledger shows:** `promotions` (Northline's cost of its codes, debited at release), `points_redeemed`
  (points spent as money), `courier_tips` (tips captured, waiting for the delivery) and `courier:<userId>` (a courier's
  tips). Refunds give back the points share (the customer's wallet, `refund_return`) and take back the refunded share
  of a Northline top-up from the merchant.
- **Points settings:** `NORTHLINE_POINTS_PER_DOLLAR` (100), `NORTHLINE_POINTS_MAX_ORDER_PERCENT` (50),
  `NORTHLINE_POINTS_MIN_REDEEM` (100). A change applies to the next quote; points already spent keep their value.
- **Tips:** console order › Tips lists them (`GET /api/v1/console/orders/{orderId}/tips`); refund only when the order
  wasn't delivered, a tip was charged twice or the amount was wrong (`POST /api/v1/console/tips/{id}/refund` with
  `reason` `not_delivered` | `duplicate` | `amount_error`; FINANCE + refund). A refunded tip that was already allocated
  is taken back from the courier's account. Tips and run pay accrue on the courier's ledger account and are paid out
  weekly (S-151, [Courier earnings and payouts](#courier-earnings-and-payouts-s-151)).
- **Reviews:** a screened review (masked contact details or swearing) raises a `review_screened` flag in the trust
  queue; "Hide review" there or `POST /api/v1/console/trust/reviews/{id}/hide|show` (TRUST, second factor). Hidden
  reviews leave the business's rating at once and its search document on the worker's next sweep.
- **Visit ETA:** `BOOKING_ETA_MINUTES_PER_KM` (2.0). Positions follow `LIVE_BUS` (Valkey keys `nl:visit-pos:*` and
  `nl:visit-ping:*`, 5-minute TTL, no history). A customer seeing "isn't sharing" while the member says they are: the
  member's Studio tab must stay open (the browser shares every 5 s) and the job must be en route; a booking without a
  map point shows "sharing" without minutes.

## Cancelling, failed deliveries and returns (S-151)

Decisions and what is not done: [DECISIONS.md § S-151](../DECISIONS.md). Money moves through the payments module's
refund queue (`OrderRefunds`): every refund is a `Refund` charged to the shop, with the ledger entries of a normal
refund and the tax reversed at the tax provider (`taxes.refunded`, the `TaxGateway`).

### Who may cancel, and until when (configuration)

`northline.orders.after-sale` (defaults in `AfterSaleProperties`, no environment variable; relaxed binding gives
`NORTHLINE_ORDERS_AFTERSALE_*` if an environment needs one):

| property | default | meaning |
|---|---|---|
| `customer-cancel-until` | `packed` | the customer cancels until any shop packs (`packed` \| `picked_up` \| `delivered`) |
| `seller-cancel-until` | `picked_up` | a shop cancels its own lines until the courier has them |
| `staff-cancel-until` | `delivered` | Northline staff (console, support action) until delivery; after that it is a refund |
| `failed-delivery-decide-within` | 48h | how long the customer has to choose after a missed delivery |
| `failed-delivery-fee-cents` | 0 | kept from the delivery fee when the customer chooses a refund (or it is refunded automatically) |
| `return-days` | 30 | the return window when the category sets none |
| `job-interval` | PT1M | the job that refunds missed deliveries nobody chose for |

Food orders are not cancelled here (409 `not_cancellable`; the kitchen flow owns them).

**What a cancellation does:** the lines go `cancelled`; the escrow holds (products) are voided — or refunded when
captured — per shop; the delivery fee hold is voided when the whole order is cancelled; stock goes back on sale
(`restock`, default on for shops and staff); the order's stops leave their run (`RunChanged stops_removed`; a run with
no stops left is `cancelled` and its courier freed); `order.cancelled` goes out (worker: push, SMS, email to the
customer; Studio live events). Staff cancels are audited `orders.order_cancelled` (codes only; the note stays on the
order).

| call | answer |
|---|---|
| `POST /api/v1/me/orders/{id}/cancel` `{reason, note?}` | the order's tracking; 409 `too_late` / `order_closed` / `not_cancellable` |
| `POST /api/v1/merchants/{m}/orders/{id}/cancel` `{reason, note?, restock?}` (OPERATE) | the shop's order; 409 `too_late` / `nothing_to_cancel` / `order_closed` |
| `GET /api/v1/console/orders/{id}/after-sale` (orders screen) | `{orderId, state, cancellations[], deliveryFailure, returns[]}` |
| `POST /api/v1/console/orders/{id}/cancel` `{reason, note?, restock?}` (orders screen + `support` action) | `{cancellationId, orderId, state, whole, lineIds}` |

### Failed deliveries

1. The courier takes a photo at the door (`/proof`, kind `photo`) and calls `/fail` with a reason. The drop-off stop is
   `done` with proof `failed`; a `return` stop to each shop is added to the run; `delivery.failed` goes out.
2. Orders moves the order to `delivery_failed` and asks the customer (`order.delivery_failed`: push, SMS, email). The
   console's orders monitor lists these under **Delivery failed**.
3. The customer chooses by `decideBy`: `POST /api/v1/me/orders/{id}/delivery-failure {choice: redeliver|refund}`.
   - `redeliver`: the order is handed to dispatch again for the next run in its market (`attempts` counts).
   - `refund`: like a cancellation by the customer, keeping `failed-delivery-fee-cents` of the delivery fee.
4. Nobody chose by then: the job refunds it the same way (`by: system`).

### Returns

1. A delivered line in its window can be returned. The window is the category's
   (`catalogue.category_return_windows`, days; 0 = not returnable, e.g. food and restricted items), else `return-days`.
2. The customer sends one shop's lines, a reason and a photo (JPG / PNG / WebP up to 5 MB, object storage under
   `orders/returns/`): `POST /api/v1/me/orders/{id}/returns` (multipart).
3. The shop decides in Studio › Orders › Returns (`/api/v1/merchants/{m}/returns`, `…/{id}/photo`, `…/approve
   {method: pickup|dropoff, restock?}`, `…/decline {reason}`, `…/received`):
   - **pickup:** a `collect` stop at the customer's door and a `return` stop at the shop ride on the market's next
     pooled run (within `return-join-within`), else a run of their own after `return-direct-after`;
   - **dropoff:** the customer brings it; the shop marks it received.
   - Received (or delivered back by the courier): the lines are refunded with their tax, restocked if asked.
4. A declined return can go to a Northline agent: `POST /api/v1/me/returns/{id}/escalate {statement}` opens a dispute
   in the disputes queue. `…/withdraw` withdraws it before the shop has the items.

### Moving the rest of a stuck run

Console › Delivery ops › Runs › **Move remaining stops** (dispatch action) on a run that is `loading` or `en_route`:
the orders whose pickups aren't done move to a free courier on shift as a new part of the same window. What the first
courier already carries stays with them. Audited `fulfilment.run_split`.

## Courier earnings and payouts (S-151)

- **Pay model (region configuration):** `region.regions.courier_pay` (jsonb) on a province or a market, keys
  `perRunCents`, `perPickupCents` (each shop), `perDropoffCents`, `perFailedCents`, `perCollectCents`, `perReturnCents`.
  The market's keys win over its province's, which win over `northline.fulfilment.courier-pay`. A finished run is paid
  once (`fulfilment.courier_earnings`, with the model it was paid under) and posted to the ledger
  (`courier_pay` → `courier:<userId>`, ref `courier_run`).
- **Tips** (checkout or after delivery) are allocated to the courier who delivered and show on that run.
- **Payouts:** a weekly job (`COURIER_PAYOUTS_CRON`, default Monday 06:41 in the platform zone) pays each courier's
  balance of at least $1.00 through the payouts port: a transfer to their Stripe Connect account (Express, Canada,
  transfers only, manual payouts) then a payout to its bank. Webhooks `payout.*` and `account.updated` settle them.
  Under `local`/`test` the fake gateway enables the account when the onboarding link is asked for and pays the next
  business morning. Details and what never ran against Stripe: [stripe.md § Courier payouts](stripe.md#11-courier-payouts-s-151).
- **The courier app:** Profile › Earnings and payouts ([courier-app.md](courier-app.md)).
