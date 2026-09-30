/**
 * Live markets the header can place someone in without the api (design 06, Location screen: "In Alberta: Calgary,
 * Edmonton and Airdrie are live"). Coordinates are city centres. The Location screen (S-47) resolves real addresses to
 * markets and zones through the api; this is only the header's first guess.
 */
export interface Market { city: string; province: 'AB'; lat: number; lng: number }

export const MARKETS: readonly Market[] = [
  { city: 'Calgary', province: 'AB', lat: 51.0447, lng: -114.0719 },
  { city: 'Edmonton', province: 'AB', lat: 53.5461, lng: -113.4938 },
  { city: 'Airdrie', province: 'AB', lat: 51.2917, lng: -114.0144 },
];

/** Where nobody can be placed: Calgary (the first live market). */
export const DEFAULT_MARKET = MARKETS[0]!;

/** Within this distance of a market's centre, the header says that city. */
export const MARKET_RADIUS_KM = 40;

export function distanceKm(a: { lat: number; lng: number }, b: { lat: number; lng: number }): number {
  const rad = (d: number) => (d * Math.PI) / 180;
  const dLat = rad(b.lat - a.lat), dLng = rad(b.lng - a.lng);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * 6371 * Math.asin(Math.sqrt(h));
}

/** The nearest live market within MARKET_RADIUS_KM, or null (outside every live market). */
export function nearestMarket(point: { lat: number; lng: number }): Market | null {
  let best: Market | null = null, bestKm = Infinity;
  for (const m of MARKETS) { const km = distanceKm(point, m); if (km < bestKm) { best = m; bestKm = km; } }
  return bestKm <= MARKET_RADIUS_KM ? best : null;
}
