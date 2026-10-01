import { act, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LIVE_RETRY_MS, LIVE_SAFETY_MS, useLivePoll, useStudioLive } from './live';

/** A minimal EventSource: the test plays the server. */
class FakeEventSource {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;
  static all: FakeEventSource[] = [];
  readyState = FakeEventSource.CONNECTING;
  onerror: (() => void) | null = null;
  closed = false;
  private handlers = new Map<string, ((e: MessageEvent<string>) => void)[]>();
  constructor(readonly url: string, readonly init?: EventSourceInit) { FakeEventSource.all.push(this); }
  addEventListener(name: string, fn: (e: MessageEvent<string>) => void) { this.handlers.set(name, [...(this.handlers.get(name) ?? []), fn]); }
  close() { this.closed = true; this.readyState = FakeEventSource.CLOSED; }
  emit(name: string, data = '{"ref":null}') {
    this.readyState = FakeEventSource.OPEN;
    act(() => this.handlers.get(name)?.forEach(fn => fn(new MessageEvent(name, { data }))));
  }
  fail(state: number) { this.readyState = state; act(() => this.onerror?.()); }
}

function Probe({ merchantId }: { merchantId: string }) {
  useStudioLive(merchantId);
  return <span data-testid="poll">{useLivePoll(15_000)}</span>;
}

function setup() {
  const qc = new QueryClient();
  const invalidate = vi.spyOn(qc, 'invalidateQueries');
  const view = render(<QueryClientProvider client={qc}><Probe merchantId="M1" /></QueryClientProvider>);
  return { qc, invalidate, view, poll: () => Number(screen.getByTestId('poll').textContent) };
}
const keys = (spy: { mock: { calls: unknown[][] } }) => spy.mock.calls.map(c => JSON.stringify((c[0] as { queryKey: unknown }).queryKey));

describe('useStudioLive (S-68)', () => {
  beforeEach(() => { FakeEventSource.all = []; vi.stubGlobal('EventSource', FakeEventSource); });
  afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

  it('opens one stream with the session cookie and polls until it is ready', () => {
    const { poll } = setup();
    expect(FakeEventSource.all).toHaveLength(1);
    expect(FakeEventSource.all[0]!.url).toBe('/api/v1/merchants/M1/live');
    expect(FakeEventSource.all[0]!.init).toEqual({ withCredentials: true });
    expect(poll()).toBe(15_000);
    FakeEventSource.all[0]!.emit('ready', '{}');
    expect(poll()).toBe(LIVE_SAFETY_MS);
  });

  it('refreshes the screen each event names', () => {
    const { invalidate } = setup();
    const es = FakeEventSource.all[0]!;
    es.emit('ready', '{}');
    expect(invalidate).not.toHaveBeenCalled(); // the first open needs no catch-up
    es.emit('message', '{"ref":"T1"}');
    expect(keys(invalidate)).toEqual(expect.arrayContaining(['["merchant","M1","threads"]', '["merchant","M1","threads","T1"]', '["merchant","M1","nav-badges"]', '["merchant","M1","help","cases"]']));
    invalidate.mockClear();
    es.emit('kitchen');
    expect(keys(invalidate)).toContain('["merchant","M1","kitchen","live"]');
    invalidate.mockClear();
    es.emit('orders', '{"ref":"O1"}');
    expect(keys(invalidate)).toContain('["merchant","M1","orders"]');
  });

  it('falls back to polling when the stream drops, and catches up when it is back', () => {
    const { invalidate, poll } = setup();
    const es = FakeEventSource.all[0]!;
    es.emit('ready', '{}');
    es.fail(FakeEventSource.CONNECTING); // the browser reconnects by itself
    expect(poll()).toBe(15_000);
    es.emit('ready', '{}');
    expect(poll()).toBe(LIVE_SAFETY_MS);
    expect(keys(invalidate)).toEqual(expect.arrayContaining(['["merchant","M1","threads"]', '["merchant","M1","kitchen","live"]', '["merchant","M1","orders"]']));
  });

  it('keeps polling when the stream is refused and tries again later', () => {
    vi.useFakeTimers();
    const { poll } = setup();
    FakeEventSource.all[0]!.fail(FakeEventSource.CLOSED);
    expect(poll()).toBe(15_000);
    act(() => { vi.advanceTimersByTime(LIVE_RETRY_MS); });
    expect(FakeEventSource.all).toHaveLength(2);
  });

  it('closes the stream when the Studio unmounts', () => {
    const { view } = setup();
    view.unmount();
    expect(FakeEventSource.all[0]!.closed).toBe(true);
  });
});
