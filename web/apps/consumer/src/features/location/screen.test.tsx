import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { LocationScreen } from './LocationScreen';
import { SAVED_KEY } from './useDeliveryLocation';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const MARKETS = {
  fallback: { id: 'mkt-calgary', city: 'Calgary', province: 'AB', stage: 'live', lat: 51.0447, lng: -114.0719 },
  items: [
    { code: 'AB', name: 'Alberta', stage: 'live', taxBps: 500, markets: [
      { id: 'mkt-calgary', city: 'Calgary', province: 'AB', stage: 'live' }, { id: 'mkt-edmonton', city: 'Edmonton', province: 'AB', stage: 'live' },
      { id: 'mkt-airdrie', city: 'Airdrie', province: 'AB', stage: 'live' }, { id: 'mkt-lethbridge', city: 'Lethbridge', province: 'AB', stage: 'waitlist' }] },
    { code: 'BC', name: 'British Columbia', stage: 'pilot', taxBps: 1200, markets: [{ id: 'mkt-vancouver', city: 'Vancouver', province: 'BC', stage: 'pilot' }] },
    { code: 'ON', name: 'Ontario', stage: 'waitlist', taxBps: 1300, markets: [] },
    { code: 'QC', name: 'Québec', stage: 'waitlist', taxBps: 1498, markets: [] },
  ],
};
const SUGGESTIONS = {
  attribution: 'Google',
  items: [
    { placeId: 'ChIJsw', main: '1204 17 Ave SW', secondary: 'Calgary, AB T2T 0B7, Canada' },
    { placeId: 'ChIJnw', main: '1204 17 Ave NW', secondary: 'Calgary, AB T2M 0P8, Canada' },
    { placeId: 'ChIJse', main: '1204 17 Ave SE', secondary: 'Calgary, AB T2G 1J6, Canada' },
  ],
};
const BELTLINE = {
  placeId: 'ChIJsw', label: '1204 17 Ave SW, Calgary', street: '1204 17 Ave SW', city: 'Calgary', province: 'AB', postalCode: 'T2T 0B7',
  neighbourhood: 'Beltline', lat: 51.0379, lng: -114.0898,
  resolution: { market: { id: 'mkt-calgary', city: 'Calgary', province: 'AB', stage: 'live' }, zone: { id: 'zone-yyc-beltline', name: 'Beltline', runsPerDay: 3, feeStdCents: 499, feePlusCents: 0, minBasketCents: 3500 }, waitlist: null },
};
const LETHBRIDGE = {
  placeId: 'ChIJleth', label: '910 4 Ave S, Lethbridge', street: '910 4 Ave S', city: 'Lethbridge', province: 'AB', postalCode: 'T1J 0P6',
  neighbourhood: 'Downtown', lat: 49.69, lng: -112.84,
  resolution: { market: { id: 'mkt-lethbridge', city: 'Lethbridge', province: 'AB', stage: 'waitlist' }, zone: null, waitlist: { regionId: 'mkt-lethbridge', name: 'Lethbridge', stage: 'waitlist' } },
};

function api(opts: { user?: boolean; autocomplete?: { status: number; body?: unknown }; place?: unknown } = {}) {
  return (c: Call) => {
    if (c.url === '/bff/session') return { body: { user: opts.user ? { id: 'u1', firstName: 'Amara', lastName: 'Osei', initials: 'AO', email: 'amara@example.ca' } : null, guestId: 'g_1' } };
    if (c.url === '/api/v1/geo/markets') return { body: MARKETS };
    if (c.url.startsWith('/api/v1/geo/autocomplete')) return opts.autocomplete ?? { status: 200, body: c.url.includes('q=910') ? { attribution: 'Google', items: [{ placeId: 'ChIJleth', main: '910 4 Ave S', secondary: 'Lethbridge, AB T1J 0P6, Canada' }] } : SUGGESTIONS };
    if (c.url.startsWith('/api/v1/geo/places/')) return { body: opts.place ?? (c.url.includes('ChIJleth') ? LETHBRIDGE : BELTLINE) };
    if (c.url === '/api/v1/geo/waitlist') return { status: 201, body: { joined: true } };
    return undefined;
  };
}

const screenWith = (next?: string) => ({ location: () => <LocationScreen next={next} /> });

beforeEach(() => { localStorage.clear(); sessionStorage.clear(); });
afterEach(() => vi.unstubAllGlobals());

async function typeAndPick(user: ReturnType<typeof userEvent.setup>, text: string, option: RegExp, label = 'Street address') {
  await user.type(await screen.findByRole('combobox', { name: label }), text);
  await user.click(await screen.findByRole('option', { name: option }));
}

describe('location screen', () => {
  it('shows provinces with their stages', async () => {
    mockFetch(api());
    renderApp('/location', { routes: screenWith() });
    expect(await screen.findByRole('heading', { level: 1, name: 'Where should we bring things?' })).toBeInTheDocument();
    expect(screen.getByText('Your province sets taxes, delivery zones and which providers you see.')).toBeInTheDocument();
    const group = await screen.findByRole('group', { name: 'Province' });
    await expectNoAxeViolations(document.body); // S-109
    const alberta = within(group).getByRole('button', { name: /Alberta/ });
    expect(alberta).toHaveTextContent('Live · Calgary, Edmonton');
    expect(alberta).toHaveAttribute('aria-pressed', 'true');
    expect(within(group).getByRole('button', { name: /British Columbia/ })).toHaveTextContent('Pilot · invite only');
    expect(within(group).getByRole('button', { name: /Ontario/ })).toBeDisabled();
    expect(within(group).getByRole('button', { name: /Québec/ })).toHaveTextContent('Waitlist');
    // the provinces and cities come from the api (region configuration), in its order
    expect(within(group).getAllByRole('button').map(b => b.firstChild?.textContent)).toEqual(['Alberta', 'British Columbia', 'Ontario', 'Québec']);
    expect(screen.getByText('Northline opens city by city. Live in Alberta: Calgary, Edmonton, Airdrie. Waitlist: Lethbridge. An address outside a live market joins the waitlist for its nearest one.')).toBeInTheDocument();
  });

  it('suggests addresses, resolves the chosen one and saves it', async () => {
    const calls = mockFetch(api());
    const user = userEvent.setup({ delay: null });
    const { router } = renderApp('/location', { routes: screenWith('/food/checkout') });
    await user.type(await screen.findByRole('combobox', { name: 'Street address' }), '1204 17 ave');
    const list = await screen.findByRole('listbox', { name: 'Address suggestions' });
    expect(await within(list).findAllByRole('option')).toHaveLength(3);
    expect(screen.getByText('powered by Google')).toBeInTheDocument();
    const suggest = calls.filter(c => c.url.startsWith('/api/v1/geo/autocomplete'));
    expect(suggest).toHaveLength(1); // debounced: one request for the whole word
    const session = new URL(`http://x${suggest[0]!.url}`).searchParams.get('session');
    expect(session).toMatch(/^[A-Za-z0-9_-]{8,64}$/);

    await user.click(within(list).getByRole('option', { name: /1204 17 Ave SW/ }));
    expect(await screen.findByText('T2T 0B7')).toBeInTheDocument();
    expect(calls.find(c => c.url.startsWith('/api/v1/geo/places/'))!.url).toBe(`/api/v1/geo/places/ChIJsw?session=${session}`);
    expect(screen.getByText('Market · Calgary · live')).toBeInTheDocument();
    expect(screen.getByText('Zone · Beltline')).toBeInTheDocument();
    expect(screen.getByText('3 pooled runs / day')).toBeInTheDocument();
    expect(screen.getByText('Sales tax 5%')).toBeInTheDocument();
    expect(screen.getByText('ChIJsw')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Unit / buzzer / drop-off note'), 'Apt 804 · buzz 0804');
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/food/checkout'));
    expect(JSON.parse(localStorage.getItem(SAVED_KEY)!)).toEqual({
      label: '1204 17 Ave SW, Calgary', city: 'Calgary', lat: 51.0379, lng: -114.0898, placeId: 'ChIJsw', street: '1204 17 Ave SW',
      unit: 'Apt 804 · buzz 0804', province: 'AB', postalCode: 'T2T 0B7', marketId: 'mkt-calgary', zoneId: 'zone-yyc-beltline', zone: 'Beltline',
    });
    expect(await screen.findByText('1204 17 Ave SW, Calgary')).toBeInTheDocument(); // the pill
  });

  it('picks with the keyboard', async () => {
    mockFetch(api());
    const user = userEvent.setup({ delay: null });
    renderApp('/location', { routes: screenWith() });
    const box = await screen.findByRole('combobox', { name: 'Street address' });
    await user.type(box, '1204 17 ave');
    await screen.findAllByRole('option');
    await user.keyboard('{ArrowDown}{ArrowDown}');
    expect(box).toHaveAttribute('aria-activedescendant', expect.stringContaining('-1'));
    await user.keyboard('{Enter}');
    expect(await screen.findByText('T2T 0B7')).toBeInTheDocument(); // the mock answers Beltline for any place
    expect(box).toHaveValue('1204 17 Ave NW, Calgary');
  });

  it('asks to choose from the list before saving', async () => {
    mockFetch(api());
    const user = userEvent.setup({ delay: null });
    renderApp('/location', { routes: screenWith() });
    await user.click(await screen.findByRole('button', { name: 'Save and continue' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Choose your address from the list.');
    expect(screen.getByRole('combobox', { name: 'Street address' })).toHaveAttribute('aria-invalid', 'true');
  });

  it('offers the waitlist outside a live market — guests leave an email', async () => {
    const calls = mockFetch(api());
    const user = userEvent.setup({ delay: null });
    renderApp('/location', { routes: screenWith() });
    await typeAndPick(user, '910 4 ave', /910 4 Ave S/);
    expect(await screen.findByRole('heading', { name: 'Northline isn’t in Lethbridge yet.' })).toBeInTheDocument();
    expect(screen.getByText('Market · Lethbridge · waitlist')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save and continue' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Join the waitlist' }));
    expect(await screen.findByText('Email is required.')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Email'), 'dana@');
    await user.click(screen.getByRole('button', { name: 'Join the waitlist' }));
    expect(await screen.findByText('That doesn\'t look like an email address.')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Email'), 'example.ca');
    await user.click(screen.getByRole('button', { name: 'Join the waitlist' }));
    expect(await screen.findByText('You’re on the list for Lethbridge. We’ll email you when we open.')).toBeInTheDocument();
    expect(calls.find(c => c.url === '/api/v1/geo/waitlist')!.body).toEqual({ regionId: 'mkt-lethbridge', email: 'dana@example.ca' });
    expect(localStorage.getItem(SAVED_KEY)).toBeNull();
  });

  it('signed-in people join without typing an email', async () => {
    const calls = mockFetch(api({ user: true }));
    const user = userEvent.setup({ delay: null });
    renderApp('/location', { routes: screenWith() });
    await typeAndPick(user, '910 4 ave', /910 4 Ave S/);
    await screen.findByRole('heading', { name: 'Northline isn’t in Lethbridge yet.' });
    expect(screen.queryByLabelText('Email')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Join the waitlist' }));
    await screen.findByText(/You’re on the list/);
    expect(calls.find(c => c.url === '/api/v1/geo/waitlist')!.body).toEqual({ regionId: 'mkt-lethbridge' });
  });

  it('says when addresses can’t be looked up', async () => {
    mockFetch(api({ autocomplete: { status: 503, body: { code: 'places_unavailable' } } }));
    const user = userEvent.setup({ delay: null });
    renderApp('/location', { routes: screenWith() });
    await user.type(await screen.findByRole('combobox', { name: 'Street address' }), '1204 17');
    expect(await screen.findByText(/We couldn’t look up addresses right now/)).toBeInTheDocument();
  });

  it('says when there are too many lookups, or no match', async () => {
    mockFetch(api({ autocomplete: { status: 429, body: { code: 'rate_limited' } } }));
    const user = userEvent.setup({ delay: null });
    renderApp('/location', { routes: screenWith() });
    await user.type(await screen.findByRole('combobox', { name: 'Street address' }), '1204 17');
    expect(await screen.findByText(/Too many address lookups/)).toBeInTheDocument();
  });

  it('says when nothing matches', async () => {
    mockFetch(api({ autocomplete: { status: 200, body: { attribution: 'Google', items: [] } } }));
    const user = userEvent.setup({ delay: null });
    renderApp('/location', { routes: screenWith() });
    await user.type(await screen.findByRole('combobox', { name: 'Street address' }), 'zzzz');
    expect(await screen.findByText('No Canadian address matches “zzzz”.')).toBeInTheDocument();
  });

  it('shows the saved address', async () => {
    localStorage.setItem(SAVED_KEY, JSON.stringify({ label: '1204 17 Ave SW, Calgary', city: 'Calgary', street: '1204 17 Ave SW', province: 'AB', postalCode: 'T2T 0B7', marketId: 'mkt-calgary', zone: 'Beltline', unit: 'Apt 804' }));
    mockFetch(api());
    renderApp('/location', { routes: screenWith() });
    expect(await screen.findByDisplayValue('1204 17 Ave SW, Calgary')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Apt 804')).toBeInTheDocument();
    expect(screen.getByText('Zone · Beltline')).toBeInTheDocument();
  });

  it('speaks French', async () => {
    mockFetch(api());
    const user = userEvent.setup({ delay: null });
    renderApp('/location', { routes: screenWith(), locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Où devons-nous livrer ?' })).toBeInTheDocument();
    const group = await screen.findByRole('group', { name: 'Province' });
    expect(within(group).getByRole('button', { name: /Alberta/ })).toHaveTextContent('En service · Calgary, Edmonton');
    expect(within(group).getByRole('button', { name: /British Columbia/ })).toHaveTextContent('Pilote · sur invitation');
    await typeAndPick(user, '1204 17 ave', /1204 17 Ave SW/, 'Adresse');
    expect(await screen.findByText('Marché · Calgary · en service')).toBeInTheDocument();
    expect(screen.getByText('3 tournées groupées / jour')).toBeInTheDocument();
    expect(screen.getByText('Taxes de vente 5 %')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Enregistrer et continuer' })).toBeInTheDocument();
  });
});
