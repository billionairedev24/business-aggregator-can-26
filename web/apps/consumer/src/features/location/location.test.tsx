import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, screen } from '@testing-library/react';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { SAVED_KEY } from './useDeliveryLocation';

/** The api's fallback market (region configuration; test data). */
const FALLBACK = { id: 'mkt-calgary', city: 'Calgary', province: 'AB', stage: 'live', lat: 51.0447, lng: -114.0719 };
const session = (location?: { city: string }, fallback: unknown = FALLBACK) => (call: Call) =>
  call.url === '/bff/session' ? { body: { user: null, guestId: 'g_x', location } }
    : call.url === '/api/v1/geo/markets' ? { body: { items: [], fallback } } : undefined;
const pill = () => screen.getByRole('link', { name: /deliver to|Locating|Set location/i });

function fakeGeo(result: { lat: number; lng: number } | 'denied' | 'never') {
  return {
    getCurrentPosition: (ok: PositionCallback, fail?: PositionErrorCallback | null) => {
      if (result === 'never') return;
      if (result === 'denied') fail?.({ code: 1, message: 'denied', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 } as GeolocationPositionError);
      else ok({ coords: { latitude: result.lat, longitude: result.lng, accuracy: 20 } } as GeolocationPosition);
    },
  } as unknown as Geolocation;
}

beforeEach(() => { localStorage.clear(); sessionStorage.clear(); });
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('location pill', () => {
  it('falls back to the api’s fallback market when it can’t name the device’s place', async () => {
    mockFetch(session());
    renderApp('/', { geolocation: fakeGeo({ lat: 53.54, lng: -113.5 }) });
    expect(await screen.findByText('Calgary')).toBeInTheDocument();
    expect(pill()).not.toHaveTextContent('Detected');
    expect(sessionStorage.getItem('nl.location.detected')).toBeNull();
  });

  it('asks for a location when no fallback market is configured', async () => {
    mockFetch(session(undefined, null));
    renderApp('/', { geolocation: fakeGeo('denied') });
    expect(await screen.findByText('Set location')).toBeInTheDocument();
  });

  it('uses the api’s neighbourhood when it has one', async () => {
    const calls = mockFetch(c => session()(c) ?? (c.url.startsWith('/api/v1/geo/reverse') ? { body: { label: 'Beltline, Calgary', city: 'Calgary' } } : undefined));
    renderApp('/', { geolocation: fakeGeo({ lat: 51.04, lng: -114.07 }) });
    expect(await screen.findByText('Beltline, Calgary')).toBeInTheDocument();
    expect(calls.find(c => c.url.startsWith('/api/v1/geo/reverse'))!.url).toBe('/api/v1/geo/reverse?lat=51.04000&lng=-114.07000');
  });

  it('falls back to Calgary when location access is declined', async () => {
    mockFetch(session());
    renderApp('/', { geolocation: fakeGeo('denied') });
    expect(await screen.findByText('Calgary')).toBeInTheDocument();
    expect(pill()).toHaveTextContent('Deliver to');
    expect(pill()).not.toHaveTextContent('Detected');
    expect(pill()).toHaveAttribute('title', 'Delivery location');
  });

  it('falls back to Calgary outside every live market', async () => {
    mockFetch(session());
    renderApp('/', { geolocation: fakeGeo({ lat: 43.65, lng: -79.38 }) });
    expect(await screen.findByText('Calgary')).toBeInTheDocument();
    expect(pill()).not.toHaveTextContent('Detected');
  });

  it('shows the IP city first and keeps it when the browser has no geolocation', async () => {
    mockFetch(session({ city: 'Airdrie' }));
    renderApp('/', { geolocation: null });
    expect(await screen.findByText('Airdrie')).toBeInTheDocument();
    expect(pill()).toHaveTextContent('Detected · deliver to');
  });

  it('stops "Locating…" after 4 s when the prompt is never answered', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    mockFetch(session());
    renderApp('/', { geolocation: fakeGeo('never') });
    expect(await screen.findByText('Locating…')).toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(4100); });
    expect(await screen.findByText('Calgary')).toBeInTheDocument();
  });

  it('prefers the address saved on the Location screen, and opens that screen', async () => {
    localStorage.setItem(SAVED_KEY, JSON.stringify({ label: '1204 17 Ave SW, Calgary', city: 'Calgary' }));
    mockFetch(session({ city: 'Airdrie' }));
    renderApp('/', { geolocation: fakeGeo({ lat: 53.54, lng: -113.5 }) });
    expect(await screen.findByText('1204 17 Ave SW, Calgary')).toBeInTheDocument();
    expect(pill()).toHaveTextContent('Deliver to');
    expect(pill()).toHaveAttribute('href', '/location');
  });

  it('treats a place outside every market as unknown, even when the api can name it (S-47)', async () => {
    mockFetch(c => session()(c) ?? (c.url.startsWith('/api/v1/geo/reverse') ? { body: { label: 'Parkdale, Toronto', city: 'Toronto', province: 'ON', market: null, zone: null } } : undefined));
    renderApp('/', { geolocation: fakeGeo({ lat: 43.64, lng: -79.43 }) });
    expect(await screen.findByText('Calgary')).toBeInTheDocument();
    expect(pill()).not.toHaveTextContent('Detected');
  });

  it('keeps the market, zone and province the api resolved (S-47)', async () => {
    mockFetch(c => session()(c) ?? (c.url.startsWith('/api/v1/geo/reverse') ? { body: { label: 'Beltline, Calgary', city: 'Calgary', province: 'AB', market: { id: 'mkt-calgary', stage: 'live' }, zone: { id: 'zone-yyc-beltline', name: 'Beltline' } } } : undefined));
    renderApp('/', { geolocation: fakeGeo({ lat: 51.038, lng: -114.089 }) });
    expect(await screen.findByText('Beltline, Calgary')).toBeInTheDocument();
    expect(JSON.parse(sessionStorage.getItem('nl.location.detected')!)).toMatchObject({ city: 'Calgary', province: 'AB', marketId: 'mkt-calgary', zoneId: 'zone-yyc-beltline', zone: 'Beltline' });
  });

  it('is in French too', async () => {
    mockFetch(session());
    renderApp('/', { locale: 'fr', geolocation: fakeGeo('denied') });
    expect(await screen.findByText('Livrer à')).toBeInTheDocument();
  });
});

