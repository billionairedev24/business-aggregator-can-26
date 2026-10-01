import { ApiClient, ApiError, DpopSession, NetworkError, SignedOutError, memorySecureStorage } from '@northline/mobile-kit';

import { CourierApi, type Run } from '../src/api/courier';
import { createFixtureServer } from '../src/fixtures/server';
import { Outbox, withPending, type KeyValueStore, type OutboxAction } from '../src/offline/outbox';

function memoryStore(): KeyValueStore & { data: Map<string, string> } {
  const data = new Map<string, string>();
  return {
    data,
    getItem: async (k) => data.get(k) ?? null,
    setItem: async (k, v) => void data.set(k, v),
    removeItem: async (k) => void data.delete(k),
  };
}

const RUN = { id: 'r', state: 'loading', stops: [] } as unknown as Run;
let n = 0;
const newId = () => `act-${++n}`;

describe('the outbox', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('sends actions in order, each once, and hands the run on', async () => {
    const sent: OutboxAction[] = [];
    const runs: Array<Run | null> = [];
    const outbox = new Outbox({ store: memoryStore(), newId, send: async (a) => (sent.push(a), RUN), onRun: (r) => runs.push(r) });
    await outbox.enqueue({ kind: 'arrive', stopId: 's1' });
    await outbox.enqueue({ kind: 'pickup', stopId: 's1', scanOk: true });
    await outbox.flush();
    expect(sent.map((a) => a.kind)).toEqual(['arrive', 'pickup']);
    expect(runs).toHaveLength(2);
    expect(outbox.snapshot.pending).toEqual([]);
  });

  it('keeps an action without an answer and retries it with the same idempotency key, with back-off', async () => {
    const keys: string[] = [];
    let offline = true;
    const store = memoryStore();
    const outbox = new Outbox({
      store,
      newId,
      send: async (a) => {
        keys.push(a.id);
        if (offline) throw new NetworkError(new TypeError('Network request failed'));
        return RUN;
      },
      onRun: () => undefined,
    });
    await outbox.enqueue({ kind: 'dropoff', stopId: 's3', proof: 'pin', pin: '4821' });
    await outbox.flush();
    expect(outbox.snapshot.pending).toHaveLength(1);
    expect(outbox.snapshot.retryAt).not.toBeNull();
    expect(JSON.parse(store.data.get('nl.courier.outbox.v1')!)[0].attempts).toBe(1);

    await jest.advanceTimersByTimeAsync(1000); // first back-off: 1 s
    expect(keys).toHaveLength(2);
    offline = false;
    await jest.advanceTimersByTimeAsync(2000); // second: 2 s
    expect(keys).toHaveLength(3);
    expect(new Set(keys).size).toBe(1);
    expect(outbox.snapshot.pending).toEqual([]);
    expect(store.data.get('nl.courier.outbox.v1')).toBe('[]');
  });

  it('waits as long as Retry-After says on a 429 or 503', async () => {
    let calls = 0;
    const outbox = new Outbox({
      store: memoryStore(),
      newId,
      send: async () => {
        if (++calls === 1) throw new ApiError(503, undefined, 'down', [], 10);
        return RUN;
      },
      onRun: () => undefined,
    });
    await outbox.enqueue({ kind: 'arrive', stopId: 's1' });
    await outbox.flush();
    await jest.advanceTimersByTimeAsync(9000);
    expect(calls).toBe(1);
    await jest.advanceTimersByTimeAsync(1000);
    expect(calls).toBe(2);
  });

  it('drops a refused action and the later ones for the same stop, and says why', async () => {
    const sent: string[] = [];
    const released: string[] = [];
    const outbox = new Outbox({
      store: memoryStore(),
      newId,
      send: async (a) => {
        sent.push(`${a.kind}:${a.stopId}`);
        if (a.kind === 'proof') throw new ApiError(422, undefined, undefined, [{ message: 'Upload a JPG, PNG or WebP image under 5 MB.' }]);
        return RUN;
      },
      onRun: () => undefined,
      release: (a) => released.push(a.kind),
    });
    await outbox.enqueue(
      { kind: 'proof', stopId: 's3', proofKind: 'photo', file: { uri: 'file:///p.jpg', type: 'image/jpeg', name: 'p.jpg' } },
      { kind: 'dropoff', stopId: 's3', proof: 'photo' },
    );
    await outbox.enqueue({ kind: 'arrive', stopId: 's4' });
    await outbox.flush();
    expect(sent).toEqual(['proof:s3', 'arrive:s4']);
    expect(released).toEqual(expect.arrayContaining(['proof', 'dropoff']));
    expect(outbox.snapshot.failures.map((f) => f.message)).toEqual(['Upload a JPG, PNG or WebP image under 5 MB.']);
    outbox.dismissFailure(outbox.snapshot.failures[0]!.action.id);
    expect(outbox.snapshot.failures).toEqual([]);
  });

  it('treats a proof replayed after the drop-off (409 stop_done) as sent', async () => {
    const outbox = new Outbox({
      store: memoryStore(),
      newId,
      send: async () => {
        throw new ApiError(409, 'stop_done', 'This stop is already done.');
      },
      onRun: () => undefined,
    });
    await outbox.enqueue({ kind: 'proof', stopId: 's3', proofKind: 'signature', file: { uri: 'file:///s.png', type: 'image/png', name: 's.png' } });
    await outbox.flush();
    expect(outbox.snapshot.pending).toEqual([]);
    expect(outbox.snapshot.failures).toEqual([]);
  });

  it('pauses when the sign-in ended and resumes when woken', async () => {
    let signedIn = false;
    const outbox = new Outbox({
      store: memoryStore(),
      newId,
      send: async () => {
        if (!signedIn) throw new SignedOutError();
        return RUN;
      },
      onRun: () => undefined,
    });
    await outbox.enqueue({ kind: 'arrive', stopId: 's1' });
    await outbox.flush();
    expect(outbox.snapshot.needsSignIn).toBe(true);
    expect(outbox.snapshot.pending).toHaveLength(1);
    signedIn = true;
    outbox.wake();
    await outbox.flush();
    expect(outbox.snapshot.pending).toEqual([]);
  });

  it('survives a restart of the app', async () => {
    const store = memoryStore();
    const first = new Outbox({ store, newId, send: async () => Promise.reject(new NetworkError()), onRun: () => undefined });
    await first.enqueue({ kind: 'arrive', stopId: 's1' });
    const sent: string[] = [];
    const second = new Outbox({ store, newId, send: async (a) => (sent.push(a.id), RUN), onRun: () => undefined });
    await second.load();
    await second.flush();
    expect(sent).toHaveLength(1);
    await second.clear();
    expect(store.data.has('nl.courier.outbox.v1')).toBe(false);
  });

  it('runs against the api stand-in: replays change nothing', async () => {
    jest.useRealTimers();
    const server = createFixtureServer();
    const session = new DpopSession({ issuer: 'http://auth', clientId: 'courier-app', redirectUri: 'x:/r', scopes: [] }, memorySecureStorage(), server.fetch);
    const p = session.beginSignIn();
    await session.completeSignIn(`x:/r?code=c&state=${p.state}`, p);
    const api = new CourierApi(new ApiClient('http://api/api/v1', session, () => 'en', server.fetch), server.fetch);
    const first = await api.pickup('st-1', true, 'k1');
    const again = await api.pickup('st-1', true, 'k1');
    expect(again.stops.find((s) => s.id === 'st-1')?.doneAt).toBe(first.stops.find((s) => s.id === 'st-1')?.doneAt);
    expect(server.calls.filter((c) => c.idempotencyKey === 'k1')).toHaveLength(2);
  });
});

describe('the run as the courier sees it', () => {
  const run = createFixtureServer().run!;
  it('shows actions still on the phone as done (or arrived), marked unsent', () => {
    const pending = [
      { id: 'a', kind: 'arrive', stopId: 'st-1', createdAt: 1, attempts: 0 },
      { id: 'b', kind: 'dropoff', stopId: 'st-3', proof: 'pin', pin: '1', createdAt: 2, attempts: 0 },
    ] as OutboxAction[];
    const { run: seen, unsent } = withPending(run, pending);
    expect(seen!.stops.find((s) => s.id === 'st-1')!.state).toBe('arrived');
    expect(seen!.stops.find((s) => s.id === 'st-3')!.state).toBe('done');
    expect(seen!.stops.find((s) => s.id === 'st-2')!.state).toBe('pending');
    expect([...unsent]).toEqual(['st-1', 'st-3']);
    expect(withPending(null, pending).run).toBeNull();
  });
});
