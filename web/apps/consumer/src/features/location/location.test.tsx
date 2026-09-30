import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, screen } from '@testing-library/react';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { nearestMarket } from './markets';
import { SAVED_KEY } from './useDeliveryLocation';

const session = (location?: { city: string }) => (call: Call) => (call.url === '/bff/session' ? { body: { user: null, guestId: 'g_x', location } } : undefined);
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
  it('names the market the device is in when the api can’t reverse-geocode yet', async () => {
    mockFetch(session());
    renderApp('/', { geolocation: fakeGeo({ lat: 53.54, lng: -113.5 }) });
    expect(await screen.findByText('Edmonton')).toBeInTheDocument();
    expect(pill()).toHaveTextContent('Detected · deliver to');
    expect(sessionStorage.getItem('nl.location.detected')).toContain('Edmonton');
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

  it('is in French too', async () => {
    mockFetch(session());
    renderApp('/', { locale: 'fr', geolocation: fakeGeo('denied') });
    expect(await screen.findByText('Livrer à')).toBeInTheDocument();
  });
});

describe('nearestMarket', () => {
  it('picks the closest live market within 40 km', () => {
    expect(nearestMarket({ lat: 51.29, lng: -114.01 })?.city).toBe('Airdrie');
    expect(nearestMarket({ lat: 51.0, lng: -114.1 })?.city).toBe('Calgary');
    expect(nearestMarket({ lat: 52.27, lng: -113.81 })).toBeNull(); // Red Deer: pilot, not live
  });
});
