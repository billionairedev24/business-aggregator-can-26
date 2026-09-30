import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import type { Quote, QuotePage as Page } from './api';
import { QuoteCompare } from './QuoteCompare';
import { QuotePage } from './QuotePage';
import { QuoteRequest, requestComplete, toAsk, type RequestDraft, type RequestStep } from './QuoteRequest';

const user = () => userEvent.setup({ delay: null });
const AMARA = { id: '01J9ZD3V00000000000000AMA1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', initials: 'AO', locale: 'en-CA' };

const Request = () => {
  const { category } = useParams({ strict: false }) as { category: string };
  const search = useSearch({ strict: false }) as { step?: RequestStep; provider?: string };
  const navigate = useNavigate();
  return (
    <QuoteRequest slug={category} step={search.step ?? 'job'} preselect={search.provider}
      onStep={step => void navigate({ to: '/services/$category/quote', params: { category }, search: { step, provider: search.provider } } as never)}
      onSent={requestId => void navigate({ to: '/quotes/requests/$requestId', params: { requestId } } as never)} />
  );
};
const Compare = () => { const { requestId } = useParams({ strict: false }) as { requestId: string }; return <QuoteCompare requestId={requestId} />; };
const One = () => { const { quoteId } = useParams({ strict: false }) as { quoteId: string }; return <QuotePage quoteId={quoteId} />; };
const routes = { quoteRequest: Request, quoteCompare: Compare, quote: One };

const group = { id: 'service.automotive', key: 'automotive', names: { en: 'Automotive' }, note: 'AMVIC licence checked', items: [] };
const mechanic = {
  id: 'service.automotive.mobile-mechanic', slug: 'mobile-mechanic', names: { en: 'Mobile mechanic' }, group, kind: 'visit', vehicle: true,
  regulatedRegistry: 'AMVIC', providers: 4, quoteable: true, jobs: [],
};
const card = (i: number, name: string) => ({
  merchantId: `m${i}`, slug: `p${i}`, name, tier: i === 1 ? 'master' : 'trusted', brandColor: '#2f5d3a', blurb: null, rating: 4.8, reviewCount: 40 + i,
  onTimePct: 95, disputePct: 0.5, rebookPct: 60, fromCents: null, pricingMode: 'quote', instantBook: false, nextAvailable: null, zones: ['Beltline'],
});
const providers = { categorySlug: 'mobile-mechanic', kind: 'visit', area: 'Beltline', city: 'Calgary', items: [card(1, 'Quote Wrench'), card(2, 'Spark Mobile'), card(3, 'Torque Garage'), card(4, 'Fourth Auto')] };

const summary = (i: number, name: string) => ({ merchantId: `m${i}`, slug: `p${i}`, name, tier: 'master', brandColor: '#2f5d3a', rating: 4.8, reviewCount: 96, onTimePct: 98, disputePct: 0.3, verifiedFacts: [] });
const validUntil = new Date(Date.now() + 72 * 3_600_000).toISOString();
const quote = (over: Partial<Quote> = {}): Quote => ({
  id: 'q1', requestId: 'r1', ref: 'QT-3310', merchantId: 'm1', version: 1, state: 'viewed', expired: false,
  scope: 'Confirm the charging fault and replace the alternator.', exclusions: 'Serpentine belt extra if worn.',
  proposedAt: '2026-10-06T16:00:00Z', durationMin: 120, warranty: 'parts_labour_12m', depositKind: 'pct', depositBps: 2500,
  lines: [
    { kind: 'part', description: 'Alternator, remanufactured', note: '12-month warranty', qty: 1, unitCents: 24000, amountCents: 24000, taxable: true },
    { kind: 'labour', description: 'Replace alternator, check belt', note: null, qty: 1.5, unitCents: 13000, amountCents: 19500, taxable: true },
  ],
  subtotalCents: 43500, taxBps: 500, taxCents: 2175, totalCents: 45675, depositCents: 11419,
  sentAt: '2026-10-05T16:00:00Z', validUntil, versions: [{ quoteId: 'q1', version: 1, state: 'viewed', totalCents: 45675, sentAt: '2026-10-05T16:00:00Z' }], currentQuoteId: 'q1',
  ...over,
});
const page = (over: Partial<Page> = {}): Page => ({
  quote: quote(), provider: summary(1, 'Quote Wrench'), title: 'Mobile mechanic · 2018 Honda Civic', area: 'Beltline', bookingId: null,
  others: [{ quoteId: 'q2', providerName: 'Spark Mobile', totalCents: 18900, state: 'sent' }], ...over,
});
const comparison = {
  requestId: 'r1', ref: 'QT-3310', categorySlug: 'mobile-mechanic', title: 'Mobile mechanic · 2018 Honda Civic', description: 'Battery light on.',
  area: 'Beltline', preferredAt: null, createdAt: '2026-10-05T15:00:00Z', respondBy: '2026-10-05T17:00:00Z', expiresAt: '2026-10-12T15:00:00Z',
  offers: [
    { provider: summary(2, 'Spark Mobile'), status: 'quoted', quote: quote({ id: 'q2', merchantId: 'm2', totalCents: 18900, depositCents: 0, depositKind: 'none', lines: [{ kind: 'labour', description: 'Replace alternator (your part)', note: null, qty: 2, unitCents: 9000, amountCents: 18000, taxable: true }] }) },
    { provider: summary(1, 'Quote Wrench'), status: 'quoted', quote: quote({ version: 2 }) },
    { provider: summary(3, 'Torque Garage'), status: 'waiting', quote: null },
  ],
};
const booking = {
  bookingId: 'b1', ref: 'BK-7720', providerName: 'Quote Wrench', providerSlug: 'p1', memberFirstName: 'Kai', title: 'Mobile mechanic · 2018 Honda Civic',
  type: 'visit', startsAt: '2026-10-06T16:00:00Z', endsAt: '2026-10-06T18:00:00Z', addressLine: '1204 17 Ave SW', priceCents: 43500, taxCents: 2175, heldCents: 11419, freeCancelUntil: null,
};

type Reply = { status?: number; body?: unknown } | undefined;
function api(signedIn: boolean, extra: (c: Call) => Reply = () => undefined) {
  return (c: Call): Reply => {
    if (c.url === '/bff/session') return { body: { user: signedIn ? AMARA : null, guestId: 'g_x', sid: signedIn ? 's1' : undefined } };
    const own = extra(c);
    if (own) return own;
    const path = c.url.split('?')[0]!;
    if (path === '/api/v1/public/services/mobile-mechanic') return { body: mechanic };
    if (path === '/api/v1/public/services/mobile-mechanic/providers') return { body: providers };
    if (path === '/api/v1/me/quote-requests' && c.method === 'POST') return { status: 201, body: { requestId: 'r1', ref: 'QT-3310', respondBy: '2026-10-05T17:00:00Z', expiresAt: '2026-10-12T15:00:00Z', providers: 2 } };
    if (path === '/api/v1/me/quote-requests/r1') return { body: comparison };
    if (path === '/api/v1/me/quotes/q1') return { body: page() };
    return undefined;
  };
}

const filled: RequestDraft = { description: 'Battery light on, car died twice this week.', vehicle: { year: '2018', make: 'Honda', model: 'Civic' }, eventDate: '', guests: '', budget: '', preferredDate: '', note: '', providers: [] };

beforeEach(() => { localStorage.clear(); sessionStorage.clear(); });
afterEach(() => vi.unstubAllGlobals());

describe('quote request (design 06 book, quote mode)', () => {
  it('walks a guest through the job and the area, lets them pick up to 3 providers and asks them to sign in to send', async () => {
    mockFetch(api(false));
    renderApp('/services/mobile-mechanic/quote', { routes });
    const u = user();
    expect(await screen.findByRole('heading', { level: 1, name: 'Describe the job, get up to 3 quotes' })).toBeInTheDocument();
    const next = screen.getByRole('button', { name: 'Continue' });
    expect(next).toBeDisabled();
    await u.type(screen.getByLabelText(/^Describe the job ·/), 'Battery light on, car died twice.');
    await u.selectOptions(screen.getByLabelText('Year'), '2018');
    await u.selectOptions(screen.getByLabelText('Make'), 'Honda');
    await u.type(screen.getByLabelText('Model'), 'Civic');
    await u.click(next);

    expect(await screen.findByRole('heading', { level: 1, name: 'Where and when?' })).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Choose who quotes' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Who should quote?' })).toBeInTheDocument();
    const list = await screen.findByRole('list', { name: 'Who should quote?' });
    const rows = within(list).getAllByRole('button');
    for (const row of rows.slice(0, 3)) await u.click(row);
    expect(rows[3]).toBeDisabled();
    expect(screen.getByText('3 of 3 chosen · You can ask up to 3 providers.')).toBeInTheDocument();
    expect(screen.getByText('Sign in to send your request — what you entered is kept.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Send request to 3 providers' })).toBeDisabled();
  });

  it('sends the request with the pre-ticked provider and opens the compare page', async () => {
    sessionStorage.setItem('nl.quote.mobile-mechanic', JSON.stringify(filled));
    const calls = mockFetch(api(true));
    renderApp('/services/mobile-mechanic/quote?step=who&provider=p1', { routes });
    const u = user();
    const list = await screen.findByRole('list', { name: 'Who should quote?' });
    await waitFor(() => expect(within(list).getByRole('button', { name: /Quote Wrench/ })).toHaveAttribute('aria-pressed', 'true'));
    await u.click(within(list).getByRole('button', { name: /Spark Mobile/ }));
    await u.type(screen.getByLabelText('Anything else for the quote?'), 'OEM parts preferred');
    await u.click(screen.getByRole('button', { name: 'Send request to 2 providers' }));

    expect(await screen.findByRole('heading', { level: 1, name: '2 quotes received' })).toBeInTheDocument();
    const sent = calls.find(c => c.url === '/api/v1/me/quote-requests' && c.method === 'POST')!;
    expect(sent.body).toMatchObject({
      category: 'mobile-mechanic', providers: ['p1', 'p2'], description: 'Battery light on, car died twice this week.',
      vehicle: { year: '2018', make: 'Honda', model: 'Civic' }, note: 'OEM parts preferred',
    });
    expect(sessionStorage.getItem('nl.quote.mobile-mechanic')).toBeNull();
  });

  it('shows the server’s validation message', async () => {
    sessionStorage.setItem('nl.quote.mobile-mechanic', JSON.stringify({ ...filled, providers: ['p1'] }));
    mockFetch(api(true, c => (c.url === '/api/v1/me/quote-requests' ? { status: 422, body: { errors: [{ field: 'providers', rule: 'not_offered', message: "One of these providers doesn't offer this service any more. Choose again." }] } } : undefined)));
    renderApp('/services/mobile-mechanic/quote?step=who', { routes });
    const send = await screen.findByRole('button', { name: 'Send request to 1 provider' });
    await waitFor(() => expect(send).toBeEnabled());
    await user().click(send);
    expect(await screen.findByRole('alert')).toHaveTextContent("One of these providers doesn't offer this service any more. Choose again.");
  });
});

describe('compare quotes', () => {
  it('lists each provider asked, cheapest quote first, then the ones still waiting', async () => {
    mockFetch(api(true));
    renderApp('/quotes/requests/r1', { routes });
    expect(await screen.findByRole('heading', { level: 1, name: '2 quotes received' })).toBeInTheDocument();
    const rows = screen.getAllByRole('row').slice(1);
    expect(within(rows[0]!).getByText('Spark Mobile')).toBeInTheDocument();
    expect(within(rows[0]!).getByText('No deposit')).toBeInTheDocument();
    expect(within(rows[0]!).getByRole('link', { name: 'View quote' })).toHaveAttribute('href', '/quotes/q2');
    expect(within(rows[1]!).getByText('v2')).toBeInTheDocument();
    expect(within(rows[1]!).getByText('$114.19 deposit')).toBeInTheDocument();
    expect(within(rows[2]!).getByText('Waiting for a quote')).toBeInTheDocument();
  });
});

describe('quote (design 06 quote)', () => {
  it('shows every line, the totals, scope, exclusions, warranty, deposit and validity', async () => {
    mockFetch(api(true));
    renderApp('/quotes/q1', { routes });
    expect(await screen.findByRole('heading', { level: 1, name: 'Mobile mechanic · 2018 Honda Civic' })).toBeInTheDocument();
    expect(screen.getByText('Quote ready')).toBeInTheDocument();
    expect(screen.getByText(/^Valid until /)).toBeInTheDocument();
    const lines = screen.getAllByRole('table')[0]!;
    expect(within(lines).getByText('Alternator, remanufactured')).toBeInTheDocument();
    expect(within(lines).getByText('12-month warranty')).toBeInTheDocument();
    expect(within(lines).getByText('$195.00')).toBeInTheDocument();
    expect(screen.getByText('$435.00')).toBeInTheDocument();
    expect(screen.getByText('$21.75')).toBeInTheDocument();
    expect(screen.getByText('$456.75')).toBeInTheDocument();
    expect(screen.getByText('Confirm the charging fault and replace the alternator.')).toBeInTheDocument();
    expect(screen.getByText('Serpentine belt extra if worn.')).toBeInTheDocument();
    expect(screen.getByText('Warranty: parts and labour, 12 months')).toBeInTheDocument();
    expect(screen.getByText('25% deposit ($114.19) held in escrow when you accept; the balance ($342.56) is held before the job.')).toBeInTheDocument();
    expect(screen.getByText(/You have 1 other quote for this request: Spark Mobile \$189/)).toBeInTheDocument();
  });

  it('accepts by holding the deposit with an Idempotency-Key, then books it', async () => {
    const calls = mockFetch(api(true, c => {
      if (c.url === '/api/v1/me/quotes/q1/accept') return { body: { quoteId: 'q1', bookingId: 'b1', amountCents: 10875, taxCents: 544, totalCents: 11419, status: 'authorized', paymentIntent: 'pi_fake_1', clientSecret: null, provider: 'fake', publishableKey: null } };
      if (c.url === '/api/v1/me/quotes/q1/accept/confirm') return { status: 201, body: booking };
      return undefined;
    }));
    renderApp('/quotes/q1', { routes });
    const u = user();
    const accept = await screen.findByRole('button', { name: 'Accept · hold $114.19' });
    expect(accept).toBeDisabled();
    await u.type(screen.getByLabelText('Street address'), '1204 17 Ave SW');
    await u.type(screen.getByLabelText('Access instructions (optional)'), 'Gate 4471');
    await u.click(accept);
    expect(await screen.findByText('Accepted. $114.19 held · booking BK-7720 created · Quote Wrench notified.')).toBeInTheDocument();
    const started = calls.find(c => c.url === '/api/v1/me/quotes/q1/accept')!;
    expect(started.headers['idempotency-key']).toBeTruthy();
    expect(started.body).toEqual({ addressLine: '1204 17 Ave SW', accessNote: 'Gate 4471' });
    expect(calls.find(c => c.url === '/api/v1/me/quotes/q1/accept/confirm')!.headers['idempotency-key']).toBe('confirm-q1');
  });

  it('asks for a passkey when the account has no second factor (S-51 rule)', async () => {
    mockFetch(api(true, c => (c.url === '/api/v1/me/quotes/q1/accept' ? { status: 403, body: { code: 'second_factor_required', detail: 'Add a passkey' } } : undefined)));
    renderApp('/quotes/q1', { routes });
    const u = user();
    await u.type(await screen.findByLabelText('Street address'), '1204 17 Ave SW');
    await u.click(screen.getByRole('button', { name: 'Accept · hold $114.19' }));
    expect(await screen.findByRole('dialog', { name: 'Add a passkey to pay' })).toBeInTheDocument();
  });

  it('says so when the provider revised the quote meanwhile', async () => {
    mockFetch(api(true, c => (c.url === '/api/v1/me/quotes/q1/accept' ? { status: 409, body: { code: 'quote_revised', detail: 'revised' } } : undefined)));
    renderApp('/quotes/q1', { routes });
    const u = user();
    await u.type(await screen.findByLabelText('Street address'), '1204 17 Ave SW');
    await u.click(screen.getByRole('button', { name: 'Accept · hold $114.19' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The provider just revised this quote. Review the new version before accepting.');
  });

  it('points a superseded version to the newest one and offers no accept', async () => {
    mockFetch(api(true, c => (c.url.startsWith('/api/v1/me/quotes/q1?') ? { body: page({
      quote: quote({ state: 'superseded', currentQuoteId: 'q3', versions: [
        { quoteId: 'q3', version: 2, state: 'sent', totalCents: 50000, sentAt: null },
        { quoteId: 'q1', version: 1, state: 'superseded', totalCents: 45675, sentAt: null },
      ] }),
    }) } : undefined)));
    renderApp('/quotes/q1', { routes });
    expect(await screen.findByRole('note')).toHaveTextContent('Quote Wrench revised this quote — version 2 replaces it.');
    expect(screen.getByRole('link', { name: 'See version 2' })).toHaveAttribute('href', '/quotes/q3');
    expect(screen.getByText('This version was replaced. Accept the newest one.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Accept/ })).toBeNull();
    expect(screen.getByRole('link', { name: 'Version 2 · $500.00' })).toHaveAttribute('href', '/quotes/q3');
  });

  it('reads in French', async () => {
    mockFetch(api(true));
    renderApp('/quotes/q1', { routes, locale: 'fr' });
    expect(await screen.findByText('Devis prêt')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Ce qui est compris' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Accepter · retenir/ })).toBeInTheDocument();
  });
});

describe('quote rules', () => {
  it('needs the description, the vehicle for a mechanic and the date and guests for an event', () => {
    expect(requestComplete('job', { ...filled, description: 'short' }, 'visit', true)).toBe(false);
    expect(requestComplete('job', filled, 'visit', true)).toBe(true);
    expect(requestComplete('job', { ...filled, vehicle: { year: '', make: '', model: '' } }, 'event', false)).toBe(false);
    expect(requestComplete('job', { ...filled, eventDate: '2026-12-12', guests: '40' }, 'event', false)).toBe(true);
    expect(requestComplete('who', filled, 'visit', true)).toBe(false);
  });

  it('sends only what the kind of job needs', () => {
    expect(toAsk({ ...filled, providers: ['p1'], eventDate: '2026-12-12', guests: '40' }, 'mobile-mechanic', 'visit', true, 'Beltline'))
      .toEqual({ category: 'mobile-mechanic', providers: ['p1'], description: filled.description, vehicle: { year: '2018', make: 'Honda', model: 'Civic' }, eventDate: undefined, guests: undefined, budget: undefined, note: undefined, area: 'Beltline', preferredDate: undefined });
    expect(toAsk({ ...filled, eventDate: '2026-12-12', guests: '40' }, 'cocktail-bar', 'event', false, undefined))
      .toMatchObject({ vehicle: undefined, eventDate: '2026-12-12', guests: 40 });
  });
});
