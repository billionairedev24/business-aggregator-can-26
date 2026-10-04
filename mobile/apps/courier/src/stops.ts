import type { Run, Stop } from './api/courier';

/**
 * The shop's name for a pickup or a return, the street for a drop-off (never the customer's name: the api gives only
 * the name an age-restricted order's photo ID must show, on the drop-off screen).
 */
export function stopName(stop: Stop): string {
  if (stop.kind !== 'dropoff') return stop.place?.name ?? stop.orderRef ?? '';
  return stop.dropoff?.street ?? stop.orderRef ?? '';
}

/** The stop's kind as the app names it. */
export const kindKey = (stop: Stop) => (stop.kind === 'pickup' ? 'stop.pickup' : stop.kind === 'return' ? 'stop.return' : 'stop.dropoff') as 'stop.pickup' | 'stop.return' | 'stop.dropoff';

/** The full address line(s) to show and to search in maps. */
export function stopAddress(stop: Stop): string {
  if (stop.kind !== 'dropoff') return stop.place?.address ?? '';
  const d = stop.dropoff;
  if (!d) return '';
  return [d.street, [d.city, d.postal].filter(Boolean).join(' ')].filter(Boolean).join(', ');
}

/** Stops in the api's order (seq): pickups first, then drop-offs, then any return to a business. */
export function orderedStops(run: Run): Stop[] {
  return [...run.stops].sort((a, b) => a.seq - b.seq);
}

/** The first stop not done yet. */
export function nextStop(run: Run): Stop | undefined {
  return orderedStops(run).find((s) => s.state !== 'done');
}

/** A drop-off can be made once every pickup of its order is done. */
export function pickedUp(run: Run, stop: Stop): boolean {
  return run.stops.filter((s) => s.kind === 'pickup' && s.orderId === stop.orderId).every((s) => s.state === 'done');
}

/** A maps link for the platform's own maps app: coordinates when known, else the address. */
export function mapsUrl(stop: Stop, os: string): string | null {
  const lat = stop.kind !== 'dropoff' ? stop.place?.lat : stop.dropoff?.lat;
  const lng = stop.kind !== 'dropoff' ? stop.place?.lng : stop.dropoff?.lng;
  const q = lat != null && lng != null ? `${lat},${lng}` : stopAddress(stop);
  if (!q) return null;
  const query = encodeURIComponent(q);
  return os === 'ios' ? `maps:?q=${query}` : os === 'android' ? `geo:0,0?q=${query}` : null;
}
