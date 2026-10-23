import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useParams } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { ago, ProviderPage } from './ProviderPage';
import type { ProviderFacts, Storefront } from './api';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const user = () => userEvent.setup({ delay: null });
const Provider = () => { const { slug } = useParams({ strict: false }) as { slug: string }; return <ProviderPage slug={slug} />; };
const routes = { provider: Provider };
const guest = (c: Call) => (c.url === '/bff/session' ? { body: { user: null, guestId: 'g_x' } } : undefined);

const page: Storefront = {
  slug: 'prairie-wrench', url: 'northline.ca/prairie-wrench', pageKind: 'business_page', brandColor: '#2f5d3a', logoUrl: null,
  tagline: 'Mobile mechanic · Calgary & Airdrie', ctaLabel: 'book_visit', announcement: 'Winter tire swaps: book before Oct 15 for $99',
  customDomain: null, publishedAt: '2026-01-08T16:00:00Z',
  sections: [
    { kind: 'hero', settings: {} }, { kind: 'about', settings: {} }, { kind: 'services', settings: {} }, { kind: 'reviews', settings: {} },
    { kind: 'faq', settings: { pairs: [{ q: 'Do you work in parkades?', a: 'Yes — up to 2.1 m clearance.' }] } }, { kind: 'area', settings: {} }, { kind: 'cta', settings: {} },
  ],
  business: { displayName: 'Prairie Wrench', type: 'provider', tier: 'master', city: 'Calgary', about: 'Mobile mechanic since 2019. Red Seal, AMVIC-licensed.', serviceArea: null, verifiedFacts: [] },
};
const review = (i: number) => ({ id: `r${i}`, rating: 5, text: `Great job number ${i}.`, author: `Customer ${i}`, jobLabel: 'Brake inspection', refType: 'booking', createdAt: new Date(Date.now() - 3 * 86_400_000).toISOString(), reply: i === 1 ? 'Thanks!' : null });
const facts: ProviderFacts = {
  merchantId: 'm1', slug: 'prairie-wrench', name: 'Prairie Wrench', tier: 'master', city: 'Calgary', since: '2019-05-01T00:00:00Z',
  verifiedFacts: ['kyc', 'licence:AMVIC', 'insurance'], rating: 4.9, reviewCount: 312, onTimePct: 98, disputePct: 0.3, rebookPct: 71,
  kind: 'visit', category: { id: 'service.automotive.mobile-mechanic', slug: 'mobile-mechanic', names: { en: 'Mobile mechanic' } }, vehicle: true, quoteable: true,
  services: [
    { id: 's1', name: 'Brake inspection', included: null, pricingMode: 'fixed', priceCents: 8900, durationMin: 60, instantBook: true, categorySlug: 'brakes-and-suspension', kind: 'visit' },
    { id: 's2', name: 'Alternator', included: null, pricingMode: 'quote', priceCents: null, durationMin: 90, instantBook: false, categorySlug: 'mobile-mechanic', kind: 'visit' },
  ],
  zones: ['Beltline', 'Downtown'], nextAvailable: null,
  taxBps: 500, reviews: { items: [review(1), review(2), review(3)], nextOffset: 3 },
};

/** The public calendar for whatever days the booking box asks: two slots a day (the second taken), the third day full. */
function slots(url: string) {
  const q = new URL(url, 'http://x').searchParams;
  const from = q.get('from')!;
  const days = Array.from({ length: Number(q.get('days')) }, (_, i) => {
    const d = new Date(`${from}T12:00:00Z`); d.setUTCDate(d.getUTCDate() + i);
    const date = d.toISOString().slice(0, 10);
    const s = i === 2 ? [] : [{ startsAt: `${date}T16:00:00Z`, free: true }, { startsAt: `${date}T17:30:00Z`, free: false }];
    return { date, closed: null, free: s.filter(x => x.free).length, slots: s };
  });
  return { serviceId: q.get('serviceId'), durationMin: 60, days, timeZone: 'America/Halifax', cancellation: { windowHours: 24, feeBps: 5000, feeCents: 0 } };
}

function api(over: { page?: Partial<Storefront>; facts?: Partial<ProviderFacts> } = {}) {
  return (c: Call) => guest(c) ?? (() => {
    if (c.url === '/api/v1/storefronts/prairie-wrench') return { body: { ...page, ...over.page } };
    if (c.url.startsWith('/api/v1/public/providers/prairie-wrench/reviews')) return { body: { items: [review(4)], nextOffset: null } };
    if (c.url.startsWith('/api/v1/public/providers/prairie-wrench/slots')) return { body: slots(c.url) };
    if (c.url.startsWith('/api/v1/public/providers/prairie-wrench')) return { body: { ...facts, ...over.facts } };
    return undefined;
  })();
}

afterEach(() => vi.unstubAllGlobals());

describe('provider page (E-15 S-172, design/revamp/a.html)', () => {
  it('renders the header, the trust badges, the tabs and the services as one-choice cards', async () => {
    mockFetch(api());
    renderApp('/providers/prairie-wrench', { routes });
    expect(await screen.findByRole('heading', { level: 1, name: 'Prairie Wrench' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body); // S-109
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(crumbs).getByRole('link', { name: 'Mobile mechanic' })).toHaveAttribute('href', '/services/mobile-mechanic');
    expect(screen.getByText('Mobile mechanic · Beltline, Downtown')).toBeInTheDocument();
    expect(screen.getByText('Mobile mechanic · Calgary & Airdrie · since 2019 · Master tier · verified')).toBeInTheDocument();
    expect(screen.getByRole('note')).toHaveTextContent('Winter tire swaps: book before Oct 15 for $99');
    expect(screen.getByRole('img', { name: '4.9 out of 5 stars' })).toBeInTheDocument();
    expect(screen.getByText('98% on time · 71% book again')).toBeInTheDocument();
    for (const tag of ['ID verified', 'AMVIC licensed', '$2M insured', 'instant book']) expect(screen.getByText(tag)).toBeInTheDocument();
    const tabs = screen.getByRole('tablist', { name: 'About Prairie Wrench' });
    expect(within(tabs).getAllByRole('tab').map(x => x.textContent)).toEqual(['Services2', 'Reviews312', 'About']);
    expect(within(tabs).getByRole('tab', { name: /Services/ })).toHaveAttribute('aria-selected', 'true');
    const services = screen.getByRole('radiogroup', { name: 'Choose a service' });
    expect(within(services).getByRole('radio', { name: /Brake inspection/ })).toHaveAttribute('aria-checked', 'true');
    expect(within(services).getByRole('radio', { name: /Alternator.*quoted before work/ })).toBeInTheDocument();
    const neighbours = screen.getByRole('region', { name: 'What neighbours say' });
    expect(within(neighbours).getByText('“Great job number 1.”')).toBeInTheDocument();
    expect(within(neighbours).getByText('Customer 1 · verified booking · 3 days ago')).toBeInTheDocument();
    expect(within(neighbours).getByText(/Reply from Prairie Wrench/)).toBeInTheDocument();
  });

  it('shows the about, the figures, the FAQ and the area under About, and every review under Reviews', async () => {
    mockFetch(api());
    renderApp('/providers/prairie-wrench', { routes });
    await user().click(await screen.findByRole('tab', { name: /About/ }));
    expect(screen.getByText('Mobile mechanic since 2019. Red Seal, AMVIC-licensed.')).toBeInTheDocument();
    expect(screen.getByText('312 verified reviews')).toBeInTheDocument();
    expect(screen.getByText('0.3%')).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 2 }).map(h => h.textContent)).toEqual(expect.arrayContaining(['About Prairie Wrench', 'FAQ', 'Service area']));
    expect(within(screen.getByRole('region', { name: 'Service area' })).getByText('Comes to you in Beltline · Downtown.')).toBeInTheDocument();
    expect(screen.getByText('Do you work in parkades?')).toBeInTheDocument();
    await user().click(screen.getByRole('tab', { name: /Reviews/ }));
    expect(screen.getByRole('heading', { name: 'Reviews · 312' })).toBeInTheDocument();
    expect(screen.getByText('“Great job number 3.”')).toBeInTheDocument();
  });

  it('books from the box: a day, a time, then "Book <day> at <time>" opens the wizard with them chosen', async () => {
    sessionStorage.clear();
    mockFetch(api());
    const { router } = renderApp('/providers/prairie-wrench', { routes: { ...routes, book: () => <h1>Booking wizard</h1> } });
    const box = await screen.findByRole('complementary', { name: 'Book a time' });
    expect(within(box).getAllByText('Brake inspection')).toHaveLength(2); // the choice and the price line
    expect(within(box).getByText('60 min · $89')).toBeInTheDocument();
    expect(within(box).getByRole('button', { name: 'Choose a time' })).toBeDisabled();
    const days = await within(box).findByRole('radiogroup', { name: 'Choose a day' });
    const choices = within(days).getAllByRole('radio');
    expect(choices).toHaveLength(5);
    expect(choices[2]).toBeDisabled(); // nothing free that day
    await user().click(choices[1]!);
    expect(choices[1]).toHaveAttribute('aria-checked', 'true');
    const times = within(box).getByRole('radiogroup', { name: /^Times on / });
    expect(within(times).getByRole('radio', { name: '2:30 p.m.' })).toBeDisabled(); // taken (Halifax time, the calendar's zone)
    await user().click(within(times).getByRole('radio', { name: '1:00 p.m.' }));
    expect(within(box).getByText('Comes to you')).toBeInTheDocument();
    expect(within(box).getByText('Included')).toBeInTheDocument();
    expect(within(box).getByText('$4.45')).toBeInTheDocument(); // 5 % on $89
    expect(within(box).getByText('Your card is held, not charged, until you confirm the job is done.')).toBeInTheDocument();
    const book = within(box).getByRole('button', { name: /^Book .+ at 1:00 p\.m\.$/ });
    await user().click(book);
    expect(await screen.findByRole('heading', { name: 'Booking wizard' })).toBeInTheDocument();
    expect(router.state.location.search).toEqual({ service: 's1' });
    const draft = JSON.parse(sessionStorage.getItem('nl.book.prairie-wrench')!);
    expect(draft).toMatchObject({ serviceId: 's1', timeZone: 'America/Halifax', cancellation: { windowHours: 24 } });
    expect(draft.startsAt).toMatch(/T16:00:00Z$/);
  });

  it('asks for a quote when the chosen service is quoted', async () => {
    mockFetch(api());
    renderApp('/providers/prairie-wrench', { routes });
    await user().click(await screen.findByRole('radio', { name: /Alternator/ }));
    const box = screen.getByRole('complementary', { name: 'Book a time' });
    expect(within(box).getByRole('link', { name: 'Request a quote' })).toHaveAttribute('href', '/services/mobile-mechanic/quote?provider=prairie-wrench');
    expect(within(box).queryByRole('radiogroup', { name: 'Choose a day' })).not.toBeInTheDocument();
  });

  it('leaves out sections the business turned off, and says so when it has no reviews yet', async () => {
    mockFetch(api({ page: { sections: [{ kind: 'hero', settings: {} }, { kind: 'reviews', settings: {} }, { kind: 'cta', settings: {} }], announcement: null }, facts: { reviewCount: 0, reviews: { items: [], nextOffset: null } } }));
    renderApp('/providers/prairie-wrench', { routes });
    expect(await screen.findByRole('heading', { name: 'Reviews · 0' })).toBeInTheDocument();
    expect(screen.getAllByText('New on Northline — verified').length).toBeGreaterThan(0);
    expect(screen.queryByRole('tab', { name: /Services/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Brake inspection')).not.toBeInTheDocument();
    expect(screen.queryByRole('note')).not.toBeInTheDocument();
    const aside = screen.getByRole('complementary', { name: 'Book Prairie Wrench' });
    expect(within(aside).getByRole('link', { name: 'Book a visit' })).toHaveAttribute('href', '/providers/prairie-wrench/book');
    expect(within(aside).getByRole('link', { name: 'Not sure? Request a quote' })).toBeInTheDocument();
    await user().click(screen.getByRole('tab', { name: /About/ }));
    expect(screen.queryByText('Mobile mechanic since 2019. Red Seal, AMVIC-licensed.')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Service area' })).not.toBeInTheDocument();
  });

  it('loads more reviews on demand', async () => {
    const calls = mockFetch(api());
    renderApp('/providers/prairie-wrench', { routes });
    await user().click(await screen.findByRole('tab', { name: /Reviews/ }));
    await user().click(screen.getByRole('button', { name: 'Show more reviews' }));
    expect(await screen.findByText('“Great job number 4.”')).toBeInTheDocument();
    expect(calls.some(c => c.url === '/api/v1/public/providers/prairie-wrench/reviews?offset=3&limit=10')).toBe(true);
    expect(screen.queryByRole('button', { name: 'Show more reviews' })).not.toBeInTheDocument();
  });

  it('reads in French', async () => {
    mockFetch(api());
    renderApp('/providers/prairie-wrench', { routes, locale: 'fr' });
    expect(await screen.findByText(/Niveau Maître · vérifié/)).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '4,9 sur 5 étoiles' })).toBeInTheDocument();
    expect(screen.getByText('98 % à l’heure · 71 % réservent de nouveau')).toBeInTheDocument();
    expect(screen.getByRole('radiogroup', { name: 'Choisissez un service' })).toBeInTheDocument();
    const box = screen.getByRole('complementary', { name: 'Réserver un moment' });
    expect(within(box).getByRole('button', { name: 'Choisissez une heure' })).toBeDisabled();
    expect(screen.getByText('Customer 1 · réservation vérifiée · il y a 3 jours')).toBeInTheDocument();
    await user().click(screen.getByRole('tab', { name: /Avis/ }));
    expect(screen.getByRole('heading', { name: 'Avis · 312' })).toBeInTheDocument();
  });

  it('words relative review dates', () => {
    const now = Date.parse('2026-09-30T12:00:00Z');
    expect(ago('2026-09-29T12:00:00Z', 'en', now)).toBe('yesterday');
    expect(ago('2026-09-16T12:00:00Z', 'en', now)).toBe('2 weeks ago');
    expect(ago('2026-08-01T12:00:00Z', 'en', now)).toBe('2 months ago');
  });
});

describe('storefront visits and the provider-funded reward (S-75)', () => {
  const withReward = (reward: unknown) => (c: Call) => (c.url === '/api/v1/public/merchants/m1/reward' ? { body: reward } : api()(c));
  beforeEach(() => sessionStorage.clear());

  it('counts the visit once per tab session, with no cookie or identity in the beacon', async () => {
    const calls = mockFetch(api());
    const first = renderApp('/providers/prairie-wrench', { routes });
    await screen.findByRole('heading', { level: 1, name: 'Prairie Wrench' });
    first.unmount();
    renderApp('/providers/prairie-wrench', { routes });
    await screen.findByRole('heading', { level: 1, name: 'Prairie Wrench' });
    const beacons = calls.filter(c => c.url === '/api/v1/public/storefronts/prairie-wrench/visits');
    expect(beacons).toHaveLength(1);
    expect(beacons[0]!.method).toBe('POST');
    expect(beacons[0]!.body).toBeUndefined();
  });

  it('shows the running reward among the credentials', async () => {
    mockFetch(withReward({ multiplier: 2, label: 'brake jobs', endsOn: '2026-10-31' }));
    renderApp('/providers/prairie-wrench', { routes });
    expect(await screen.findByText('2× points on brake jobs until Oct 31')).toBeInTheDocument();
  });

  it('shows nothing when no reward runs, and words it in French', async () => {
    mockFetch(withReward(null));
    const r = renderApp('/providers/prairie-wrench', { routes });
    await screen.findByRole('heading', { level: 1, name: 'Prairie Wrench' });
    expect(screen.queryByText(/points/)).not.toBeInTheDocument();
    r.unmount();
    mockFetch(withReward({ multiplier: 3, label: null, endsOn: '2026-10-31' }));
    renderApp('/providers/prairie-wrench', { routes, locale: 'fr' });
    expect(await screen.findByText('Points ×3 jusqu’au 31 oct.')).toBeInTheDocument();
  });
});
