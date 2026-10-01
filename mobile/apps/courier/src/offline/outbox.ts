import { ApiError, NetworkError, SignedOutError } from '@northline/mobile-kit';

import type { ProofFile, ProofKind, Run } from '../api/courier';

/**
 * The courier's stop actions, kept on the phone until the api has them (S-87: offline-tolerant).
 *
 * - Actions are sent one at a time, in the order they were made, each with its own Idempotency-Key that every retry
 *   repeats. The api's stop actions are idempotent by state (a done stop answers the run, also after the run ended), so
 *   a retry whose first answer was lost changes nothing.
 * - No answer (offline, time-out), 408/429/5xx: kept, retried with back-off (Retry-After when given), and at once when
 *   the phone is back online.
 * - A refusal (409/422/404): the action and the later ones for the same stop are dropped, and the person sees why
 *   (a wrong PIN, a shop that hasn't packed). A proof upload refused with `stop_done` was already used: success.
 * - The sign-in ended: kept, paused until the courier signs in again.
 * - Only what the api needs is stored: stop ids, the proof kind, the PIN until it is sent, and the proof file's path.
 *   No address or name. Cleared at sign-out.
 */
export type OutboxAction =
  | { id: string; kind: 'arrive'; stopId: string; createdAt: number; attempts: number }
  | { id: string; kind: 'pickup'; stopId: string; scanOk: boolean; createdAt: number; attempts: number }
  | { id: string; kind: 'proof'; stopId: string; proofKind: 'photo' | 'signature'; file: ProofFile; createdAt: number; attempts: number }
  | { id: string; kind: 'dropoff'; stopId: string; proof: ProofKind; pin?: string; createdAt: number; attempts: number };

/** What enqueue takes: the outbox adds id, createdAt and attempts. */
export type NewAction = OutboxAction extends infer A ? (A extends OutboxAction ? Omit<A, 'id' | 'createdAt' | 'attempts'> : never) : never;

export interface Failure {
  action: OutboxAction;
  message: string;
  code?: string;
}

export interface OutboxState {
  pending: OutboxAction[];
  failures: Failure[];
  /** Waiting for a retry until this time (epoch ms); null when sending or idle. */
  retryAt: number | null;
  /** The sign-in ended with actions still pending. */
  needsSignIn: boolean;
}

export interface KeyValueStore {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
}

export interface OutboxDeps {
  store: KeyValueStore;
  send(action: OutboxAction): Promise<Run | null>;
  /** The api answered with the run: the screens update from it. */
  onRun(run: Run | null): void;
  /** An action left the outbox (sent or dropped): delete its proof file. */
  release?(action: OutboxAction): void;
  newId(): string;
  now?(): number;
}

const STORE_KEY = 'nl.courier.outbox.v1';
const MAX_BACKOFF_MS = 60_000;

export class Outbox {
  private state: OutboxState = { pending: [], failures: [], retryAt: null, needsSignIn: false };
  private listeners = new Set<(s: OutboxState) => void>();
  private current: Promise<void> | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private loaded: Promise<void> | null = null;

  constructor(private readonly deps: OutboxDeps) {}

  private now() {
    return this.deps.now?.() ?? Date.now();
  }

  /** Reads what a previous run of the app left, then sends it. */
  load(): Promise<void> {
    this.loaded ??= (async () => {
      try {
        const raw = await this.deps.store.getItem(STORE_KEY);
        const saved = raw ? (JSON.parse(raw) as OutboxAction[]) : [];
        this.set({ pending: [...saved, ...this.state.pending] });
      } catch {
        // unreadable: start empty
      }
    })();
    return this.loaded;
  }

  get snapshot(): OutboxState {
    return this.state;
  }

  subscribe(listener: (s: OutboxState) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private set(patch: Partial<OutboxState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((l) => l(this.state));
  }

  private async persist() {
    await this.deps.store.setItem(STORE_KEY, JSON.stringify(this.state.pending));
  }

  /** Adds actions (several at once stay together: a proof upload and its drop-off), then sends. */
  async enqueue(...actions: NewAction[]): Promise<OutboxAction[]> {
    await this.load();
    const full = actions.map((a) => ({ ...a, id: this.deps.newId(), createdAt: this.now(), attempts: 0 }) as OutboxAction);
    this.set({ pending: [...this.state.pending, ...full] });
    await this.persist();
    void this.flush();
    return full;
  }

  dismissFailure(actionId: string) {
    this.set({ failures: this.state.failures.filter((f) => f.action.id !== actionId) });
  }

  /** Connectivity came back, or the courier signed in again: send now. */
  wake() {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.set({ retryAt: null, needsSignIn: false });
    void this.flush();
  }

  /** Sign-out: forget everything (the caller warned about unsent actions). */
  async clear() {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.state.pending.forEach((a) => this.deps.release?.(a));
    this.set({ pending: [], failures: [], retryAt: null, needsSignIn: false });
    await this.deps.store.removeItem(STORE_KEY);
  }

  /** Sends the pending actions in order until one has to wait. Resolves when it stops (one sender at a time). */
  flush(): Promise<void> {
    this.current ??= this.send().finally(() => {
      this.current = null;
    });
    return this.current;
  }

  private async send(): Promise<void> {
    await this.load();
    if (this.timer || this.state.needsSignIn) return;
    while (this.state.pending.length > 0) {
      const outcome = await this.attempt(this.state.pending[0]!);
      if (outcome === 'wait') return;
    }
  }

  private async attempt(action: OutboxAction): Promise<'next' | 'wait'> {
    try {
      const run = await this.deps.send(action);
      this.deps.onRun(run);
      await this.remove([action]);
      return 'next';
    } catch (e) {
      if (e instanceof ApiError && action.kind === 'proof' && e.status === 409 && e.code === 'stop_done') {
        await this.remove([action]); // the drop-off already happened with this proof
        return 'next';
      }
      if (e instanceof SignedOutError) {
        this.set({ needsSignIn: true });
        return 'wait';
      }
      if (e instanceof NetworkError || (e instanceof ApiError && e.transient)) {
        const attempts = action.attempts + 1;
        this.set({ pending: this.state.pending.map((a) => (a.id === action.id ? { ...a, attempts } : a)) });
        await this.persist();
        const backoff = Math.min(MAX_BACKOFF_MS, 1000 * 2 ** Math.min(attempts - 1, 6));
        const delay = e instanceof ApiError && e.retryAfter !== undefined ? Math.max(e.retryAfter * 1000, 1000) : backoff;
        this.set({ retryAt: this.now() + delay });
        this.timer = setTimeout(() => {
          this.timer = null;
          this.set({ retryAt: null });
          void this.flush();
        }, delay);
        return 'wait';
      }
      // refused: drop it and what depends on it (later actions for the same stop)
      const dropped = this.state.pending.filter((a) => a.id === action.id || (a.stopId === action.stopId && a.createdAt >= action.createdAt));
      const message = e instanceof Error ? e.message : String(e);
      const code = e instanceof ApiError ? e.code : undefined;
      this.set({ failures: [...this.state.failures, { action, message, code }] });
      await this.remove(dropped);
      return 'next';
    }
  }

  private async remove(actions: OutboxAction[]) {
    const ids = new Set(actions.map((a) => a.id));
    actions.forEach((a) => this.deps.release?.(a));
    this.set({ pending: this.state.pending.filter((a) => !ids.has(a.id)) });
    await this.persist();
  }
}

/**
 * The run as the courier sees it: what the api answered plus what is still on the phone (an arrival, a pickup or a
 * drop-off that has not reached the api yet looks done, marked "saved on this phone").
 */
export function withPending(run: Run | null | undefined, pending: readonly OutboxAction[]): { run: Run | null; unsent: Set<string> } {
  if (!run) return { run: null, unsent: new Set() };
  const unsent = new Set<string>();
  const stops = run.stops.map((s) => {
    const mine = pending.filter((a) => a.stopId === s.id);
    if (mine.length === 0 || s.state === 'done') return s;
    unsent.add(s.id);
    if (mine.some((a) => a.kind === 'pickup' || a.kind === 'dropoff')) return { ...s, state: 'done' as const, arrivedAt: s.arrivedAt ?? new Date(mine[0]!.createdAt).toISOString() };
    if (mine.some((a) => a.kind === 'arrive')) return { ...s, state: 'arrived' as const, arrivedAt: s.arrivedAt ?? new Date(mine[0]!.createdAt).toISOString() };
    return s;
  });
  return { run: { ...run, stops }, unsent };
}
