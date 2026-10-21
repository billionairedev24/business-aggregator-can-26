import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { AccountScreen } from '../account/AccountScreen';
import { HomeScreen } from '../home/HomeScreen';
import { ItemCard } from '../search/ItemCard';
import { CartPage } from '../cart/CartPage';
import { SAVED_KEY } from '../location/useDeliveryLocation';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

/** S-152: points, Invite a neighbour, Plus, rewards near you and Plus prices on the consumer site. */

const AMARA = { id: 'C1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: '+14035550148', initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };
const WALLET = { points: { balance: 1240, valueCents: 1240, weekly: [120, 340, 90, 410, 260, 180, 520, 300] }, plus: null };
const POINTS = {
  balance: 1240, valueCents: 1240, nextExpiryAt: '2027-04-20T12:00:00Z', nextExpiryPoints: 120, pointsPerDollarSpent: 2,
  redeemPointsPerDollar: 100, expiryMonths: 18, plusMultiplierPct: 100,
  activity: [
    { id: 'P3', type: 'reward', points: 400, note: '3× points', at: '2026-10-18T15:00:00Z', expiresAt: '2028-04-18T15:00:00Z', fundedBy: 'M1' },
    { id: 'P2', type: 'earn', points: 200, note: 'Brake inspection', at: '2026-10-18T15:00:00Z', expiresAt: '2028-04-18T15:00:00Z', fundedBy: null },
    { id: 'P1', type: 'redemption', points: -980, note: 'Order', at: '2026-10-01T15:00:00Z', expiresAt: null, fundedBy: null },
  ],
};
const REFERRAL = { code: 'AMARAK26', shareUrl: 'http://localhost:3000/?invite=AMARAK26', pointsEach: 1000, minOrderCents: 2000, pending: 1, rewarded: 2, pointsEarned: 2000, claimed: null, canClaim: true };
const PLUS_NONE = {
  status: 'none', plan: null, provider: 'local', since: null, currentPeriodEnd: null, cancelAtPeriodEnd: false, payer: false, members: 0,
  monthlyPriceCents: 799, annualPriceCents: 6900, trialDays: 30, trialAvailable: true,
  benefits: { member: false, serviceFeeWaived: true, memberPrices: true, pointsMultiplierPct: 200 }, clientSecret: null, publishableKey: null,
};
const PLUS_TRIAL = { ...PLUS_NONE, status: 'trialing', plan: 'annual', since: '2026-10-20T00:00:00Z', currentPeriodEnd: '2026-11-19T12:00:00Z', payer: true, members: 2, trialAvailable: false, benefits: { ...PLUS_NONE.benefits, member: true } };

const CARDS: unknown[] = [];
const PERIODS = [
  { id: 'PR2', startsAt: '2026-11-19T12:00:00Z', endsAt: '2026-12-19T12:00:00Z', plan: 'monthly', state: 'scheduled', amountCents: 799, taxCents: 0, paidAt: null },
  { id: 'PR1', startsAt: '2026-10-19T12:00:00Z', endsAt: '2026-11-19T12:00:00Z', plan: 'monthly', state: 'paid', amountCents: 799, taxCents: 40, paidAt: '2026-10-19T12:00:00Z' },
];
const PLUS_PAID = {
  ...PLUS_TRIAL, status: 'active', plan: 'monthly', nextChargeAt: '2026-11-19T12:00:00Z', nextChargeCents: 799,
  card: { id: 'pm_1', brand: 'visa', last4: '4242', chosen: false }, history: PERIODS,
};

type Reply = { status?: number; body?: unknown } | undefined;
let calls: Call[];
let plus: unknown;
let claimReply: Reply;
let joinReply: Reply;
const server = (c: Call): Reply => {
  const u = c.url;
  if (u === '/bff/session') return { body: { user: AMARA, guestId: 'g', sid: 'S1' } };
  if (u === '/api/v1/me/account-summary') return { body: {} };
  if (u === '/api/v1/me/wallet') return { body: WALLET };
  if (u.startsWith('/api/v1/me/points')) return { body: POINTS };
  if (u === '/api/v1/me/payment-methods') return { body: { provider: 'fake', publishableKey: null, items: CARDS } };
  if (u === '/api/v1/me/referral') return { body: REFERRAL };
  if (u === '/api/v1/me/referral/claim') return claimReply;
  if (u === '/api/v1/me/plus' && c.method === 'GET') return { body: plus };
  if (u === '/api/v1/me/plus' && c.method === 'POST') { const r = joinReply ?? { status: 201, body: PLUS_TRIAL }; if (!r.status || r.status < 300) plus = r.body; return r; }
  if (u === '/api/v1/me/plus' && c.method === 'PATCH') { plus = { ...PLUS_TRIAL, plan: (c.body as { plan: string }).plan }; return { body: plus }; }
  if (u === '/api/v1/me/plus' && c.method === 'DELETE') { plus = { ...PLUS_TRIAL, cancelAtPeriodEnd: true }; return { body: plus }; }
  if (u === '/api/v1/me/plus/resume') { plus = PLUS_TRIAL; return { body: plus }; }
  if (u === '/api/v1/me/plus/card') { const id = (c.body as { paymentMethodId: string | null }).paymentMethodId; plus = { ...(plus as object), card: { id: id ?? 'pm_1', brand: 'visa', last4: id ? '4444' : '4242', chosen: id !== null } }; return { body: plus }; }
  if (u === '/api/v1/me/plus/confirm') { plus = PLUS_PAID; return { body: plus }; }
  return undefined;
};

const account = (tab: string, locale: 'en' | 'fr' = 'en') => renderApp(`/account?tab=${tab}`, { locale, routes: { account: () => <AccountScreen /> } });
const sent = (method: string, url: string) => calls.filter(c => c.method === method && c.url === url);

beforeEach(() => {
  localStorage.clear();
  plus = PLUS_NONE;
  claimReply = undefined;
  joinReply = undefined;
  calls = mockFetch(server);
});
afterEach(() => { vi.unstubAllGlobals(); });

describe('points (Wallet & points)', () => {
  it('shows the rules, what expires next and the activity', async () => {
    account('wallet');
    expect(await screen.findByText(/^120 points expire on Apr 20/)).toBeInTheDocument();
    expect(screen.getByText('Completed orders and bookings earn 2 points for every dollar you pay the business, once its money is released.')).toBeInTheDocument();
    expect(screen.getByText('100 points = $1 off at checkout, before tax.')).toBeInTheDocument();
    expect(screen.getByText('Points expire 18 months after you earn them.')).toBeInTheDocument();
    const activity = screen.getByRole('heading', { name: 'Points activity' }).nextElementSibling as HTMLElement;
    const rows = within(activity).getAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('Business bonus');
    expect(rows[0]).toHaveTextContent('+400');
    expect(rows[2]).toHaveTextContent('Spent at checkout');
    expect(rows[2]).toHaveTextContent('−980');
    await expectNoAxeViolations(document.body);
  });

  it('in French', async () => {
    account('wallet', 'fr');
    expect(await screen.findByText(/^120 points expirent le 20 avr/)).toBeInTheDocument();
    expect(screen.getByText('Prime de l’entreprise')).toBeInTheDocument();
  });

  it('an error + Retry', async () => {
    let fail = true;
    calls = mockFetch(c => (c.url.startsWith('/api/v1/me/points') ? (fail ? { status: 503, body: {} } : { body: POINTS }) : server(c)));
    account('wallet');
    await screen.findByText('1,240 pts');
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Points activity')).toBeInTheDocument();
  });
});

describe('Invite a neighbour', () => {
  it('shows the code, the rules and how it went', async () => {
    account('invite');
    expect(await screen.findByRole('heading', { level: 1, name: 'Invite a neighbour' })).toBeInTheDocument();
    expect(await screen.findByText('AMARAK26')).toBeInTheDocument();
    expect(screen.getByText('Share your code. When your neighbour’s first order of $20.00 or more is completed, you both get 1,000 points ($10.00).')).toBeInTheDocument();
    expect(screen.getByText('1 neighbour waiting · 2 rewarded · 2,000 points earned')).toBeInTheDocument();
    await expectNoAxeViolations(document.body);
  });

  it('copies the link', async () => {
    const writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    account('invite');
    await userEvent.click(await screen.findByRole('button', { name: 'Copy link' }));
    expect(writeText).toHaveBeenCalledWith('http://localhost:3000/?invite=AMARAK26');
    expect(await screen.findByRole('status')).toHaveTextContent('Link copied');
  });

  it('a new neighbour uses a code from an invite link; the server’s reason shows under the field', async () => {
    localStorage.setItem('nl.invite', 'NEIGHB22');
    claimReply = { status: 422, body: { errors: [{ field: 'code', rule: 'own', message: 'That’s your own invite code: share it with a neighbour instead.' }] } };
    account('invite');
    const field = await screen.findByLabelText('Invite code');
    expect(field).toHaveValue('NEIGHB22');
    await userEvent.click(screen.getByRole('button', { name: 'Use code' }));
    expect(await screen.findByText('That’s your own invite code: share it with a neighbour instead.')).toBeInTheDocument();
    claimReply = { body: { ...REFERRAL, canClaim: false, claimed: { code: 'NEIGHB22', state: 'pending', claimedAt: '2026-10-20T00:00:00Z', reason: null } } };
    await userEvent.click(screen.getByRole('button', { name: 'Use code' }));
    expect(await screen.findByText('You used code NEIGHB22. Your points arrive after your first completed order of $20.00 or more.')).toBeInTheDocument();
    expect(sent('POST', '/api/v1/me/referral/claim').map(c => c.body)).toEqual([{ code: 'NEIGHB22' }, { code: 'NEIGHB22' }]);
    expect(localStorage.getItem('nl.invite')).toBeNull();
  });

  it('in French', async () => {
    account('invite', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Inviter un voisin' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Copier le lien' })).toBeInTheDocument();
  });
});

describe('Plus', () => {
  it('shows the plans, the real benefits and starts the trial with an Idempotency-Key', async () => {
    account('plus');
    expect(await screen.findByText('$0 service fee on food orders')).toBeInTheDocument();
    expect(screen.getByText('2× points on everything you order and book')).toBeInTheDocument();
    expect(screen.getByText('Member prices at shops that offer them')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /^Annual · \$69\.00\/year/ }));
    expect(screen.getByText(/^Then \$69\.00\/year · cancel any time/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Start 30-day free trial' }));
    expect(await screen.findByText(/Free trial · ends Nov 19/)).toBeInTheDocument();
    const joined = sent('POST', '/api/v1/me/plus');
    expect(joined.map(c => c.body)).toEqual([{ plan: 'annual' }]);
    expect(joined[0]!.headers['idempotency-key']).toBeTruthy();
    await expectNoAxeViolations(document.body);
  });

  it('without a saved card, says to add one first', async () => {
    joinReply = { status: 409, body: { code: 'card_required', detail: 'Add a card in Payment methods first: Plus renews on it.' } };
    account('plus');
    await userEvent.click(await screen.findByRole('button', { name: 'Start 30-day free trial' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Plus renews on your default card.');
    expect(within(alert).getByRole('link', { name: 'Add a card first' })).toHaveAttribute('href', '/account?tab=payments');
  });

  it('the payer switches plans, cancels to the period’s end and keeps it', async () => {
    plus = PLUS_TRIAL;
    account('plus');
    await userEvent.click(await screen.findByRole('button', { name: 'Switch to Monthly' }));
    await waitFor(() => expect(sent('PATCH', '/api/v1/me/plus').map(c => c.body)).toEqual([{ plan: 'monthly' }]));
    await userEvent.click(screen.getByRole('button', { name: 'Cancel Plus' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(dialog).toHaveTextContent(/Your household keeps Plus until Nov 19.*Nothing more is charged\./);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel Plus' }));
    expect(await screen.findByText(/Cancelled · ends Nov 19/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Keep Plus' }));
    await waitFor(() => expect(sent('POST', '/api/v1/me/plus/resume')).toHaveLength(1));
  });

  it('others of the household see it without the buttons', async () => {
    plus = { ...PLUS_TRIAL, payer: false };
    account('plus');
    expect(await screen.findByText('The person who started Plus for your household manages it.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel Plus' })).toBeNull();
  });

  it('S-162: the next payment, the card Plus renews on, and the payments so far', async () => {
    CARDS.splice(0, CARDS.length,
      { id: 'pm_1', brand: 'visa', last4: '4242', expMonth: 12, expYear: 2030, isDefault: true, addedAt: '2026-10-01T00:00:00Z' },
      { id: 'pm_2', brand: 'mastercard', last4: '4444', expMonth: 1, expYear: 2031, isDefault: false, addedAt: '2026-10-02T00:00:00Z' });
    plus = PLUS_PAID;
    account('plus');
    expect(await screen.findByText(/Next payment \$7\.99 on Nov 19, plus tax\./)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Your default card' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('$7.99 + $0.40 tax')).toBeInTheDocument();
    expect(screen.getByText('Upcoming')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'MASTERCARD ending 4444' }));
    await waitFor(() => expect(sent('PUT', '/api/v1/me/plus/card').map(c => c.body)).toEqual([{ paymentMethodId: 'pm_2' }]));
    expect(await screen.findByRole('button', { name: 'MASTERCARD ending 4444' })).toHaveAttribute('aria-pressed', 'true');
    await expectNoAxeViolations(document.body);
    CARDS.splice(0, CARDS.length);
  });

  it('S-162: a plan change waits for the next renewal, and can be undone', async () => {
    plus = { ...PLUS_PAID, nextPlan: 'annual', nextChargeCents: 6900 };
    account('plus');
    expect(await screen.findByText(/Switches to Annual on Nov 19\./)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Stay on Monthly' }));
    await waitFor(() => expect(sent('PATCH', '/api/v1/me/plus').map(c => c.body)).toEqual([{ plan: 'monthly' }]));
  });

  it('S-162: a declined renewal says when it’s tried again', async () => {
    plus = { ...PLUS_PAID, status: 'past_due', payment: { periodId: 'PR2', reason: 'card_declined', totalCents: 839, retryAt: '2026-11-20T12:00:00Z', lapsesAt: '2026-11-24T12:00:00Z' } };
    account('plus');
    expect(await screen.findByText('Your card was declined for $8.39. We’ll try again on Nov 20, or choose another card.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirm the payment' })).toBeNull();
  });

  it('S-162: a payment the bank wants confirmed — Stripe.js, then the api reads it again', async () => {
    const confirmCardPayment = vi.fn(async () => ({}));
    vi.stubGlobal('Stripe', () => ({ confirmCardPayment }));
    plus = {
      ...PLUS_PAID, status: 'past_due', clientSecret: 'pi_1_secret_x', publishableKey: 'pk_test_fake',
      payment: { periodId: 'PR2', reason: 'authentication_required', totalCents: 839, retryAt: '2026-11-20T12:00:00Z', lapsesAt: '2026-11-24T12:00:00Z' },
    };
    account('plus');
    expect(await screen.findByText('Your bank wants you to confirm the payment of $8.39.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Confirm the payment' }));
    await waitFor(() => expect(sent('POST', '/api/v1/me/plus/confirm')).toHaveLength(1));
    expect(confirmCardPayment).toHaveBeenCalledWith('pi_1_secret_x');
    expect(await screen.findByText('Thanks — the payment went through.')).toBeInTheDocument();
  });

  it('in French', async () => {
    account('plus', 'fr');
    expect(await screen.findByText('Aucuns frais de service sur les commandes de repas')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Commencer l’essai gratuit de 30 jours' })).toBeInTheDocument();
  });
});

describe('rewards near you and Plus prices', () => {
  it('home lists the businesses paying extra points where the person is', async () => {
    localStorage.setItem(SAVED_KEY, JSON.stringify({ label: 'Beltline, Calgary', city: 'Calgary', province: 'AB' }));
    calls = mockFetch(c => {
      if (c.url === '/bff/session') return { body: { user: null, guestId: 'g_1' } };
      if (c.url.startsWith('/api/v1/search?')) return { body: { items: [{ id: 'O1', kind: 'product', name: 'Loaf', description: null, merchant: { id: 'm', name: 'Bakery', type: 'seller', slug: null, tier: 'master' }, category: null, priceCents: 750, pricingMode: 'fixed', rating: null, reviewCount: 0, trustTier: 'registered', distanceKm: null, instantBook: false, fulfilment: [], openNow: false, soldOut: false, onTonightsRun: false, prepMinutes: null, dietary: [], imageKey: null }], total: 1, facets: null, next: null } };
      if (c.url.startsWith('/api/v1/public/rewards?')) return { body: { items: [{ merchantId: 'M1', businessName: 'Prairie Wrench', type: 'provider', slug: 'prairie-wrench', multiplier: 2, label: 'brake jobs', endsOn: '2026-11-19', distanceKm: 2.4 }] } };
      if (c.url.startsWith('/api/v1/public/plus-prices?')) return { body: {} };
      if (c.url === '/api/v1/cart') return { body: { itemCount: 0, shopCount: 0, subtotalCents: 0, groups: [] } };
      return undefined;
    });
    renderApp('/?invite=FRIEND22', { routes: { home: () => <HomeScreen /> } });
    const section = await screen.findByRole('region', { name: 'Rewards near you' });
    expect(within(section).getByRole('link', { name: /Prairie Wrench/ })).toHaveAttribute('href', '/providers/prairie-wrench');
    expect(section).toHaveTextContent('2× points on brake jobs · until Nov 19');
    expect(calls.find(c => c.url.startsWith('/api/v1/public/rewards?'))!.url).toContain('city=Calgary');
    await waitFor(() => expect(localStorage.getItem('nl.invite')).toBe('FRIEND22'));
  });

  it('a product card shows the shop’s Plus price, one request for the page’s cards', async () => {
    calls = mockFetch(c => {
      if (c.url === '/bff/session') return { body: { user: null, guestId: 'g_1' } };
      if (c.url.startsWith('/api/v1/public/plus-prices?')) return { body: { O1: 10 } };
      if (c.url === '/api/v1/cart') return { body: { itemCount: 0, shopCount: 0, subtotalCents: 0, groups: [] } };
      return undefined;
    });
    const card = (id: string, name: string) => ({ id, kind: 'product' as const, name, description: null, merchant: { id: 'm', name: 'Bakery', type: 'seller', slug: null, tier: 'master' }, category: null, priceCents: 850, pricingMode: 'fixed', rating: null, reviewCount: 0, trustTier: 'registered', distanceKm: null, instantBook: false, fulfilment: [], openNow: false, soldOut: false, onTonightsRun: false, prepMinutes: null, dietary: [], imageKey: null });
    renderApp('/search', { routes: { search: () => <><ItemCard item={card('O1', 'Rye') as never} /><ItemCard item={card('O2', 'Wheat') as never} /></> } });
    expect(await screen.findByText('Plus $7.65')).toBeInTheDocument();
    expect(calls.filter(c => c.url.startsWith('/api/v1/public/plus-prices?')).map(c => c.url)).toEqual(['/api/v1/public/plus-prices?offers=O1,O2']);
  });

  it('the cart prices a member’s lines with the Plus price and says what it saved; others see the offer', async () => {
    const line = (itemId: string, memberPrice: boolean) => ({
      itemId, merchantId: 'M1', offerId: `O-${itemId}`, variantId: null, productId: 'P1', name: `Rye ${itemId}`, option: null, unit: null, imageUrl: null,
      unitCents: memberPrice ? 765 : 850, qty: 2, lineCents: memberPrice ? 1530 : 1700, stock: 9, available: true, handlingDays: 0,
      listUnitCents: 850, memberUnitCents: 765, memberPrice,
    });
    calls = mockFetch(c => {
      if (c.url === '/bff/session') return { body: { user: AMARA, guestId: 'g', sid: 'S1' } };
      if (c.url === '/api/v1/cart') return { body: { itemCount: 4, shopCount: 1, subtotalCents: 3230, plusSavingsCents: 170, groups: [{ merchantId: 'M1', shopName: 'Bakery', items: [line('A', true), line('B', false)] }] } };
      return undefined;
    });
    renderApp('/cart', { routes: { cart: () => <CartPage /> } });
    expect(await screen.findByText('Plus price $7.65 (was $8.50)')).toBeInTheDocument();
    expect(screen.getByText('Plus members pay $7.65')).toBeInTheDocument();
    expect(screen.getByText('Plus member prices saved you $1.70.')).toBeInTheDocument();
  });
});
