import { useSyncExternalStore } from 'react';

const never = () => () => {};

/**
 * S-180: false in the server's render and in the browser's hydration render, true after — React reads the server
 * snapshot while it hydrates, so what a component draws from browser-only state (the session) matches the server's HTML.
 * The flaky React error 418 on the booking wizard: the session is fetched in the browser only, and when its answer arrived before
 * the wizard's streamed part hydrated, that part's first render showed the signed-in photo picker the server had not
 * drawn. A plain client render (tests, later navigations) is true from the start.
 */
export function useHydrated(): boolean {
  return useSyncExternalStore(never, () => true, () => false);
}
