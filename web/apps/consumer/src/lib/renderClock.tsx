import { createContext, useContext, type ReactNode } from 'react';

/**
 * S-182: one clock for the server's render and the browser's first render. Time-based text ("last week", "Today 3 pm",
 * "tonight", whether a review can still be edited) rendered from `Date.now()` on each side can differ — a request that
 * straddles a minute, an hour or midnight — and React then throws the server's HTML away (#418). The root route's
 * loader takes the time once on the server (`renderedAt`); it travels to the browser with the rest of the loader data
 * and every screen reads it here instead of the system clock. A later navigation in the browser runs the loader
 * again, so the clock moves on with the visit. Outside the app (component tests), it is the system clock.
 */
const RenderClock = createContext<number | null>(null);

export function RenderClockProvider({ at, children }: { at: number | undefined; children: ReactNode }) {
  return <RenderClock.Provider value={at ?? null}>{children}</RenderClock.Provider>;
}

/** The time this page was rendered at (the same on the server and in the browser's first render). */
export function useRenderNow(): Date {
  const at = useContext(RenderClock);
  return new Date(at ?? Date.now());
}
