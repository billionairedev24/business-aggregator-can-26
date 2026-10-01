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

All payloads are ids only. They never carry an address, a name or a photo.

## Courier app API (S-87)

- **Token:** scope `courier` on a **DPoP-bound** token from the `courier-app` client (S-29, [mobile-auth.md](mobile-auth.md)).
  A bearer token, or a token without the scope, gets 403 at the filter.
- **Who:** the person must be an active courier (403 `not_a_courier`).
- **Whose stops:** a courier sees and moves only their own run. Another courier's stop is 404.

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

A stop has these fields:

- `id, seq, kind: pickup|dropoff, state: pending|arrived|done, orderId, orderRef, eta, arrivedAt, doneAt`;
- `place`, for a pickup: `{merchantId, name, address, lat, lng}`;
- `dropoff`, for a drop-off: `{street, unit, city, postal, note, lat, lng}`;
- `packed`, for a pickup;
- `proofKind`.

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
| `POST /api/v1/console/fulfilment/runs/{runId}/assign` `{courierId}` | RunSummary; the previous courier is freed. 409 `courier_busy` / `run_started` |
| `GET /api/v1/console/fulfilment/orders/{orderId}` | `{orderId, orderRef, orderType, kind, market, state, orderBy, packBy, run, pickups: [{merchantId, name, packedAt, pickedUpAt}], dropoffEta, deliveredAt, proofKind}` |
| `GET /api/v1/console/fulfilment/couriers?market=` | `{items: [{id, userId, name, market, vehicle, status, active, shift, runId}]}` |
| `POST /api/v1/console/fulfilment/couriers` `{userId, market, vehicle: bike\|ebike\|car\|van}` | 201; 409 `already_a_courier` |
| `POST /api/v1/console/fulfilment/couriers/{courierId}/shifts` `{startsAt, endsAt}` | 201; at most 12 h |
| `POST /api/v1/console/fulfilment/plan` `{market?}` | `{runs, assigned}` |

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

Proof photos and signatures use the object storage of [object-storage.md](object-storage.md) (`STORAGE_PROVIDER`):
prefix `fulfilment/proofs/<runId>/<stopId>-<kind>`. Outside `local`/`test` with `STORAGE_PROVIDER=local`, uploads
fail loudly.

## Privacy

- **Drop-off addresses** are personal data. They are copied into `fulfilment.deliveries.dropoff` for the courier and
  cleared 30 days after the delivery ends (hourly job). Only the assigned courier and staff see them.
- **Events** carry ids only.
- **The PIN** is stored in clear, so that the customer's order can show it (S-88). It proves a hand-over, not an
  identity.
