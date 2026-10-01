import { useEffect, useSyncExternalStore } from 'react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';

/**
 * S-68: the Studio's live stream — one `EventSource` per tab on `GET /api/v1/merchants/{id}/live` (through the BFF,
 * session cookie). Each event names what changed (`message` + thread id, `kitchen`, `orders`); the matching queries are
 * invalidated and refetch with the caller's own permissions, so a new message or order shows within about a second.
 *
 * Fallback: while the stream is not open (not yet connected, dropped, refused, or a browser without EventSource) the
 * screens poll at their old intervals; while it is open they keep only a slow safety refresh ({@link LIVE_SAFETY_MS})
 * for changes that send no signal (another member reading a thread, a courier being assigned).
 */

export const LIVE_SAFETY_MS = 60_000;
/** After the server closed the stream for good (refused, endpoint missing), try again this much later. */
export const LIVE_RETRY_MS = 30_000;

type Listener = () => void;
let connected = false;
const listeners = new Set<Listener>();

function setConnected(next: boolean) {
  if (connected === next) return;
  connected = next;
  listeners.forEach(l => l());
}

const subscribe = (l: Listener) => {
  listeners.add(l);
  return () => listeners.delete(l);
};

/** Whether the live stream is open right now. */
export const useLiveConnected = () => useSyncExternalStore(subscribe, () => connected, () => false);

/** A screen's `refetchInterval`: `pollMs` while the stream is down, the slow safety refresh while it is up. */
export function useLivePoll(pollMs: number): number {
  return useLiveConnected() ? Math.max(pollMs, LIVE_SAFETY_MS) : pollMs;
}

const key = (merchantId: string, ...rest: string[]) => ['merchant', merchantId, ...rest];

/** What each event refreshes. Keys follow the features' query keys (messages, help, kitchen, orders, nav badges). */
export function applyLiveEvent(qc: QueryClient, merchantId: string, event: string, ref: string | null) {
  const refresh = (...k: string[]) => void qc.invalidateQueries({ queryKey: key(merchantId, ...k) });
  switch (event) {
    case 'message':
      refresh('threads'); // the list and, by prefix, the open thread (…, 'threads', id, locale)
      if (ref) refresh('threads', ref);
      refresh('help', 'cases'); // help cases carry messages too
      refresh('nav-badges');
      break;
    case 'kitchen':
      refresh('kitchen', 'live');
      refresh('nav-badges');
      break;
    case 'orders':
      refresh('orders');
      refresh('nav-badges');
      refresh('dashboard');
      break;
  }
}

/** Opens the stream for the business while the Studio shell is mounted; closes it on unmount or business switch. */
export function useStudioLive(merchantId: string) {
  const qc = useQueryClient();
  useEffect(() => {
    if (typeof EventSource === 'undefined') return;
    let source: EventSource | null = null;
    let retry: ReturnType<typeof setTimeout> | undefined;
    let stopped = false;
    let opened = false;
    const open = () => {
      source = new EventSource(`/api/v1/merchants/${merchantId}/live`, { withCredentials: true });
      source.addEventListener('ready', () => {
        setConnected(true);
        if (!opened) {
          opened = true;
          return;
        }
        // a reconnect may have missed signals while it was down: catch up once, then rely on the stream again
        applyLiveEvent(qc, merchantId, 'message', null);
        applyLiveEvent(qc, merchantId, 'kitchen', null);
        applyLiveEvent(qc, merchantId, 'orders', null);
      });
      for (const event of ['message', 'kitchen', 'orders']) {
        source.addEventListener(event, e => applyLiveEvent(qc, merchantId, event, refOf((e as MessageEvent<string>).data)));
      }
      source.onerror = () => {
        setConnected(false); // poll until "ready" comes again
        if (source?.readyState === EventSource.CLOSED && !stopped) {
          source.close();
          retry = setTimeout(open, LIVE_RETRY_MS);
        }
      };
    };
    open();
    return () => {
      stopped = true;
      clearTimeout(retry);
      source?.close();
      setConnected(false);
    };
  }, [merchantId, qc]);
}

function refOf(data: string): string | null {
  try {
    const parsed: unknown = JSON.parse(data);
    return parsed && typeof parsed === 'object' && 'ref' in parsed && typeof parsed.ref === 'string' ? parsed.ref : null;
  } catch {
    return null;
  }
}
