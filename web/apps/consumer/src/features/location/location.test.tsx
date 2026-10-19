import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { SAVED_KEY } from './useDeliveryLocation';

// Test data (region configuration comes from the api; never from code).
const FALLBACK = { id: 'mkt-calgary', city: 'Calgary', province: 'AB', stage: 'live', lat: 51.0447, lng: -114.0719 };
const MARKETS = {
  fallback: FALLBACK,
  items: [
    { code: 'AB', name: 'Alberta', stage: 'live', taxBps: 500, markets: [FALLBACK, { id: 'mkt-edmonton', city: 'Edmonton', province: 'AB', stage: 'live' }, { id: 'mkt-lethbridge', city: 'Lethbridge', province: 'AB', stage: 'waitlist' }] },
    { code: 'ON', name: 'Ontario', stage: 'waitlist', taxBps: 1300, markets: [] },
  ],
};
const BELTLINE = {
  placeId: 'ChIJsw', label: '1204 17 Ave SW, Calgary', street: '1204 17 Ave SW', city: 'Calgary', province: 'AB', postalCode: 'T2T 0B7',
  neighbourhood: 'Beltline', lat: 51.0379, lng: -114.0898,
  resolution: { market: { id: 'mkt-calgary', city: 'Calgary', province: 'AB', stage: 'live' }, zone: { id: 'zone-yyc-beltline', name: 'Beltline' }, waitlist: null },
};
const LETHBRIDGE = {
  placeId: 'ChIJleth', label: '910 4 Ave S, Lethbridge', street: '910 4 Ave S', city: 'Lethbridge', province: 'AB', postalCode: 'T1J 0P6',
  lat: 49.69, lng: -112.84,
  resolution: { market: { id: 'mkt-lethbridge', city: 'Lethbridge', province: 'AB', stage: 'waitlist' }, zone: null, waitlist: { regionId: 'mkt-lethbridge', name: 'Lethbridge', stage: 'waitlist' } },
};
const ADDRESSES = { items: [
  { id: 'A2', label: 'Mum', street: '44 Varsity Estates Cir NW', unit: null, city: 'Calgary', province: 'AB', postal: 'T3B 3B8', isDefault: false },
  { id: 'A1', label: null, street: '1204 17 Ave SW', unit: 'Apt 804', city: 'Calgary', province: 'AB', postal: 'T2T 0B8', isDefault: true },
] };

function api(opts: { user?: boolean; ipCity?: string; fallback?: unknown; reverse?: { status?: number; body?: unknown }; autocomplete?: { status: number; body?: unknown } } = {}) {
  return (c: Call) => {
    if (c.url === '/bff/session') return { body: { user: opts.user ? { id: 'u1', firstName: 'Amara', lastName: 'Osei', initials: 'AO', email: 'amara@example.ca' } : null, guestId: 'g_1', ...(opts.ipCity ? { location: { city: opts.ipCity } } : {}) } };
    if (c.url === '/api/v1/geo/markets') return { body: { ...MARKETS, fallback: opts.fallback === undefined ? FALLBACK : opts.fallback } };
    if (c.url === '/api/v1/me/addresses') return { body: ADDRESSES };
    if (c.url.startsWith('/api/v1/geo/reverse')) return opts.reverse ?? { body: { label: 'Beltline, Calgary', city: 'Calgary', province: 'AB', market: { id: 'mkt-calgary', stage: 'live' }, zone: { id: 'zone-yyc-beltline', name: 'Beltline' } } };
    if (c.url.startsWith('/api/v1/geo/autocomplete')) return opts.autocomplete ?? { body: c.url.includes('q=910') ? { attribution: 'Google', items: [{ placeId: 'ChIJleth', main: '910 4 Ave S', secondary: 'Lethbridge, AB T1J 0P6, Canada' }] } : { attribution: 'Google', items: [{ placeId: 'ChIJsw', main: '1204 17 Ave SW', secondary: 'Calgary, AB T2T 0B7, Canada' }, { placeId: 'ChIJnw', main: '1204 17 Ave NW', secondary: 'Calgary, AB T2M 0P8, Canada' }] } };
    if (c.url.startsWith('/api/v1/geo/places/')) return { body: c.url.includes('ChIJleth') ? LETHBRIDGE : BELTLINE };
    if (c.url === '/api/v1/geo/waitlist') return { status: 201, body: { joined: true } };
    return undefined;
  };
}

function fakeGeo(result: { lat: number; lng: number } | 'denied') {
  return {
    getCurrentPosition: vi.fn((ok: PositionCallback, fail?: PositionErrorCallback | null) => {
      if (result === 'denied') fail?.({ code: 1, message: 'denied', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 } as GeolocationPositionError);
      else ok({ coords: { latitude: result.lat, longitude: result.lng, accuracy: 20 } } as GeolocationPosition);
    }),
  } as unknown as Geolocation;
}

const control = () => screen.getByRole('button', { name: /Change location|Changer d’adresse/ });
const user = () => userEvent.setup({ delay: null });
async function openDialog() {
  await user().click(await screen.findByRole('button', { name: /Change location/ }));
  return screen.findByRole('dialog', { name: 'Choose your location' });
}

beforeEach(() => { localStorage.clear(); sessionStorage.clear(); window.location.hash = ''; });
afterEach(() => { vi.unstubAllGlobals(); });

describe('the location control (never prompts on load)', () => {
  it('shows the fallback market without asking the browser for its position', async () => {
    const geo = fakeGeo({ lat: 51.04, lng: -114.07 });
    const calls = mockFetch(api());
    renderApp('/', { geolocation: geo });
    expect(await screen.findByText('Calgary')).toBeInTheDocument();
    expect(control()).toHaveTextContent('Deliver to');
    expect(control()).toHaveAttribute('aria-haspopup', 'dialog');
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
    expect(calls.some(c => c.url.startsWith('/api/v1/geo/reverse'))).toBe(false);
  });

  it('asks to set a location when nothing is known and no fallback market is configured', async () => {
    mockFetch(api({ fallback: null }));
    renderApp('/');
    expect(await screen.findByText('Set your location')).toBeInTheDocument();
  });

  it('shows the CDN’s city', async () => {
    mockFetch(api({ ipCity: 'Airdrie' }));
    renderApp('/');
    expect(await screen.findByText('Airdrie')).toBeInTheDocument();
  });

  it('shows a signed-in person’s default address before the CDN’s city', async () => {
    mockFetch(api({ user: true, ipCity: 'Airdrie' }));
    renderApp('/');
    expect(await screen.findByText('1204 17 Ave SW, Calgary')).toBeInTheDocument();
  });

  it('prefers the place saved in this browser', async () => {
    localStorage.setItem(SAVED_KEY, JSON.stringify({ label: 'Saved St, Calgary', city: 'Calgary' }));
    mockFetch(api({ user: true, ipCity: 'Airdrie' }));
    renderApp('/');
    expect(await screen.findByText('Saved St, Calgary')).toBeInTheDocument();
  });

  it('says "Near" on the services pages', async () => {
    mockFetch(api());
    renderApp('/services');
    await screen.findByText('Calgary');
    expect(control()).toHaveTextContent('Near');
  });

  it('opens the dialog from #location (the old /location page redirects there)', async () => {
    window.location.hash = '#location';
    mockFetch(api());
    renderApp('/');
    expect(await screen.findByRole('dialog', { name: 'Choose your location' })).toBeInTheDocument();
    expect(window.location.hash).toBe('');
  });
});

describe('the location dialog', () => {
  it('says what it shows now, offers the live markets, and passes axe', async () => {
    mockFetch(api());
    renderApp('/');
    await screen.findByText('Calgary');
    const dialog = await openDialog();
    expect(within(dialog).getByText('Showing Calgary until you choose your location.')).toBeInTheDocument();
    const markets = within(dialog).getByRole('region', { name: 'Where Northline is live' });
    expect(within(markets).getAllByRole('button').map(b => b.textContent)).toEqual(['CalgaryAlberta', 'EdmontonAlberta']);
    await expectNoAxeViolations(document.body);
  });

  it('“Use my current location” asks only on the press, names the place and saves it', async () => {
    const geo = fakeGeo({ lat: 51.038, lng: -114.089 });
    const calls = mockFetch(api());
    renderApp('/', { geolocation: geo });
    await screen.findByText('Calgary');
    const dialog = await openDialog();
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
    await user().click(within(dialog).getByRole('button', { name: 'Use my current location' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);
    expect(calls.find(c => c.url.startsWith('/api/v1/geo/reverse'))!.url).toBe('/api/v1/geo/reverse?lat=51.03800&lng=-114.08900');
    expect(await screen.findByText('Beltline, Calgary')).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem(SAVED_KEY)!)).toMatchObject({ label: 'Beltline, Calgary', city: 'Calgary', lat: 51.038, lng: -114.089, marketId: 'mkt-calgary', zoneId: 'zone-yyc-beltline' });
  });

  it('explains a refusal', async () => {
    mockFetch(api());
    renderApp('/', { geolocation: fakeGeo('denied') });
    await screen.findByText('Calgary');
    const dialog = await openDialog();
    await user().click(within(dialog).getByRole('button', { name: 'Use my current location' }));
    expect(await within(dialog).findByText(/Location access is off for this site/)).toBeInTheDocument();
    expect(localStorage.getItem(SAVED_KEY)).toBeNull();
  });

  it('explains a place outside every market', async () => {
    mockFetch(api({ reverse: { body: { label: 'Parkdale, Toronto', city: 'Toronto', province: 'ON', market: null, zone: null } } }));
    renderApp('/', { geolocation: fakeGeo({ lat: 43.64, lng: -79.43 }) });
    await screen.findByText('Calgary');
    const dialog = await openDialog();
    await user().click(within(dialog).getByRole('button', { name: 'Use my current location' }));
    expect(await within(dialog).findByText('Northline isn’t available in Toronto yet. Choose one of our markets below.')).toBeInTheDocument();
  });

  it('explains a browser without geolocation', async () => {
    mockFetch(api());
    renderApp('/');
    await screen.findByText('Calgary');
    const dialog = await openDialog();
    await user().click(within(dialog).getByRole('button', { name: 'Use my current location' }));
    expect(await within(dialog).findByText(/Your browser couldn’t find your position/)).toBeInTheDocument();
  });

  it('finds an address or postal code, resolves it and saves it', async () => {
    const calls = mockFetch(api());
    renderApp('/');
    await screen.findByText('Calgary');
    const dialog = await openDialog();
    const u = user();
    await u.type(within(dialog).getByRole('combobox', { name: 'Address or postal code' }), '1204 17 ave');
    const list = await within(dialog).findByRole('listbox', { name: 'Address suggestions' });
    expect(await within(dialog).findByText('powered by Google')).toBeInTheDocument();
    expect(calls.filter(c => c.url.startsWith('/api/v1/geo/autocomplete'))).toHaveLength(1); // debounced
    await u.click(within(list).getByRole('option', { name: /1204 17 Ave SW/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('1204 17 Ave SW, Calgary')).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem(SAVED_KEY)!)).toEqual({
      label: '1204 17 Ave SW, Calgary', city: 'Calgary', lat: 51.0379, lng: -114.0898, placeId: 'ChIJsw', street: '1204 17 Ave SW',
      province: 'AB', postalCode: 'T2T 0B7', marketId: 'mkt-calgary', zoneId: 'zone-yyc-beltline', zone: 'Beltline',
    });
  });

  it('picks a suggestion with the keyboard', async () => {
    mockFetch(api());
    renderApp('/');
    await screen.findByText('Calgary');
    const dialog = await openDialog();
    const u = user();
    const box = within(dialog).getByRole('combobox', { name: 'Address or postal code' });
    await u.type(box, '1204 17 ave');
    await within(dialog).findAllByRole('option');
    await u.keyboard('{ArrowDown}{ArrowDown}');
    expect(box).toHaveAttribute('aria-activedescendant', expect.stringContaining('-1'));
    await u.keyboard('{Enter}');
    await waitFor(() => expect(localStorage.getItem(SAVED_KEY)).not.toBeNull());
  });

  it('offers the waitlist outside a live market — guests leave an email', async () => {
    const calls = mockFetch(api());
    renderApp('/');
    await screen.findByText('Calgary');
    const dialog = await openDialog();
    const u = user();
    await u.type(within(dialog).getByRole('combobox', { name: 'Address or postal code' }), '910 4 ave');
    await u.click(await within(dialog).findByRole('option', { name: /910 4 Ave S/ }));
    expect(await within(dialog).findByRole('heading', { name: 'Northline isn’t in Lethbridge yet.' })).toBeInTheDocument();
    await u.click(within(dialog).getByRole('button', { name: 'Join the waitlist' }));
    expect(await within(dialog).findByText('Email is required.')).toBeInTheDocument();
    await u.type(within(dialog).getByLabelText('Email'), 'dana@');
    await u.click(within(dialog).getByRole('button', { name: 'Join the waitlist' }));
    expect(await within(dialog).findByText('That doesn\'t look like an email address.')).toBeInTheDocument();
    await u.type(within(dialog).getByLabelText('Email'), 'example.ca');
    await u.click(within(dialog).getByRole('button', { name: 'Join the waitlist' }));
    expect(await within(dialog).findByText('You’re on the list for Lethbridge. We’ll email you when we open.')).toBeInTheDocument();
    expect(calls.find(c => c.url === '/api/v1/geo/waitlist')!.body).toEqual({ regionId: 'mkt-lethbridge', email: 'dana@example.ca' });
    expect(localStorage.getItem(SAVED_KEY)).toBeNull();
  });

  it('says when addresses can’t be looked up, or there are too many lookups', async () => {
    mockFetch(api({ autocomplete: { status: 429, body: { code: 'rate_limited' } } }));
    renderApp('/');
    await screen.findByText('Calgary');
    const dialog = await openDialog();
    await user().type(within(dialog).getByRole('combobox', { name: 'Address or postal code' }), 'T2T 0B8');
    expect(await within(dialog).findByText(/Too many address lookups/)).toBeInTheDocument();
  });

  it('lists a signed-in person’s addresses and uses one', async () => {
    mockFetch(api({ user: true }));
    renderApp('/');
    await screen.findByText('1204 17 Ave SW, Calgary');
    const dialog = await openDialog();
    const saved = await within(dialog).findByRole('region', { name: 'Your addresses' });
    await user().click(within(saved).getByRole('button', { name: /Mum · 44 Varsity Estates Cir NW/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('44 Varsity Estates Cir NW, Calgary')).toBeInTheDocument();
  });

  it('chooses a market, and every screen follows without leaving the page', async () => {
    mockFetch(api());
    const { router } = renderApp('/cart');
    await screen.findByText('Calgary');
    const dialog = await openDialog();
    await user().click(within(dialog).getByRole('button', { name: /Edmonton/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('Edmonton')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/cart');
    expect(JSON.parse(localStorage.getItem(SAVED_KEY)!)).toEqual({ label: 'Edmonton', city: 'Edmonton', province: 'AB', marketId: 'mkt-edmonton' });
  });

  it('closes with Escape, back to the control', async () => {
    mockFetch(api());
    renderApp('/');
    await screen.findByText('Calgary');
    await openDialog();
    await user().keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(control()).toHaveFocus();
  });

  it('speaks French', async () => {
    mockFetch(api());
    renderApp('/', { locale: 'fr' });
    expect(await screen.findByText('Livrer à')).toBeInTheDocument();
    await user().click(control());
    const dialog = await screen.findByRole('dialog', { name: 'Choisissez votre adresse' });
    expect(within(dialog).getByRole('button', { name: 'Utiliser ma position actuelle' })).toBeInTheDocument();
    expect(within(dialog).getByRole('combobox', { name: 'Adresse ou code postal' })).toBeInTheDocument();
  });
});
