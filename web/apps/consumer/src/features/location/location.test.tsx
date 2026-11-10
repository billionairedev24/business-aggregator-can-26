import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { SAVED_KEY } from './useDeliveryLocation';
import { countryOptions } from './countries';
import { PLACE_COOKIE } from './sources';

// Test data (region configuration comes from the api; never from code).
const ADDRESSES = { items: [
  { id: 'A2', label: 'Mum', street: '44 Varsity Estates Cir NW', unit: null, city: 'Calgary', province: 'AB', postal: 'T3B 3B8', isDefault: false },
  { id: 'A1', label: null, street: '1204 17 Ave SW', unit: 'Apt 804', city: 'Calgary', province: 'AB', postal: 'T2T 0B8', isDefault: true },
] };
const CALGARY = { id: 'mkt-calgary', city: 'Calgary', province: 'AB', stage: 'live' };
const POSTAL: Record<string, unknown> = {
  'T2T 0B7': { postalCode: 'T2T 0B7', found: true, city: 'Calgary', region: 'AB', country: 'CA', lat: 51.0379, lng: -114.0898, resolution: { market: CALGARY, zone: { id: 'zone-yyc-beltline', name: 'Beltline' }, waitlist: null } },
  '90210': { postalCode: '90210', found: true, city: 'Beverly Hills', region: 'CA', country: 'US', lat: 34.09, lng: -118.41, resolution: { market: null, zone: null, waitlist: null } },
  'T1J 0P6': { postalCode: 'T1J 0P6', found: true, city: 'Lethbridge', region: 'AB', country: 'CA', lat: 49.69, lng: -112.84, resolution: { market: { id: 'mkt-lethbridge', city: 'Lethbridge', province: 'AB', stage: 'waitlist' }, zone: null, waitlist: { regionId: 'mkt-lethbridge', name: 'Lethbridge', stage: 'waitlist' } } },
  '10115': { postalCode: '10115', found: false },
};

function api(opts: { user?: boolean; ipCity?: string; visitor?: unknown; reverse?: { status?: number; body?: unknown } } = {}) {
  return (c: Call) => {
    if (c.url === '/bff/session') return { body: { user: opts.user ? { id: 'u1', firstName: 'Amara', lastName: 'Osei', initials: 'AO', email: 'amara@example.ca' } : null, guestId: 'g_1', ...(opts.ipCity ? { location: { city: opts.ipCity } } : {}) } };
    if (c.url === '/api/v1/geo/visitor') return { body: opts.visitor ?? { source: 'none' } };
    if (c.url === '/api/v1/me/addresses') return { body: ADDRESSES };
    if (c.url.startsWith('/api/v1/geo/reverse')) return opts.reverse ?? { body: { label: 'Beltline, Calgary', city: 'Calgary', province: 'AB', market: { id: 'mkt-calgary', stage: 'live' }, zone: { id: 'zone-yyc-beltline', name: 'Beltline' } } };
    if (c.url.startsWith('/api/v1/geo/postal')) {
      const code = decodeURIComponent(new URL(c.url, 'http://x').searchParams.get('code') ?? '');
      return POSTAL[code] ? { body: POSTAL[code] } : { body: { postalCode: code, found: false } };
    }
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

const control = () => screen.getByRole('button', { name: /^(Deliver to|Near|Livrer à|Près de).*(Change location|Changer d’adresse)$/ });
const user = () => userEvent.setup({ delay: null });
async function openDialog() {
  await user().click(await screen.findByRole('button', { name: /Deliver to.*Change location/ }));
  return screen.findByRole('dialog', { name: 'Choose your location' });
}
const saved = () => JSON.parse(localStorage.getItem(SAVED_KEY) ?? 'null');

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); window.location.hash = '';
  document.cookie = `${PLACE_COOKIE}=; Path=/; Max-Age=0`;
});
afterEach(() => { vi.unstubAllGlobals(); });

describe('the header’s place (never prompts on load; S-192 Amazon’s "Deliver to")', () => {
  it('with nothing known never guesses a city, never asks the browser, and offers a quiet "Choose location"', async () => {
    const geo = fakeGeo({ lat: 51.04, lng: -114.07 });
    const calls = mockFetch(api());
    renderApp('/', { geolocation: geo });
    expect(await screen.findByText('Choose location')).toBeInTheDocument();
    expect(control()).toHaveTextContent('Deliver to');
    expect(control()).toHaveAttribute('aria-haspopup', 'dialog');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
    expect(calls.some(c => c.url.startsWith('/api/v1/geo/reverse'))).toBe(false);
  });

  it('shows the city detected from the connection, with its postal code', async () => {
    mockFetch(api({ visitor: { country: 'CA', city: 'Airdrie', postalCode: 'T4B', lat: 51.29, lng: -114.01, source: 'edge' } }));
    renderApp('/');
    expect(await screen.findByText('Airdrie T4B')).toBeInTheDocument();
    expect(control()).toHaveAccessibleName('Deliver to Airdrie T4B. Change location');
  });

  it('names the detected country when that is all the edge knows', async () => {
    mockFetch(api({ visitor: { country: 'FR', source: 'edge' } }));
    renderApp('/');
    expect(await screen.findByText('France', { selector: '.nl-location-place' })).toBeInTheDocument();
    // the footer's "language · country" follows (S-192)
    expect(screen.getByRole('button', { name: 'English · France. Change location' })).toBeInTheDocument();
  });

  it('still reads the session’s CDN city', async () => {
    mockFetch(api({ ipCity: 'Airdrie' }));
    renderApp('/');
    expect(await screen.findByText('Airdrie')).toBeInTheDocument();
  });

  it('greets a signed-in person over their default address’s city and postal code', async () => {
    mockFetch(api({ user: true, ipCity: 'Airdrie' }));
    renderApp('/');
    expect(await screen.findByText('Calgary T2T 0B8')).toBeInTheDocument();
    expect(control()).toHaveTextContent('Deliver to Amara');
  });

  it('prefers the place saved in this browser', async () => {
    localStorage.setItem(SAVED_KEY, JSON.stringify({ label: 'Saved St, Calgary', city: 'Calgary', postalCode: 'T2P 1B5' }));
    mockFetch(api({ user: true, ipCity: 'Airdrie' }));
    renderApp('/');
    expect(await screen.findByText('Calgary T2P 1B5')).toBeInTheDocument();
  });

  it('reads the saved place’s cookie when the storage is empty (what the server rendered)', async () => {
    document.cookie = `${PLACE_COOKIE}=${encodeURIComponent(JSON.stringify({ locality: 'Beverly Hills', postalCode: '90210', country: 'US' }))}; Path=/`;
    mockFetch(api({ ipCity: 'Airdrie' }));
    renderApp('/');
    expect(await screen.findByText('Beverly Hills 90210')).toBeInTheDocument();
  });

  it('says "Near" on the services pages', async () => {
    mockFetch(api({ ipCity: 'Calgary' }));
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

describe('the location dialog (S-192, Amazon’s "Choose your location")', () => {
  it('a guest: the line, sign in for addresses, a postal code with Apply, a country, Done — and it passes axe', async () => {
    const calls = mockFetch(api());
    renderApp('/');
    await screen.findByText('Choose location');
    const dialog = await openDialog();
    expect(within(dialog).getByText('Delivery options and delivery speeds may vary for different locations.')).toBeInTheDocument();
    expect(within(dialog).getByRole('link', { name: 'Sign in to see your addresses' })).toHaveAttribute('href', '/sign-in');
    expect(within(dialog).getByRole('separator', { name: 'or enter a postal or ZIP code' })).toBeInTheDocument();
    expect(within(dialog).getByRole('textbox', { name: 'Postal or ZIP code' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Apply' })).toBeInTheDocument();
    expect(within(dialog).getByRole('separator', { name: 'or deliver to another country' })).toBeInTheDocument();
    expect(within(dialog).getByRole('combobox', { name: 'Country' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Done' })).toBeInTheDocument();
    expect(calls.some(c => c.url.startsWith('/api/v1/me/addresses'))).toBe(false);
    await expectNoAxeViolations(document.body);
  });

  it('lists a signed-in person’s addresses as cards — default marked, the current one chosen — and uses one', async () => {
    mockFetch(api({ user: true }));
    renderApp('/');
    await screen.findByText('Calgary T2T 0B8');
    const dialog = await openDialog();
    const cards = await within(dialog).findAllByRole('button', { pressed: undefined, name: /Calgary/ });
    expect(cards).toHaveLength(2);
    const home = within(dialog).getByRole('button', { name: /1204 17 Ave SW/ });
    expect(home).toHaveAttribute('aria-pressed', 'true');
    expect(home).toHaveTextContent('Default address');
    expect(within(dialog).getByRole('link', { name: 'Manage addresses' })).toHaveAttribute('href', '/account?tab=addresses');
    await user().click(within(dialog).getByRole('button', { name: /Mum/ }));
    expect(within(dialog).getByRole('button', { name: /Mum/ })).toHaveAttribute('aria-pressed', 'true');
    expect(saved()).toMatchObject({ street: '44 Varsity Estates Cir NW', city: 'Calgary', postalCode: 'T3B 3B8' });
    expect(await screen.findByText('Calgary T3B 3B8')).toBeInTheDocument();
    await user().click(within(dialog).getByRole('button', { name: 'Done' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('checks a postal code as the api does, before asking it', async () => {
    const calls = mockFetch(api());
    renderApp('/');
    await screen.findByText('Choose location');
    const dialog = await openDialog();
    const box = within(dialog).getByRole('textbox', { name: 'Postal or ZIP code' });
    await user().click(within(dialog).getByRole('button', { name: 'Apply' }));
    expect(await within(dialog).findByText('Enter a postal or ZIP code.')).toBeInTheDocument();
    await user().type(box, '1234567890123');
    await user().click(within(dialog).getByRole('button', { name: 'Apply' }));
    expect(await within(dialog).findByText('At most 12 characters.')).toBeInTheDocument();
    await user().clear(box);
    await user().type(box, 'T2T_0B7');
    await user().click(within(dialog).getByRole('button', { name: 'Apply' }));
    expect(await within(dialog).findByText('Use letters, digits, spaces and hyphens only.')).toBeInTheDocument();
    expect(box).toHaveAttribute('aria-invalid', 'true');
    expect(calls.some(c => c.url.startsWith('/api/v1/geo/postal'))).toBe(false);
  });

  it('applies a postal code in a market: saves its market and zone, every screen follows', async () => {
    const calls = mockFetch(api());
    renderApp('/');
    await screen.findByText('Choose location');
    const dialog = await openDialog();
    await user().type(within(dialog).getByRole('textbox', { name: 'Postal or ZIP code' }), ' t2t   0b7 ');
    await user().click(within(dialog).getByRole('button', { name: 'Apply' }));
    expect(await within(dialog).findByText('Delivering to Calgary T2T 0B7.', { selector: '.nl-loc-applied p' })).toBeInTheDocument();
    expect(calls.find(c => c.url.startsWith('/api/v1/geo/postal'))!.url).toBe('/api/v1/geo/postal?code=T2T%200B7');
    expect(saved()).toMatchObject({ postalCode: 'T2T 0B7', locality: 'Calgary', city: 'Calgary', country: 'CA', marketId: 'mkt-calgary', zone: 'Beltline' });
    expect(document.cookie).toContain(`${PLACE_COOKIE}=`);
    expect(decodeURIComponent(document.cookie)).not.toContain('Varsity');
    await user().click(within(dialog).getByRole('button', { name: 'Done' }));
    expect(await screen.findByText('Calgary T2T 0B7')).toBeInTheDocument();
  });

  it('applies a code of another country, or one nobody knows — never blocks, ranks everything', async () => {
    mockFetch(api());
    renderApp('/');
    await screen.findByText('Choose location');
    const dialog = await openDialog();
    const box = within(dialog).getByRole('textbox', { name: 'Postal or ZIP code' });
    await user().type(box, '90210');
    await user().click(within(dialog).getByRole('button', { name: 'Apply' }));
    expect(await within(dialog).findByText('Northline doesn’t deliver to Beverly Hills 90210 yet. Showing everything, nearest first.')).toBeInTheDocument();
    expect(saved()).toEqual({ label: 'Beverly Hills 90210', postalCode: '90210', locality: 'Beverly Hills', country: 'US', lat: 34.09, lng: -118.41 });
    await user().clear(box);
    await user().type(box, '10115');
    await user().click(within(dialog).getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(saved()).toEqual({ label: '10115', postalCode: '10115' }));
    expect(await screen.findAllByText('10115')).not.toHaveLength(0);
  });

  it('offers the waitlist for a market not open yet — guests leave an email', async () => {
    const calls = mockFetch(api());
    renderApp('/');
    await screen.findByText('Choose location');
    const dialog = await openDialog();
    await user().type(within(dialog).getByRole('textbox', { name: 'Postal or ZIP code' }), 'T1J 0P6');
    await user().click(within(dialog).getByRole('button', { name: 'Apply' }));
    const list = await within(dialog).findByRole('region', { name: 'Northline isn’t in Lethbridge yet.' });
    await user().type(within(list).getByRole('textbox', { name: 'Email' }), 'dana@example.com');
    await user().click(within(list).getByRole('button', { name: 'Join the waitlist' }));
    expect(await within(dialog).findByText('You’re on the list for Lethbridge. We’ll email you when we open.')).toBeInTheDocument();
    expect(calls.find(c => c.url === '/api/v1/geo/waitlist')!.body).toEqual({ regionId: 'mkt-lethbridge', email: 'dana@example.com' });
  });

  it('delivers to another country: the country alone, no city', async () => {
    mockFetch(api());
    renderApp('/');
    await screen.findByText('Choose location');
    const dialog = await openDialog();
    const select = within(dialog).getByRole('combobox', { name: 'Country' });
    // common countries first (configuration), then every other one by name
    expect(within(select).getAllByRole('group').map(g => g.getAttribute('label'))).toEqual(['Common', 'All countries']);
    await user().selectOptions(select, 'FR');
    expect(saved()).toEqual({ country: 'FR' });
    expect(await screen.findByText('France', { selector: '.nl-location-place' })).toBeInTheDocument();
  });

  it('“Use my current location” asks only on the press, names the place and saves it', async () => {
    const geo = fakeGeo({ lat: 51.038, lng: -114.089 });
    const calls = mockFetch(api());
    renderApp('/', { geolocation: geo });
    await screen.findByText('Choose location');
    const dialog = await openDialog();
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
    await user().click(within(dialog).getByRole('button', { name: 'Use my current location' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);
    expect(calls.find(c => c.url.startsWith('/api/v1/geo/reverse'))!.url).toBe('/api/v1/geo/reverse?lat=51.03800&lng=-114.08900');
    expect(saved()).toMatchObject({ label: 'Beltline, Calgary', city: 'Calgary', lat: 51.038, lng: -114.089, marketId: 'mkt-calgary', zoneId: 'zone-yyc-beltline' });
  });

  it('explains a refusal, and keeps a place outside every market (nearest first)', async () => {
    mockFetch(api({ reverse: { body: { label: 'Far City', city: 'Far City', province: null, market: null, zone: null } } }));
    renderApp('/', { geolocation: fakeGeo('denied') });
    await screen.findByText('Choose location');
    let dialog = await openDialog();
    await user().click(within(dialog).getByRole('button', { name: 'Use my current location' }));
    expect(await within(dialog).findByText('Location access is off for this site. Enter a postal or ZIP code instead.')).toBeInTheDocument();
    await user().keyboard('{Escape}');
    Object.defineProperty(window.navigator, 'geolocation', { value: fakeGeo({ lat: 43.6, lng: -79.4 }), configurable: true });
    dialog = await openDialog();
    await user().click(within(dialog).getByRole('button', { name: 'Use my current location' }));
    expect(await within(dialog).findByText('Northline doesn’t deliver to Far City yet. Showing everything, nearest first.')).toBeInTheDocument();
    expect(saved()).toEqual({ label: 'Far City', locality: 'Far City', lat: 43.6, lng: -79.4 });
  });

  it('closes with Escape, back to the control', async () => {
    mockFetch(api());
    renderApp('/');
    await screen.findByText('Choose location');
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
    expect(within(dialog).getByRole('textbox', { name: 'Code postal ou ZIP' })).toBeInTheDocument();
    await user().click(within(dialog).getByRole('button', { name: 'Appliquer' }));
    expect(await within(dialog).findByText('Entrez un code postal ou ZIP.')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Terminé' })).toBeInTheDocument();
  });
});

describe('the countries (S-192: no country in code)', () => {
  it('lists the configured ones first, then every other by name in the page’s language', () => {
    const { first, rest } = countryOptions('en', ['gb', 'US', 'ZZ']);
    expect(first.map(c => c.code)).toEqual(['GB', 'US']);
    expect(rest.some(c => c.code === 'US')).toBe(false);
    expect(rest.length).toBeGreaterThan(240);
    expect(rest.map(c => c.name)).toEqual([...rest.map(c => c.name)].sort(new Intl.Collator('en-CA').compare));
    expect(countryOptions('fr', ['DE']).first[0]!.name).toBe('Allemagne');
  });
});
