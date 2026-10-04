import type { Courier, Run, Shift, Stop } from '../api/courier';

/**
 * An in-memory northline-auth + courier API for the web smoke test, demos and screen tests
 * (`EXPO_PUBLIC_FIXTURES=1`; never in a production build). It answers like the real one — DPoP token answers with a
 * nonce, 204 without a run, 409 `not_packed`, 422 for a wrong PIN, 429 for pings faster than 2 s — so the app's
 * whole stack (session, proofs, outbox, pinger) runs against it. Places and people are made up.
 */
export const FIXTURE_PIN = '4821';

type Json = Record<string, unknown>;

export interface FixtureOptions {
  now?: () => number;
  /** Start with the shift on (default) or scheduled. */
  shiftOn?: boolean;
  /** The first pickup's shop has packed (default true). */
  packed?: boolean;
  /** Courier has a run (default true). */
  withRun?: boolean;
  /** 2026-10-04: the first order has age-restricted items (its drop-off needs the ID check). */
  idCheck?: boolean;
}

export function createFixtureServer(options: FixtureOptions = {}) {
  const now = options.now ?? Date.now;
  const iso = (ms: number) => new Date(ms).toISOString();
  const t0 = now();
  const shift: Shift = {
    id: 'shift-1',
    startsAt: iso(t0 - 30 * 60_000),
    endsAt: iso(t0 + 4 * 3600_000),
    state: options.shiftOn === false ? 'scheduled' : 'on',
    startedAt: options.shiftOn === false ? null : iso(t0 - 25 * 60_000),
    endedAt: null,
  };
  const courier: Courier = { courierId: 'cour-1', market: 'Sampleville', vehicle: 'ebike', status: 'on_run', shift };
  const stop = (s: Partial<Stop> & Pick<Stop, 'id' | 'seq' | 'kind' | 'orderId'>): Stop => ({
    state: 'pending',
    orderRef: null,
    eta: null,
    arrivedAt: null,
    doneAt: null,
    place: null,
    dropoff: null,
    packed: false,
    proofKind: null,
    ...s,
  });
  let run: Run | null =
    options.withRun === false
      ? null
      : {
          id: 'run-1',
          label: 'R-701',
          part: 1,
          kind: 'pooled',
          state: 'planned',
          market: 'Sampleville',
          startsAt: iso(t0),
          endsAt: iso(t0 + 2 * 3600_000),
          stops: [
            stop({ id: 'st-1', seq: 1, kind: 'pickup', orderId: 'ord-a', orderRef: 'NL-48102', eta: iso(t0 + 10 * 60_000), packed: options.packed !== false,
              place: { merchantId: 'm-1', name: 'Juniper Bakery', address: '12 Market Lane', lat: null, lng: null } }),
            stop({ id: 'st-2', seq: 2, kind: 'pickup', orderId: 'ord-b', orderRef: 'NL-48107', eta: iso(t0 + 18 * 60_000), packed: true,
              place: { merchantId: 'm-2', name: 'Fern & Field Grocer', address: '88 Mill Road', lat: null, lng: null } }),
            stop({ id: 'st-3', seq: 3, kind: 'dropoff', orderId: 'ord-a', orderRef: 'NL-48102', eta: iso(t0 + 31 * 60_000),
              dropoff: { street: '1204 Example Ave', unit: '804', city: 'Sampleville', postal: 'A1A 1A1', note: 'Buzz 0804', lat: null, lng: null },
              ...(options.idCheck ? { idCheck: { age: 19, recipient: 'Sam Example' } } : {}) }),
            stop({ id: 'st-4', seq: 4, kind: 'dropoff', orderId: 'ord-b', orderRef: 'NL-48107', eta: iso(t0 + 40 * 60_000),
              dropoff: { street: '57 Sample Street', unit: null, city: 'Sampleville', postal: 'A1B 2C3', note: null, lat: null, lng: null } }),
          ],
        };
  if (!run) courier.status = shift.state === 'on' ? 'available' : 'offline';
  /** The last finished run: a replayed action on one of its done stops answers it (as the api does). */
  let finished: Run | null = null;
  let lastPing = 0;
  let issued = 0;
  const pings: Array<{ lat: number; lng: number }> = [];
  /** What the ID checks recorded (the api keeps these three answers or the reason; never the ID). */
  const idChecks: Array<{ stopId: string; outcome: 'passed' | 'refused'; reason?: string }> = [];
  const calls: Array<{ method: string; path: string; idempotencyKey?: string }> = [];

  const answer = (status: number, body?: unknown, headers: Record<string, string> = {}) =>
    new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'content-type': 'application/json', date: new Date(now()).toUTCString(), ...headers },
    });
  const conflict = (code: string, detail: string) => answer(409, { code, detail });

  function stopOf(id: string) {
    return run?.stops.find((s) => s.id === id);
  }
  function settle() {
    if (!run) return;
    if (run.stops.some((s) => s.state !== 'pending') && run.state === 'planned') run.state = 'loading';
    if (run.stops.filter((s) => s.kind === 'pickup').every((s) => s.state === 'done')) run.state = 'en_route';
    if (run.stops.every((s) => s.state === 'done')) run.state = 'done';
  }
  function current() {
    const r = run!;
    if (r.state === 'done') {
      finished = r;
      run = null;
      courier.status = shift.state === 'on' ? 'available' : 'offline';
    }
    return answer(200, r);
  }

  async function handle(url: string, init: RequestInit = {}): Promise<Response> {
    const method = (init.method ?? 'GET').toUpperCase();
    const u = new URL(url, 'http://fixtures.invalid');
    const path = u.pathname.replace(/^.*\/api\/v1/, '');
    const headers = (init.headers ?? {}) as Record<string, string>;
    calls.push({ method, path: u.pathname, idempotencyKey: headers['Idempotency-Key'] });
    if (u.pathname.endsWith('/oauth2/token')) {
      issued++;
      return answer(200, { access_token: `fixture-access-${issued}`, token_type: 'DPoP', expires_in: 599, refresh_token: `fixture-refresh-${issued}` }, { 'DPoP-Nonce': `n${issued}` });
    }
    if (u.pathname.endsWith('/oauth2/revoke')) return answer(200, {});
    if (path === '/geo/regions') {
      const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
      return answer(200, { platformTimeZone: zone, markets: [{ id: 'mkt-sample', city: 'Sampleville', province: 'XX', timeZone: zone }] });
    }
    const body = typeof init.body === 'string' ? (JSON.parse(init.body) as Json) : {};
    if (method === 'GET' && path === '/courier/me') return answer(200, { ...courier, shift: shift.state === 'on' ? shift : null });
    if (method === 'GET' && path === '/courier/shifts') return answer(200, { items: shift.state === 'done' ? [] : [shift] });
    if (method === 'POST' && path === `/courier/shifts/${shift.id}/start`) {
      if (shift.state === 'scheduled') {
        shift.state = 'on';
        shift.startedAt = iso(now());
        courier.status = run ? 'on_run' : 'available';
      }
      return answer(200, shift);
    }
    if (method === 'POST' && path === `/courier/shifts/${shift.id}/end`) {
      if (run) return conflict('run_open', 'Finish your run before you end the shift.');
      shift.state = 'done';
      shift.endedAt = iso(now());
      courier.status = 'offline';
      return answer(200, shift);
    }
    if (method === 'GET' && path === '/courier/run') return run ? answer(200, run) : answer(204);
    if (method === 'POST' && path === '/courier/location') {
      if (shift.state !== 'on') return conflict('not_on_shift', 'Start your shift first.');
      if (now() - lastPing < 2000) return answer(429, { code: 'too_many_pings' }, { 'Retry-After': '2' });
      lastPing = now();
      pings.push({ lat: Number(body.lat), lng: Number(body.lng) });
      return answer(200, { acceptedAt: iso(now()), nextAfterMs: 2000 });
    }
    const m = /^\/courier\/stops\/([^/]+)\/(arrive|pickup|proof|dropoff|refuse|returned)$/.exec(path);
    if (method === 'POST' && m) {
      const s = stopOf(m[1]!);
      if (!s) {
        const done = finished?.stops.find((x) => x.id === m[1]);
        if (!done) return answer(404, { code: 'not_found', detail: 'Not found' });
        return m[2] === 'proof' ? conflict('stop_done', 'This stop is already done.') : answer(200, finished);
      }
      const action = m[2]!;
      if (action === 'arrive') {
        if (s.state === 'pending') {
          s.state = 'arrived';
          s.arrivedAt = iso(now());
        }
      } else if (action === 'pickup' && s.state !== 'done') {
        if (!s.packed) return conflict('not_packed', "This shop hasn't packed the order yet.");
        s.state = 'done';
        s.doneAt = iso(now());
      } else if (action === 'proof') {
        if (s.state === 'done') return conflict('stop_done', 'This stop is already done.');
        const form = init.body as FormData | undefined;
        s.proofKind = (form && typeof form.get === 'function' ? String(form.get('kind')) : 'photo') || 'photo';
      } else if (action === 'dropoff' && s.state !== 'done') {
        const pickedUp = run!.stops.filter((p) => p.kind === 'pickup' && p.orderId === s.orderId).every((p) => p.state === 'done');
        if (!pickedUp) return conflict('not_picked_up', 'Pick the order up before you drop it off.');
        if (body.proof === 'pin') {
          if (!body.pin) return answer(422, { errors: [{ field: 'pin', message: "Enter the customer's 4-digit PIN." }] });
          if (String(body.pin).trim() !== FIXTURE_PIN) {
            return answer(422, { errors: [{ field: 'pin', message: "That PIN doesn't match. Ask the customer for the 4 digits on their order." }] });
          }
        } else if (body.proof !== s.proofKind) {
          return conflict('proof_missing', 'Take the photo or signature first.');
        }
        if (s.idCheck) {
          const c = (body.idCheck ?? {}) as Json;
          if (!(c.idChecked && c.recipientMatches && c.ofAge)) {
            return answer(422, { errors: [{ field: 'idCheck', message: "Confirm you checked government photo ID, the name matches and the person is of age." }] });
          }
          idChecks.push({ stopId: s.id, outcome: 'passed' });
        }
        s.state = 'done';
        s.doneAt = iso(now());
        s.proofKind = String(body.proof);
        s.idCheck = null;
      } else if (action === 'refuse' && s.state !== 'done') {
        if (!s.idCheck) return conflict('no_id_check', 'This order has no age-restricted items: hand it over with the usual proof.');
        const pickedUp = run!.stops.filter((p) => p.kind === 'pickup' && p.orderId === s.orderId).every((p) => p.state === 'done');
        if (!pickedUp) return conflict('not_picked_up', 'Pick the order up before you drop it off.');
        idChecks.push({ stopId: s.id, outcome: 'refused', reason: String(body.reason) });
        s.state = 'done';
        s.doneAt = iso(now());
        s.proofKind = 'id_refused';
        s.idCheck = null;
        const shop = run!.stops.find((p) => p.kind === 'pickup' && p.orderId === s.orderId)?.place ?? null;
        run!.stops.push(stop({ id: `st-r-${s.id}`, seq: run!.stops.length + 1, kind: 'return', orderId: s.orderId, orderRef: s.orderRef, place: shop }));
      } else if (action === 'returned' && s.state !== 'done') {
        if (s.kind !== 'return') return conflict('not_a_return', 'This stop is not on your run.');
        s.state = 'done';
        s.doneAt = iso(now());
        s.proofKind = 'id_refused';
      }
      settle();
      return current();
    }
    return answer(404, { code: 'not_found' });
  }

  const fetchImpl = ((input: RequestInfo | URL, init?: RequestInit) => handle(String(input), init)) as typeof fetch;
  return {
    fetch: fetchImpl,
    pings,
    calls,
    idChecks,
    shift,
    get run() {
      return run;
    },
    pack(stopId: string) {
      const s = stopOf(stopId);
      if (s) s.packed = true;
    },
  };
}

export type FixtureServer = ReturnType<typeof createFixtureServer>;
