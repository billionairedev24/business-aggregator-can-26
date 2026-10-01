/**
 * The ops map's projection (S-81): Web Mercator in "world pixels" at zoom 0 (256 × 256 for the whole world), the same
 * space XYZ tiles use, fitted into a square view. No place is assumed: the bounds come from the data.
 */
export interface LatLng { lat: number; lng: number }
export interface Bounds { minX: number; minY: number; maxX: number; maxY: number }
export const TILE = 256;
export const VIEW = 400;

export function project({ lat, lng }: LatLng): { x: number; y: number } {
  const clamped = Math.max(-85.05112878, Math.min(85.05112878, lat));
  const s = Math.sin((clamped * Math.PI) / 180);
  return { x: ((lng + 180) / 360) * TILE, y: (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * TILE };
}

/**
 * The square around every point, padded by 10 %, at least `minSpanKm` wide around a lone point (a market's centre
 * with nothing else yet). Undefined without points.
 */
export function fit(points: readonly LatLng[], minSpanKm = 6): Bounds | undefined {
  if (!points.length) return undefined;
  const xy = points.map(project);
  let minX = Math.min(...xy.map(p => p.x)), maxX = Math.max(...xy.map(p => p.x));
  let minY = Math.min(...xy.map(p => p.y)), maxY = Math.max(...xy.map(p => p.y));
  const lat = points.reduce((s, p) => s + p.lat, 0) / points.length;
  // world pixels per km at this latitude: the equator is 40,075 km for 256 px
  const perKm = TILE / (40_075 * Math.cos((lat * Math.PI) / 180));
  const span = Math.max(maxX - minX, maxY - minY, minSpanKm * perKm) * 1.2;
  const cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
  minX = cx - span / 2; maxX = cx + span / 2; minY = cy - span / 2; maxY = cy + span / 2;
  return { minX, minY, maxX, maxY };
}

/** World pixels → the view's coordinates (0…VIEW). */
export function toView(b: Bounds, p: LatLng): { x: number; y: number } {
  const { x, y } = project(p);
  const k = VIEW / (b.maxX - b.minX);
  return { x: (x - b.minX) * k, y: (y - b.minY) * k };
}

/** The XYZ tiles covering the bounds (at most 4 × 4 at the chosen zoom), in view coordinates. */
export function tiles(b: Bounds, template: string): { key: string; href: string; x: number; y: number; size: number }[] {
  const span = b.maxX - b.minX;
  // a tile is 256 / 2^z world px, drawn 256 / 2^z × VIEW / span view px: about 256 when 2^z ≈ VIEW / span
  const z = Math.max(0, Math.min(18, Math.round(Math.log2(VIEW / span))));
  const n = 2 ** z, world = TILE / n, k = VIEW / span;
  const out: { key: string; href: string; x: number; y: number; size: number }[] = [];
  for (let tx = Math.floor(b.minX / world); tx <= Math.floor(b.maxX / world) && out.length < 25; tx++) {
    for (let ty = Math.floor(b.minY / world); ty <= Math.floor(b.maxY / world) && out.length < 25; ty++) {
      if (ty < 0 || ty >= n) continue;
      const wx = ((tx % n) + n) % n;
      out.push({ key: `${z}/${wx}/${ty}`, href: template.replace('{z}', String(z)).replace('{x}', String(wx)).replace('{y}', String(ty)),
        x: (tx * world - b.minX) * k, y: (ty * world - b.minY) * k, size: world * k });
    }
  }
  return out;
}

/** A ring's label point: the average of its vertices (enough for the small convex-ish zones of a city). */
export function centroid(ring: readonly LatLng[]): LatLng | undefined {
  const first = ring[0], last = ring.at(-1);
  const pts = ring.length > 1 && first && last && first.lat === last.lat && first.lng === last.lng ? ring.slice(0, -1) : ring;
  if (!pts.length) return undefined;
  return { lat: pts.reduce((s, p) => s + p.lat, 0) / pts.length, lng: pts.reduce((s, p) => s + p.lng, 0) / pts.length };
}
