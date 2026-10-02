import type { FixtureArea, FixtureContext } from './context';

/**
 * The region model and the geo api (S-47, S-134) with made-up places — no real province, city or zone (region-neutral):
 * one live province with two live markets, a pilot province and a waitlist province.
 */
export const FIXTURE_ZONE = 'UTC';

export const MARKETS = [
  { id: 'mkt-sampleville', city: 'Sampleville', province: 'XA', stage: 'live', lat: 45.1, lng: -75.2 },
  { id: 'mkt-exampleton', city: 'Exampleton', province: 'XA', stage: 'live', lat: 45.4, lng: -75.6 },
  { id: 'mkt-pilotburg', city: 'Pilotburg', province: 'XB', stage: 'pilot', lat: 47.0, lng: -70.1 },
] as const;

export const PROVINCES = [
  { code: 'XA', name: 'Sample Province', stage: 'live', taxBps: 500, markets: MARKETS.filter((m) => m.province === 'XA') },
  { code: 'XB', name: 'Pilot Province', stage: 'pilot', taxBps: 1300, markets: MARKETS.filter((m) => m.province === 'XB') },
  { code: 'XC', name: 'Waitlist Province', stage: 'waitlist', taxBps: 1500, markets: [] },
  { code: 'XD', name: 'Later Province', stage: 'off', taxBps: 1400, markets: [] },
] as const;

export const ZONE = { id: 'zone-old-town', name: 'Old Town', runsPerDay: 3, feeStdCents: 299, feePlusCents: 0, minBasketCents: 2000 };

export const ADDRESSES = [
  { placeId: 'place-1204', main: '1204 Example Ave', secondary: 'Sampleville, XA', street: '1204 Example Ave', city: 'Sampleville', province: 'XA', postalCode: 'A1A 1A1', lat: 45.11, lng: -75.21, market: MARKETS[0], zone: ZONE },
  { placeId: 'place-77', main: '77 Harbour Road', secondary: 'Faraway, XC', street: '77 Harbour Road', city: 'Faraway', province: 'XC', postalCode: 'C1C 1C1', lat: 50.2, lng: -60.3, market: null, zone: null },
] as const;

export function geoFixtures(ctx: FixtureContext, state: { waitlist: Array<{ regionId: string; email?: string }> }): FixtureArea {
  return (req) => {
    if (!req.path.startsWith('/geo/')) return undefined;
    const q = req.url.searchParams;
    switch (req.path) {
      case '/geo/regions':
        return ctx.answer(200, {
          platformTimeZone: FIXTURE_ZONE,
          defaultProvince: 'XA',
          provinces: PROVINCES.map((p) => ({ code: p.code, name: p.name, nameIn: p.name, nameOf: p.name, status: p.stage, timeZone: FIXTURE_ZONE, timeZones: [FIXTURE_ZONE], privacyLaw: 'PIPEDA', taxBps: p.taxBps })),
          markets: MARKETS.map((m) => ({ id: m.id, city: m.city, province: m.province, timeZone: FIXTURE_ZONE, status: m.stage, lat: m.lat, lng: m.lng })),
        });
      case '/geo/markets':
        return ctx.answer(200, { items: PROVINCES, fallback: MARKETS[0] });
      case '/geo/reverse': {
        const lat = Number(q.get('lat'));
        if (Math.abs(lat - 45.1) > 1) return ctx.answer(404, { code: 'not_found', detail: 'Nothing is known there.' });
        return ctx.answer(200, { label: '1204 Example Ave, Sampleville', city: 'Sampleville', province: 'XA', market: { id: MARKETS[0].id, stage: 'live' }, zone: { id: ZONE.id, name: ZONE.name } });
      }
      case '/geo/autocomplete': {
        const text = (q.get('q') ?? '').toLowerCase();
        if (text.length < 3) return ctx.answer(422, { errors: [{ field: 'q', rule: 'size', message: 'Type at least 3 characters.' }] });
        return ctx.answer(200, {
          items: ADDRESSES.filter((a) => a.main.toLowerCase().includes(text) || a.secondary.toLowerCase().includes(text)).map((a) => ({ placeId: a.placeId, main: a.main, secondary: a.secondary })),
          attribution: 'Fixture Maps',
        });
      }
      case '/geo/waitlist': {
        state.waitlist.push({ regionId: String(req.body.regionId ?? ''), email: req.body.email as string | undefined });
        return ctx.answer(201, { joined: true });
      }
    }
    const place = /^\/geo\/places\/(.+)$/.exec(req.path);
    if (place) {
      const a = ADDRESSES.find((x) => x.placeId === decodeURIComponent(place[1]!));
      if (!a) return ctx.answer(404, { code: 'not_found' });
      return ctx.answer(200, {
        placeId: a.placeId, label: `${a.street}, ${a.city}`, street: a.street, city: a.city, province: a.province, postalCode: a.postalCode,
        neighbourhood: a.zone?.name ?? null, lat: a.lat, lng: a.lng,
        resolution: { market: a.market, zone: a.zone, waitlist: a.market ? null : { regionId: a.province, name: 'Waitlist Province', stage: 'waitlist' } },
      });
    }
    return ctx.answer(404, { code: 'not_found' });
  };
}
