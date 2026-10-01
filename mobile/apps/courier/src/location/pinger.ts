import { ApiError } from '@northline/mobile-kit';

import type { Ping, Run } from '../api/courier';
import { PING_INTERVAL_MS } from '../config';

export interface Position {
  lat: number;
  lng: number;
  heading?: number | null;
  /** Epoch ms of the fix. */
  at: number;
}

export interface PingerDeps {
  ping(lat: number, lng: number, heading?: number | null): Promise<Ping>;
  /** The open run, or null: pings go only while there is one. */
  run(): Promise<Run | null>;
  /** The run ended or the shift is off: stop the location updates. */
  stop(): void;
  now?(): number;
}

/** A fix older than this is not worth sending: customers would see a stale position as live. */
const MAX_AGE_MS = 30_000;
/** How often the pinger re-reads whether a run is still open (the background task has no screen to tell it). */
const RUN_CHECK_MS = 60_000;

/**
 * Sends the courier's latest position while a run is open (S-88: latest position only, never a history).
 *
 * - At most one ping per {@link PING_INTERVAL_MS}, or later if the api says so (`nextAfterMs`, or 429 Retry-After).
 * - Fixes that arrive in between are not queued: only the newest is ever sent, and a failed ping is not retried
 *   (the next fix replaces it). Nothing about positions is stored on the phone.
 * - No run any more (204) or not on shift (409 `not_on_shift`): stops the updates.
 */
export class Pinger {
  private nextAt = 0;
  private sending = false;
  private runCheckedAt = 0;
  private stopped = false;

  constructor(private readonly deps: PingerDeps) {}

  private now() {
    return this.deps.now?.() ?? Date.now();
  }

  /** Marks the run as just seen open (the screen knows), so the next ping needs no extra check. */
  runSeen() {
    this.runCheckedAt = this.now();
    this.stopped = false;
  }

  /** A batch of fixes from the OS: the newest may be sent. Resolves with what happened, for tests and logs. */
  async offer(fixes: readonly Position[]): Promise<'sent' | 'later' | 'stale' | 'stopped' | 'failed' | 'busy'> {
    if (this.stopped) return 'stopped';
    const latest = [...fixes].sort((a, b) => b.at - a.at)[0];
    if (!latest) return 'later';
    const now = this.now();
    if (now - latest.at > MAX_AGE_MS) return 'stale';
    if (now < this.nextAt) return 'later';
    if (this.sending) return 'busy';
    this.sending = true;
    try {
      if (now - this.runCheckedAt > RUN_CHECK_MS) {
        const run = await this.deps.run();
        this.runCheckedAt = now;
        if (!run || run.state === 'done') return this.halt();
      }
      const answer = await this.deps.ping(latest.lat, latest.lng, latest.heading);
      this.nextAt = now + Math.max(PING_INTERVAL_MS, answer.nextAfterMs ?? 0);
      return 'sent';
    } catch (e) {
      if (e instanceof ApiError && e.status === 429) {
        this.nextAt = now + Math.max(1, e.retryAfter ?? 2) * 1000;
        return 'later';
      }
      if (e instanceof ApiError && e.status === 409) return this.halt();
      this.nextAt = now + PING_INTERVAL_MS;
      return 'failed';
    } finally {
      this.sending = false;
    }
  }

  private halt(): 'stopped' {
    this.stopped = true;
    this.deps.stop();
    return 'stopped';
  }
}
