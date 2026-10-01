import { useMemo } from 'react';
import type { Courier, DeliveryMap, Run } from './api';
import { centroid, fit, tiles, toView, VIEW, type LatLng } from './mapGeometry';
import { useDeliveryT } from './messages';

/** A courier is drawn rosehip when their run is late or their phone went quiet for 10 min while on a run. */
export function stuck(c: Courier, runs: readonly Run[], now: number): boolean {
  const run = c.runId ? runs.find(r => r.id === c.runId) : undefined;
  if (run?.late) return true;
  return !!c.runId && !!c.position && now - Date.parse(c.position.at) > 10 * 60_000;
}

/**
 * The delivery ops map (S-81, design 03 lines 281–284): the market's delivery zones (region model polygons) and the
 * couriers' latest positions (S-88), on the basemap tiles when one is configured (`CONSOLE_MAP_TILES`), else on the
 * design's grid. Bounds come from the data: no place is assumed.
 */
export function OpsMap({ map, couriers, runs, now = Date.now() }: { map: DeliveryMap; couriers: readonly Courier[]; runs: readonly Run[]; now?: number }) {
  const t = useDeliveryT();
  const placed = couriers.filter(c => c.position);
  const bounds = useMemo(() => {
    const pts: LatLng[] = [...map.zones.flatMap(z => z.ring), ...placed.map(c => c.position!)];
    if (!pts.length && map.market.lat != null && map.market.lng != null) pts.push({ lat: map.market.lat, lng: map.market.lng });
    return fit(pts);
  }, [map, placed]);
  const basemap = map.basemap && bounds ? tiles(bounds, map.basemap.tiles) : [];
  return (
    <figure className="nl-dl-map" data-basemap={map.basemap ? 'tiles' : 'grid'}>
      <svg viewBox={`0 0 ${VIEW} ${VIEW}`} role="img" aria-label={t('mapTitle', { city: map.market.city })}>
        {basemap.map(tl => <image key={tl.key} href={tl.href} x={tl.x} y={tl.y} width={tl.size} height={tl.size} preserveAspectRatio="none" />)}
        {bounds ? map.zones.filter(z => z.ring.length > 2).map(z => {
          const pts = z.ring.map(p => toView(bounds, p));
          const c = centroid(z.ring);
          const label = c ? toView(bounds, c) : undefined;
          return (
            <g key={z.id} className="nl-dl-zone">
              <polygon points={pts.map(p => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ')} />
              {label ? <text x={label.x} y={label.y} textAnchor="middle">{z.name}</text> : null}
            </g>
          );
        }) : null}
        {bounds ? placed.map(c => {
          const p = toView(bounds, c.position!);
          return <circle key={c.id} cx={p.x} cy={p.y} r={6} className="nl-dl-dot" data-stuck={stuck(c, runs, now) ? 'true' : 'false'}><title>{c.name ?? c.id}</title></circle>;
        }) : null}
      </svg>
      <figcaption className="nl-dl-legend">
        <span>● {t('legendMoving')}</span> · <span><span className="nl-dl-stuck">●</span> {t('legendStuck')}</span>
        {map.basemap?.attribution ? <span className="nl-dl-attr">{map.basemap.attribution}</span> : null}
      </figcaption>
    </figure>
  );
}
