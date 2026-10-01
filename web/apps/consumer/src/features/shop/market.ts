import { useEffect } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { useDeliveryLocation } from '../location/useDeliveryLocation';

/**
 * The Shop pages are rendered for the market in the URL (`?market=`; none = the api's fallback market, `rendered`) —
 * the same HTML for everyone.
 * Once the browser knows the visitor's location (saved, device, IP), a page without an explicit market switches to
 * the visitor's city (replacing the history entry). A market in the URL is kept (a shared link).
 */
export function useMarketFollowsLocation(explicit: string | undefined, rendered: string) {
  const { location } = useDeliveryLocation();
  const navigate = useNavigate();
  const city = location.status === 'locating' ? undefined : location.city;
  useEffect(() => {
    if (explicit || !city || city.toLowerCase() === rendered.toLowerCase()) return;
    void navigate({ to: '.', search: ((prev: Record<string, unknown>) => ({ ...prev, market: city })) as never, replace: true, resetScroll: false });
  }, [explicit, city, rendered, navigate]);
}

/** What the page says it delivers to: the visitor's own label when they're in this market, else the market. */
export function useArea(market: string) {
  const { location } = useDeliveryLocation();
  return location.status !== 'locating' && location.label && location.city?.toLowerCase() === market.toLowerCase() ? location.label : market;
}

/** Keeps an explicit `?market=` on links between Shop pages. */
export const withMarket = (path: string, market: string | undefined) =>
  market ? `${path}${path.includes('?') ? '&' : '?'}market=${encodeURIComponent(market)}` : path;
