import { describe, expect, it, vi } from 'vitest';
import { fromAddress, fromMarket, locateDevice, resolveLocation, type BookAddress, type Reverse } from './sources';

// Test data only: places come from the api's region configuration, never from code.
const FALLBACK = { id: 'mkt-a', city: 'Fallback City', province: 'AB', stage: 'live', lat: 51.04, lng: -114.07 };
const HOME: BookAddress = { id: 'A1', label: null, street: '1204 17 Ave SW', unit: 'Apt 804', city: 'Market City', province: 'AB', postal: 'T2T 0B8', isDefault: true };
const OTHER: BookAddress = { id: 'A2', label: 'Mum', street: '44 Varsity Cir NW', unit: null, city: 'Market City', province: 'AB', postal: null, isDefault: false };
const SAVED = { label: 'Saved Street, Market City', city: 'Market City' };
const none = { saved: null, addresses: null, ipCity: null, fallback: null };

describe('where the visitor is: saved → default address → IP city → fallback market', () => {
  it('prefers the saved choice over everything', () => {
    expect(resolveLocation({ saved: SAVED, addresses: [HOME], ipCity: 'Ip City', fallback: FALLBACK })).toEqual({ status: 'saved', source: 'saved', ...SAVED });
  });

  it('then the signed-in person’s default address, with its parts', () => {
    expect(resolveLocation({ ...none, addresses: [OTHER, HOME], ipCity: 'Ip City', fallback: FALLBACK })).toEqual({
      status: 'address', source: 'address', label: '1204 17 Ave SW, Market City', city: 'Market City', street: '1204 17 Ave SW',
      unit: 'Apt 804', province: 'AB', postalCode: 'T2T 0B8',
    });
  });

  it('takes the first address when none is marked default', () => {
    expect(resolveLocation({ ...none, addresses: [OTHER] })).toMatchObject({ status: 'address', label: '44 Varsity Cir NW, Market City' });
  });

  it('then the CDN’s city', () => {
    expect(resolveLocation({ ...none, addresses: [], ipCity: 'Ip City', fallback: FALLBACK })).toEqual({ status: 'detected', source: 'ip', label: 'Ip City', city: 'Ip City' });
  });

  it('then the configured fallback market', () => {
    expect(resolveLocation({ ...none, fallback: FALLBACK })).toEqual({
      status: 'fallback', source: 'default', label: 'Fallback City', city: 'Fallback City', province: 'AB', marketId: 'mkt-a', lat: 51.04, lng: -114.07,
    });
  });

  it('knows nothing when no fallback market is configured', () => {
    expect(resolveLocation(none)).toEqual({ status: 'denied' });
  });

  it('turns a market and an address into a place to save', () => {
    expect(fromMarket({ id: 'm', city: 'C', province: 'P', lat: null, lng: null })).toEqual({ label: 'C', city: 'C', province: 'P', marketId: 'm' });
    expect(fromAddress(OTHER)).toEqual({ label: '44 Varsity Cir NW, Market City', city: 'Market City', street: '44 Varsity Cir NW', province: 'AB' });
  });
});

const position = (lat: number, lng: number) => ({ coords: { latitude: lat, longitude: lng, accuracy: 20 } }) as GeolocationPosition;
const geo = (result: GeolocationPosition | { code: number }) => ({
  getCurrentPosition: vi.fn((ok: PositionCallback, fail: PositionErrorCallback) => ('coords' in result ? ok(result) : fail(result as GeolocationPositionError))),
}) as unknown as Geolocation;
const named = (r: Partial<Reverse>): Reverse => ({ label: 'Hood, Market City', city: 'Market City', province: 'AB', market: { id: 'mkt-a', stage: 'live' }, zone: { id: 'z1', name: 'Hood' }, ...r });

describe('"Use my current location"', () => {
  it('names the device’s place inside a live market, with its market and zone', async () => {
    const reverse = vi.fn(async () => named({}));
    expect(await locateDevice(geo(position(51.038, -114.089)), reverse)).toEqual({ kind: 'found', location: {
      label: 'Hood, Market City', city: 'Market City', lat: 51.038, lng: -114.089, province: 'AB', marketId: 'mkt-a', zoneId: 'z1', zone: 'Hood',
    } });
    expect(reverse).toHaveBeenCalledWith(51.038, -114.089);
  });

  it('accepts a pilot market', async () => {
    expect((await locateDevice(geo(position(1, 2)), async () => named({ market: { id: 'p', stage: 'pilot' } }))).kind).toBe('found');
  });

  it('says when the person or the browser refuses', async () => {
    expect(await locateDevice(geo({ code: 1 }), vi.fn())).toEqual({ kind: 'denied' });
  });

  it('says when there is no position (no geolocation, no fix, timed out)', async () => {
    expect(await locateDevice(null, vi.fn())).toEqual({ kind: 'unavailable' });
    expect(await locateDevice(geo({ code: 2 }), vi.fn())).toEqual({ kind: 'unavailable' });
    expect(await locateDevice(geo({ code: 3 }), vi.fn())).toEqual({ kind: 'unavailable' });
  });

  it('says when the place is outside every market — named or not', async () => {
    expect(await locateDevice(geo(position(43.6, -79.4)), async () => named({ city: 'Far City', market: null }))).toEqual({ kind: 'outside', place: 'Far City' });
    expect(await locateDevice(geo(position(49.7, -112.8)), async () => named({ city: 'Soon City', market: { id: 'w', stage: 'waitlist' } }))).toEqual({ kind: 'outside', place: 'Soon City' });
    expect(await locateDevice(geo(position(0, 0)), async () => null)).toEqual({ kind: 'outside' });
  });

  it('says when the api can’t answer', async () => {
    expect(await locateDevice(geo(position(1, 2)), async () => { throw new Error('503'); })).toEqual({ kind: 'error' });
  });
});
