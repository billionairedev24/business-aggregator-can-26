import { ApiError } from '@northline/mobile-kit';

import type { Run } from '../src/api/courier';
import { Pinger, type Position } from '../src/location/pinger';

const OPEN = { state: 'en_route' } as Run;

function setup(answers: { ping?: () => Promise<{ acceptedAt: string; nextAfterMs: number }>; run?: () => Promise<Run | null> } = {}) {
  const clock = { t: 1_000_000 };
  const pings: Array<[number, number]> = [];
  const stop = jest.fn();
  const pinger = new Pinger({
    now: () => clock.t,
    ping: async (lat, lng) => {
      pings.push([lat, lng]);
      return answers.ping ? answers.ping() : { acceptedAt: '', nextAfterMs: 2000 };
    },
    run: answers.run ?? (async () => OPEN),
    stop,
  });
  const fix = (lat: number, at = clock.t): Position => ({ lat, lng: -lat, at });
  return { clock, pings, stop, pinger, fix };
}

describe('location pings', () => {
  it('sends the newest fix, at most every 4 s, and nothing in between', async () => {
    const { clock, pings, pinger, fix } = setup();
    pinger.runSeen();
    expect(await pinger.offer([fix(1, clock.t - 2000), fix(2)])).toBe('sent');
    clock.t += 3000;
    expect(await pinger.offer([fix(3)])).toBe('later');
    clock.t += 1000;
    expect(await pinger.offer([fix(4)])).toBe('sent');
    expect(pings).toEqual([
      [2, -2],
      [4, -4],
    ]);
  });

  it('backs off as long as Retry-After says on a 429', async () => {
    let first = true;
    const { clock, pinger, fix, pings } = setup({
      ping: async () => {
        if (first) {
          first = false;
          throw new ApiError(429, 'too_many_pings', undefined, [], 6);
        }
        return { acceptedAt: '', nextAfterMs: 2000 };
      },
    });
    pinger.runSeen();
    expect(await pinger.offer([fix(1)])).toBe('later');
    clock.t += 5000;
    expect(await pinger.offer([fix(2)])).toBe('later');
    clock.t += 1000;
    expect(await pinger.offer([fix(3)])).toBe('sent');
    expect(pings).toHaveLength(2);
  });

  it('never sends an old fix as if it were live', async () => {
    const { clock, pinger, fix, pings } = setup();
    pinger.runSeen();
    expect(await pinger.offer([fix(1, clock.t - 60_000)])).toBe('stale');
    expect(pings).toEqual([]);
  });

  it('stops when the run is over (204) — pings go only while on a run', async () => {
    const { clock, pinger, fix, stop, pings } = setup({ run: async () => null });
    clock.t += 120_000; // the last check is long ago: the background task asks again
    expect(await pinger.offer([fix(1)])).toBe('stopped');
    expect(stop).toHaveBeenCalled();
    expect(pings).toEqual([]);
    expect(await pinger.offer([fix(2)])).toBe('stopped');
  });

  it('stops when the api says the courier is not on shift (409)', async () => {
    const { pinger, fix, stop } = setup({
      ping: async () => {
        throw new ApiError(409, 'not_on_shift', 'Start your shift first.');
      },
    });
    pinger.runSeen();
    expect(await pinger.offer([fix(1)])).toBe('stopped');
    expect(stop).toHaveBeenCalled();
  });

  it('drops a fix whose ping failed (no position is kept on the phone)', async () => {
    let fail = true;
    const { clock, pinger, fix, pings } = setup({
      ping: async () => {
        if (fail) throw new TypeError('Network request failed');
        return { acceptedAt: '', nextAfterMs: 0 };
      },
    });
    pinger.runSeen();
    expect(await pinger.offer([fix(1)])).toBe('failed');
    fail = false;
    clock.t += 4000;
    expect(await pinger.offer([fix(2)])).toBe('sent');
    expect(pings.map((p) => p[0])).toEqual([1, 2]);
  });
});
