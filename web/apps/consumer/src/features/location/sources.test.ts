import { describe, expect, it, vi } from 'vitest';
import { fromAddress, fromCookie, fromMarket, headerPlace, knowsPlace, locateDevice, resolveLocation, toCookie, type BookAddress, type Reverse } from './sources';

// Test data only: places come from the api's region configuration, never from code.
const HOME: BookAddress = { id: 'A1', label: null, street: '1204 17 Ave SW', unit: 'Apt 804', city: 'Market City', province: 'AB', postal: 'T2T 0B8', isDefault: true };
const OTHER: BookAddress = { id: 'A2', label: 'Mum', street: '44 Varsity Cir NW', unit: null, city: 'Market City', province: 'AB', postal: null, isDefault: false };
const SAVED = { label: 'Saved Street, Market City', city: 'Market City' };
const none = { saved: null, addresses: null, ipCity: null };

describe('where the visitor is: saved → default address → CDN city → nothing (S-190: never a guessed city)', () => {
  it('prefers the saved choice over everything', () => {
    expect(resolveLocation({ saved: SAVED, addresses: [HOME], ipCity: 'Ip City' })).toEqual({ status: 'saved', source: 'saved', ...SAVED });
  });

  it('then the signed-in person’s default address, with its parts', () => {
    expect(resolveLocation({ ...none, addresses: [OTHER, HOME], ipCity: 'Ip City' })).toEqual({
      status: 'address', source: 'address', label: '1204 17 Ave SW, Market City', city: 'Market City', street: '1204 17 Ave SW',
      unit: 'Apt 804', province: 'AB', postalCode: 'T2T 0B8',
    });
  });

  it('takes the first address when none is marked default', () => {
    expect(resolveLocation({ ...none, addresses: [OTHER] })).toMatchObject({ status: 'address', label: '44 Varsity Cir NW, Market City' });
  });

  it('then the CDN’s city', () => {
    expect(resolveLocation({ ...none, addresses: [], ipCity: 'Ip City' })).toEqual({ status: 'detected', source: 'ip', label: 'Ip City', city: 'Ip City' });
  });

  it('otherwise knows nothing — no fallback market is guessed, and nothing waits for a place', () => {
    expect(resolveLocation(none)).toEqual({ status: 'denied' });
    expect(knowsPlace(resolveLocation(none))).toBe(false);
    expect(knowsPlace({ status: 'locating' })).toBe(false);
    expect(knowsPlace(resolveLocation({ ...none, ipCity: 'Ip City' }))).toBe(true);
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
    // S-192: still a place to save — its point ranks the nearest markets first (never blocks)
    expect(await locateDevice(geo(position(43.6, -79.4)), async () => named({ city: 'Far City', province: null, market: null }))).toEqual({ kind: 'outside', place: 'Far City', location: { label: 'Far City', locality: 'Far City', lat: 43.6, lng: -79.4 } });
    expect(await locateDevice(geo(position(49.7, -112.8)), async () => named({ city: 'Soon City', market: { id: 'w', stage: 'waitlist' } }))).toMatchObject({ kind: 'outside', place: 'Soon City' });
    expect(await locateDevice(geo(position(0, 0)), async () => null)).toEqual({ kind: 'outside' });
  });

  it('says when the api can’t answer', async () => {
    expect(await locateDevice(geo(position(1, 2)), async () => { throw new Error('503'); })).toEqual({ kind: 'error' });
  });
});

describe('S-192: the detected place, the header line and the cookie', () => {
  it('turns the detected place into a location: a city ranks like the CDN city did, a country alone is shown only', () => {
    expect(resolveLocation({ ...none, visitor: { country: 'US', city: 'Austin', postalCode: '78701', lat: 30.27, lng: -97.74, region: 'TX' } })).toEqual({
      status: 'detected', source: 'ip', label: 'Austin', city: 'Austin', country: 'US', postalCode: '78701', lat: 30.27, lng: -97.74,
    });
    const countryOnly = resolveLocation({ ...none, visitor: { country: 'FR' } });
    expect(countryOnly).toEqual({ status: 'detected', source: 'ip', country: 'FR' });
    expect(knowsPlace(countryOnly)).toBe(false);
    expect(resolveLocation({ ...none, visitor: { source: 'none' } })).toEqual({ status: 'denied' });
  });

  it('says "{town} {postal code}", else the town, else the country — nothing while locating or unknown', () => {
    const name = (c: string) => `[${c}]`;
    expect(headerPlace({ status: 'address', city: 'Market City', postalCode: 'T2T 0B8', label: 'x' }, name)).toBe('Market City T2T 0B8');
    expect(headerPlace({ status: 'saved', locality: 'Beverly Hills', postalCode: '90210' }, name)).toBe('Beverly Hills 90210');
    expect(headerPlace({ status: 'saved', postalCode: '10115' }, name)).toBe('10115');
    expect(headerPlace({ status: 'detected', city: 'Austin' }, name)).toBe('Austin');
    expect(headerPlace({ status: 'saved', country: 'FR' }, name)).toBe('[FR]');
    expect(headerPlace({ status: 'locating' }, name)).toBeUndefined();
    expect(headerPlace({ status: 'denied' }, name)).toBeUndefined();
  });

  it('keeps no street in the cookie, and reads back only a well-formed place', () => {
    const raw = toCookie({ label: '1204 17 Ave SW, Market City', street: '1204 17 Ave SW', city: 'Market City', postalCode: 'T2T 0B8', lat: 1, lng: 2 });
    expect(raw).not.toContain('1204');
    expect(fromCookie(raw)).toEqual({ city: 'Market City', postalCode: 'T2T 0B8', lat: 1, lng: 2 });
    expect(fromCookie('{}')).toBeNull();
    expect(fromCookie('not json')).toBeNull();
    expect(fromCookie(JSON.stringify({ country: 'usa' }))).toBeNull();
  });
});
