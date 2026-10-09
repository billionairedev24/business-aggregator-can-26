import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/render';
import { LiveShare } from './LiveShare';

/** Mobile gaps part 2: the member on the way shares their position only after switching it on. */
function fakeGeolocation(error?: number) {
  const watches: ((p: GeolocationPosition) => void)[] = [];
  const geo = {
    watchPosition: vi.fn((ok: (p: GeolocationPosition) => void, fail: (e: GeolocationPositionError) => void) => {
      if (error) fail({ code: error, PERMISSION_DENIED: 1 } as GeolocationPositionError);
      else watches.push(ok);
      return 7;
    }),
    clearWatch: vi.fn(),
  };
  vi.stubGlobal('navigator', { ...navigator, geolocation: geo });
  return { geo, move: (lat: number, lng: number) => watches.forEach(w => w({ coords: { latitude: lat, longitude: lng } } as GeolocationPosition)) };
}

afterEach(() => { vi.unstubAllGlobals(); });

describe('LiveShare (On my way, with consent)', () => {
  it('sends nothing until switched on, then the latest position; switching off stops it', async () => {
    const calls = mockFetch(() => ({ body: { accepted: true, nextInSeconds: 5, at: '2026-10-09T15:00:00Z' } }));
    const { geo, move } = fakeGeolocation();
    renderWithProviders(<LiveShare merchantId="M1" jobId="B1" />);
    const toggle = screen.getByRole('switch', { name: 'Share my location with the customer while I’m on my way' });
    expect(geo.watchPosition).not.toHaveBeenCalled();
    expect(screen.getByText(/The customer sees minutes away, not where you are/)).toBeTruthy();
    const u = userEvent.setup();
    await u.click(toggle);
    move(50.05, -100.0);
    await waitFor(() => expect(calls.find(c => c.method === 'POST')?.body).toEqual({ lat: 50.05, lng: -100.0 }));
    expect(calls.find(c => c.method === 'POST')!.url).toBe('/api/v1/merchants/M1/jobs/B1/position');
    move(50.04, -100.0); // within 15 s: not sent again
    expect(calls.filter(c => c.method === 'POST')).toHaveLength(1);
    await u.click(toggle);
    expect(geo.clearWatch).toHaveBeenCalledWith(7);
    await waitFor(() => expect(calls.some(c => c.method === 'DELETE')).toBe(true));
  });

  it('says so when the browser refuses, in French', async () => {
    mockFetch(() => undefined);
    fakeGeolocation(1);
    renderWithProviders(<LiveShare merchantId="M1" jobId="B1" />, { locale: 'fr' });
    await userEvent.setup().click(screen.getByRole('switch', { name: 'Partager ma position avec le client pendant que je suis en route' }));
    expect(await screen.findByText('La localisation est bloquée pour ce site. Autorisez-la dans les réglages du navigateur pour la partager.')).toBeTruthy();
  });
});
