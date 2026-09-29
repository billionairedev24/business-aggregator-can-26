import { useEffect, useState } from 'react';
import type { GeoStatus } from './LocationPill';
/** Detects location on mount, reverse-geocodes via /api/v1/geo/reverse, falls back to the saved address. */
export function useGeolocation(saved?: string) {
  const [state, set] = useState<{ status: GeoStatus; label?: string; lat?: number; lng?: number }>({ status: 'locating' });
  useEffect(() => {
    if (!('geolocation' in navigator)) { set({ status: saved ? 'saved' : 'denied', label: saved }); return; }
    navigator.geolocation.getCurrentPosition(async ({ coords }) => {
      const r = await fetch(`/api/v1/geo/reverse?lat=${coords.latitude}&lng=${coords.longitude}`).then(r => r.json()).catch(() => null);
      set({ status: 'detected', label: r?.neighbourhood ? `${r.neighbourhood}, ${r.city}` : saved, lat: coords.latitude, lng: coords.longitude });
    }, () => set({ status: saved ? 'saved' : 'denied', label: saved }), { maximumAge: 600_000, timeout: 5_000 });
  }, [saved]);
  return state;
}
