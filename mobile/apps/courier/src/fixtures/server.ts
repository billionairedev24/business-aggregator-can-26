import type { Courier, Run, Shift, Stop } from '../api/courier';

/**
 * An in-memory northline-auth + courier API for the web smoke test, demos and screen tests
 * (`EXPO_PUBLIC_FIXTURES=1`; never in a production build). It answers like the real one — DPoP token answers with a
 * nonce, 204 without a run, 409 `not_packed`, 422 for a wrong PIN, 429 for pings faster than 2 s — so the app's
 * whole stack (session, proofs, outbox, pinger) runs against it. Places and people are made up.
 */
export const FIXTURE_PIN = '4821';
/** S-159: the courier's account (made up): email + password, an authenticator (code {@link FIXTURE_TOTP}). */
export const FIXTURE_COURIER = { email: 'casey@example.com', password: 'fixture courier password' };
export const FIXTURE_TOTP = '135790';
export const FIXTURE_BACKUP = 'abcd-efgh-ij';
/** A courier account without a second factor yet (enrolment is required in the courier app). */
export const FIXTURE_NEW_COURIER = { email: 'new.courier@example.com', password: 'another fixture password' };
export const FIXTURE_EMAIL_CODE = '246810';

type Json = Record<string, unknown>;

export interface FixtureOptions {
  now?: () => number;
  /** Start with the shift on (default) or scheduled. */
  shiftOn?: boolean;
  /** The first pickup's shop has packed (default true). */
  packed?: boolean;
  /** Courier has a run (default true). */
  withRun?: boolean;
  /** The courier takes part in the pilot as a courier (S-121; default false). */
  pilot?: boolean;
  /** 2026-10-04: the first order has age-restricted items (its drop-off needs the ID check). */
  idCheck?: boolean;
  /** S-151: the run also collects a customer's return (a collect stop, then its return to the shop). */
  withReturn?: boolean;
  /** S-151: the courier has set up payouts (default false). */
  payoutsEnabled?: boolean;
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
            ...(options.withReturn
              ? [
                  stop({ id: 'st-5', seq: 5, kind: 'collect', orderId: 'ord-c', orderRef: 'NL-48001', returnRef: 'RT-1201', eta: iso(t0 + 50 * 60_000),
                    dropoff: { street: '9 Example Court', unit: null, city: 'Sampleville', postal: 'A1C 3D4', note: null, lat: null, lng: null } }),
                  stop({ id: 'st-6', seq: 6, kind: 'return', orderId: 'ord-c', orderRef: 'NL-48001', returnRef: 'RT-1201', eta: iso(t0 + 60 * 60_000),
                    place: { merchantId: 'm-1', name: 'Juniper Bakery', address: '12 Market Lane', lat: null, lng: null } }),
                ]
              : []),
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
  /** S-151: failed drop-offs (reason and note) and the courier's payout account. */
  const failures: Array<{ stopId: string; reason: string; note?: string }> = [];
  const payout = { enabled: !!options.payoutsEnabled, onboardingLinks: 0 };

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

  const devices = new Map<string, Json>();
  const feedback: Json[] = [];

  // S-159: northline-auth's JSON sign-in in the app's cookie session, and /oauth2/authorize answering on the https
  // redirect once signed in (the kit's AppSignIn hand-off)
  const auth = {
    saved: null as string | null,
    signedIn: false,
    password: null as null | { email: string; factors: string[] },
    reset: false,
    api: [] as Array<{ path: string; body: Json }>,
    factors: new Map<string, string[]>([[FIXTURE_COURIER.email, ['totp', 'backup_code']], [FIXTURE_NEW_COURIER.email, []]]),
  };
  const passwords = new Map<string, string>([[FIXTURE_COURIER.email, FIXTURE_COURIER.password], [FIXTURE_NEW_COURIER.email, FIXTURE_NEW_COURIER.password]]);
  const landed = (to: string) => {
    const res = new Response('<!doctype html><title>Northline</title>', { status: 200, headers: { 'content-type': 'text/html' } });
    Object.defineProperty(res, 'url', { value: to });
    return res;
  };
  const session = () => {
    auth.signedIn = true;
    const continueTo = auth.saved;
    auth.saved = null;
    return { user: { id: 'courier-1', firstName: 'Casey', lastName: 'Example' }, acr: 'mfa', continueTo };
  };
  function authAnswer(path: string, body: Json, u: URL): Response | undefined {
    if (path === '/oauth2/authorize') {
      if (!auth.signedIn) {
        auth.saved = u.toString();
        return landed('https://site.fixtures.invalid/sign-in');
      }
      return landed(`${u.searchParams.get('redirect_uri')}?code=fixture-code&state=${u.searchParams.get('state')}`);
    }
    if (!path.startsWith('/api/auth/')) return undefined;
    auth.api.push({ path, body });
    const invalid = (field: string, rule: string, message: string) => answer(422, { errors: [{ field, rule, message }] });
    const code = String(body.code ?? '').trim();
    switch (path) {
      case '/api/auth/sign-in/password': {
        const email = String(body.email ?? '').trim().toLowerCase();
        if (passwords.get(email) !== body.password) return invalid('password', 'mismatch', "That email and password don't match. Check them and try again.");
        const factors = auth.factors.get(email) ?? [];
        if (factors.length === 0) {
          auth.password = { email, factors };
          return answer(200, { step: 'enroll', session: { ...session(), acr: null, needsSecondFactor: true } });
        }
        auth.password = { email, factors };
        return answer(200, { step: 'factor', factors, email });
      }
      case '/api/auth/sign-in/totp':
        if (!auth.password) return answer(409, { code: 'flow_not_started' });
        return code === FIXTURE_TOTP ? answer(200, session()) : invalid('code', 'mismatch', "That code didn't work. Check it and try again.");
      case '/api/auth/sign-in/backup-code':
        if (!auth.password) return answer(409, { code: 'flow_not_started' });
        return code === FIXTURE_BACKUP ? answer(200, session()) : invalid('code', 'mismatch', "That code didn't work. Check it and try again.");
      case '/api/auth/enroll/totp':
        return answer(200, { secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/Northline:casey?secret=JBSWY3DPEHPK3PXP&issuer=Northline', qrCode: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=' });
      case '/api/auth/enroll/totp/verify':
        if (code !== FIXTURE_TOTP) return invalid('code', 'mismatch', "That code doesn't match. Check it and try again.");
        if (auth.password) auth.factors.set(auth.password.email, ['totp', 'backup_code']);
        return answer(200, { session: session(), backupCodes: [FIXTURE_BACKUP, 'code-01-fx', 'code-02-fx'] });
      case '/api/auth/password/forgot':
        auth.reset = true;
        return answer(202, { step: 'verify-email', email: String(body.email ?? ''), resendAfterSeconds: 60 });
      case '/api/auth/password/reset':
        if (!auth.reset || code !== FIXTURE_EMAIL_CODE) return invalid('code', 'mismatch', "That code doesn't match. Check it and try again.");
        auth.reset = false;
        return answer(200, { email: FIXTURE_COURIER.email });
    }
    return answer(404, { code: 'not_found' });
  }

  async function handle(url: string, init: RequestInit = {}): Promise<Response> {
    const method = (init.method ?? 'GET').toUpperCase();
    const u = new URL(url, 'http://fixtures.invalid');
    const path = u.pathname.replace(/^.*\/api\/v1/, '');
    const headers = (init.headers ?? {}) as Record<string, string>;
    calls.push({ method, path: u.pathname, idempotencyKey: headers['Idempotency-Key'] });
    if (u.pathname === '/oauth2/authorize' || u.pathname.startsWith('/api/auth/')) {
      const authBody = typeof init.body === 'string' ? (JSON.parse(init.body) as Json) : {};
      const handled = authAnswer(u.pathname, authBody, u);
      if (handled) return handled;
    }
    if (u.pathname.endsWith('/oauth2/token')) {
      issued++;
      return answer(200, { access_token: `fixture-access-${issued}`, token_type: 'DPoP', expires_in: 599, refresh_token: `fixture-refresh-${issued}` }, { 'DPoP-Nonce': `n${issued}` });
    }
    if (u.pathname.endsWith('/oauth2/revoke')) {
      auth.signedIn = false; // single sign-out (S-20): the app's auth session ends too
      return answer(200, {});
    }
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
    if (method === 'GET' && path === '/courier/earnings') {
      const totals = { payCents: 1600, tipsCents: 300, runs: 1, stops: 4 };
      return answer(200, {
        zone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        today: totals,
        week: { payCents: 4200, tipsCents: 700, runs: 3, stops: 11 },
        runs: [{ runId: 'run-0', label: 'R-699', earnedAt: iso(now() - 3 * 3600_000), payCents: 1600, tipsCents: 300, pickups: 2, dropoffs: 2, failed: 0, collects: 0, returns: 0 }],
        balanceCents: 1900,
        payoutAccount: payout.enabled ? { payoutsEnabled: true, requirementsDue: 0, bankLast4: '6789' } : payout.onboardingLinks ? { payoutsEnabled: false, requirementsDue: 2, bankLast4: null } : null,
        payouts: payout.enabled ? [{ id: 'po-1', amountCents: 2300, state: 'paid', createdAt: iso(now() - 6 * 86_400_000), arrivesAt: iso(now() - 5 * 86_400_000) }] : [],
      });
    }
    if (method === 'POST' && path === '/courier/payout-account/onboarding') {
      payout.onboardingLinks++;
      return answer(200, { url: 'https://connect.stripe.example/setup/fixture' });
    }
    const m = /^\/courier\/stops\/([^/]+)\/(arrive|pickup|proof|dropoff|refuse|returned|fail|collect)$/.exec(path);
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
      } else if (action === 'fail' && s.state !== 'done') {
        // S-151: after the door photo; the order goes back to the shops it came from
        if (s.kind !== 'dropoff') return conflict('not_a_dropoff', 'This stop is not on your run.');
        if (s.proofKind !== 'photo') return conflict('proof_missing', 'Take a photo at the door first.');
        if (body.reason === 'other' && !body.note) return answer(422, { errors: [{ field: 'note', message: 'Add a note saying what happened.' }] });
        failures.push({ stopId: s.id, reason: String(body.reason), ...(body.note ? { note: String(body.note) } : {}) });
        s.state = 'done';
        s.doneAt = iso(now());
        s.proofKind = 'failed';
        s.failReason = String(body.reason);
        const shop = run!.stops.find((p) => p.kind === 'pickup' && p.orderId === s.orderId)?.place ?? null;
        run!.stops.push(stop({ id: `st-r-${s.id}`, seq: run!.stops.length + 1, kind: 'return', orderId: s.orderId, orderRef: s.orderRef, place: shop }));
      } else if (action === 'collect' && s.state !== 'done') {
        if (s.kind !== 'collect') return conflict('not_a_collect', 'This stop is not on your run.');
        s.state = 'done';
        s.doneAt = iso(now());
      } else if (action === 'returned' && s.state !== 'done') {
        if (s.kind !== 'return') return conflict('not_a_return', 'This stop is not on your run.');
        s.state = 'done';
        s.doneAt = iso(now());
        s.proofKind = 'id_refused';
      }
      settle();
      return current();
    }
    // mobile gaps part 1: push installations (S-102) and pilot feedback from the courier app (S-121)
    const device = /^\/me\/devices\/([^/]+)$/.exec(path);
    if (device && method === 'PUT') {
      devices.set(device[1]!, body);
      return answer(200, { installationId: device[1], ...body });
    }
    if (device && method === 'DELETE') {
      devices.delete(device[1]!);
      return answer(204);
    }
    if (method === 'GET' && path === '/me/pilot') {
      const courierApp = u.searchParams.get('app') === 'courier';
      return answer(200, { participant: !!options.pilot && courierApp, persona: options.pilot && courierApp ? 'courier' : null, screenshotMaxBytes: 5242880, screenshotTypes: ['image/jpeg', 'image/png'] });
    }
    if (method === 'POST' && path === '/me/pilot/screenshots') {
      if (!options.pilot) return answer(403, { code: 'forbidden', detail: 'Feedback here is for pilot participants.' });
      feedback.push({ screenshot: true });
      return answer(201, { id: `shot-${feedback.length}`, contentType: 'image/png', size: 1000 });
    }
    if (method === 'POST' && path === '/me/pilot/feedback') {
      if (!options.pilot) return answer(403, { code: 'forbidden', detail: 'Feedback here is for pilot participants.' });
      feedback.push(body);
      return answer(201, { id: 'fb-1', reference: 'UAT-1101' });
    }
    return answer(404, { code: 'not_found' });
  }

  const fetchImpl = ((input: RequestInfo | URL, init?: RequestInit) => handle(String(input), init)) as typeof fetch;
  return {
    fetch: fetchImpl,
    auth,
    pings,
    calls,
    devices,
    feedback,
    idChecks,
    failures,
    payout,
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
