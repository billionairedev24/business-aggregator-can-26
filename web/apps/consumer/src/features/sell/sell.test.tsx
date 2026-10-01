import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useSearch } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { SAVED_KEY } from '../location/useDeliveryLocation';
import { SellParams } from '../../routes/sell';
import { onboardingPath, SellScreen, studioFresh, studioStart } from './SellScreen';

const STUDIO = 'http://localhost:3100';
const AMARA = { id: '01J9ZD3V0000000000000C0001', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: null, initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };

let viewer: typeof AMARA | null = null;
const server = (call: Call) => (call.url === '/bff/session' ? { body: { user: viewer, guestId: 'g_x' } } : undefined);
function Route() {
  const { type } = SellParams.parse(useSearch({ strict: false }));
  return <SellScreen type={type} />;
}
const open = (path: string, locale: 'en' | 'fr' = 'en') => {
  mockFetch(server);
  return renderApp(path, { locale, routes: { sell: () => <Route /> } });
};

beforeEach(() => { viewer = null; localStorage.clear(); sessionStorage.clear(); });
afterEach(() => vi.unstubAllGlobals());

describe('Sell or offer a service (design 06 account › sell)', () => {
  it('offers the three ways in, as designed', async () => {
    open('/sell');
    expect(await screen.findByRole('heading', { level: 1, name: 'Sell or offer a service on Northline' })).toBeInTheDocument();
    expect(screen.getByText('Your customer account carries over — same login, same passkey. You’ll add business details, pass verification, and get a Studio.')).toBeInTheDocument();
    const cards = screen.getAllByRole('listitem');
    expect(cards.map(c => within(c).getByRole('heading', { level: 2 }).textContent)).toEqual(['Offer a service', 'Sell products', 'Run a kitchen']);
    expect(within(cards[0]!).getByText('Mechanic, cleaner, bartender, realtor… appointments, quotes, escrow payouts. Take rate 15% → 9% at Master.')).toBeInTheDocument();
    expect(within(cards[1]!).getByText('Verification: KYC · business registry · GST · product-category permits · MFA')).toBeInTheDocument();
    expect(screen.getByText('Verification: Stripe KYC, business registry, licence for regulated trades, insurance. Median 1.4 days.')).toBeInTheDocument();
  });

  it('sends a guest to the Studio’s onboarding (its own sign-in on the way), or to sign in here first', async () => {
    const { router } = open('/sell?type=kitchen');
    const start = await screen.findByRole('link', { name: /Start as a kitchen/ });
    expect(start).toHaveAttribute('href', `${STUDIO}/onboarding?type=kitchen`);
    expect(screen.getByRole('link', { name: /Start as a provider/ })).toHaveAttribute('href', `${STUDIO}/onboarding?type=provider`);
    expect(screen.getByRole('link', { name: /Start as a seller/ })).toHaveAttribute('href', `${STUDIO}/onboarding?type=seller`);
    const main = screen.getByRole('main');
    expect(within(main).getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/sign-in?next=%2Fsell%3Ftype%3Dkitchen');
    expect(screen.getByRole('link', { name: 'Not a customer yet? Start fresh.' })).toHaveAttribute('href', `${STUDIO}/register?next=%2Fonboarding%3Ftype%3Dkitchen%26new%3D1`);
    await userEvent.click(within(main).getByRole('link', { name: 'Sign in' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-in'));
  });

  it('hands a signed-in customer to the Studio with the same account', async () => {
    viewer = AMARA;
    open('/sell');
    expect(await screen.findByText('Amara Osei')).toBeInTheDocument();
    expect(screen.getByText(/your verified phone, passkey and address carry into the business account/)).toHaveTextContent(/^Logged in as Amara Osei/);
    expect(screen.getByRole('link', { name: /Start as a provider/ })).toHaveAttribute('href', `${STUDIO}/bff/login?next=%2Fonboarding%3Ftype%3Dprovider`);
    expect(screen.getByRole('link', { name: 'Not a customer yet? Start fresh.' })).toHaveAttribute('href', `${STUDIO}/register?next=%2Fonboarding%3Fnew%3D1`);
    expect(within(screen.getByRole('main')).queryByRole('link', { name: 'Sign in' })).toBeNull();
  });

  it('marks the type the footer asked for', async () => {
    open('/sell?type=seller');
    const chosen = await screen.findByRole('heading', { level: 2, name: /Sell products · Chosen/ });
    expect(chosen.closest('li')).toHaveClass('nl-sell-card-chosen');
    expect(screen.getAllByRole('listitem').filter(li => li.classList.contains('nl-sell-card-chosen'))).toHaveLength(1);
  });

  it('names the visitor’s province’s food permit, never a fixed one', async () => {
    localStorage.setItem(SAVED_KEY, JSON.stringify({ label: '1 Main St, Riverton', city: 'Riverton', province: 'BC' }));
    open('/sell');
    expect(await screen.findByText('Verification: British Columbia Food Handling Permit · food-safety certificates · inspection report · allergen attestation · kitchen visit')).toBeInTheDocument();
  });

  it('speaks French', async () => {
    localStorage.setItem(SAVED_KEY, JSON.stringify({ label: '1 rue Principale, Riverton', city: 'Riverton', province: 'QC' }));
    open('/sell', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Vendre ou offrir un service sur Northline' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Commencer comme prestataire/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Commencer comme vendeur/ })).toBeInTheDocument();
    expect(await screen.findByText(/permis de manipulation des aliments du Québec/)).toBeInTheDocument();
    expect(await within(screen.getByRole('main')).findByRole('link', { name: 'Connectez-vous' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Pas encore client ? Repartez de zéro.' })).toBeInTheDocument();
  });

  it('is reached from the footer and the account menu', async () => {
    viewer = AMARA;
    open('/');
    const footer = await screen.findByRole('contentinfo');
    expect(within(footer).getByRole('link', { name: 'Run a kitchen' })).toHaveAttribute('href', '/sell?type=kitchen');
    await userEvent.click(await screen.findByRole('button', { name: 'Account menu' }));
    expect(await screen.findByRole('menuitem', { name: /Sell or offer a service/ })).toHaveAttribute('href', '/sell');
  });
});

describe('Studio links', () => {
  it('build the onboarding paths of 07a–07d', () => {
    expect(onboardingPath()).toBe('/onboarding');
    expect(onboardingPath('provider', true)).toBe('/onboarding?type=provider&new=1');
    expect(studioStart('https://studio.test', 'seller', true)).toBe('https://studio.test/bff/login?next=%2Fonboarding%3Ftype%3Dseller');
    expect(studioFresh('https://studio.test')).toBe('https://studio.test/register?next=%2Fonboarding%3Fnew%3D1');
  });
});
