import type { Run, Stop } from './api/courier';

/** The shop's name for a pickup, the street for a drop-off (never the customer's name: the api has none for us). */
export function stopName(stop: Stop): string {
  if (stop.kind === 'pickup') return stop.place?.name ?? stop.orderRef ?? '';
  return stop.dropoff?.street ?? stop.orderRef ?? '';
}

/** The full address line(s) to show and to search in maps. */
export function stopAddress(stop: Stop): string {
  if (stop.kind === 'pickup') return stop.place?.address ?? '';
  const d = stop.dropoff;
  if (!d) return '';
  return [d.street, [d.city, d.postal].filter(Boolean).join(' ')].filter(Boolean).join(', ');
}

/** Stops in the api's order (seq): pickups first, then drop-offs. */
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
  const lat = stop.kind === 'pickup' ? stop.place?.lat : stop.dropoff?.lat;
  const lng = stop.kind === 'pickup' ? stop.place?.lng : stop.dropoff?.lng;
  const q = lat != null && lng != null ? `${lat},${lng}` : stopAddress(stop);
  if (!q) return null;
  const query = encodeURIComponent(q);
  return os === 'ios' ? `maps:?q=${query}` : os === 'android' ? `geo:0,0?q=${query}` : null;
}
