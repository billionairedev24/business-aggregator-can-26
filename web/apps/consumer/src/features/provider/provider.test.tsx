import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useParams } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { ago, ProviderPage } from './ProviderPage';
import type { ProviderFacts, Storefront } from './api';

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

function api(over: { page?: Partial<Storefront>; facts?: Partial<ProviderFacts> } = {}) {
  return (c: Call) => guest(c) ?? (() => {
    if (c.url === '/api/v1/storefronts/prairie-wrench') return { body: { ...page, ...over.page } };
    if (c.url.startsWith('/api/v1/public/providers/prairie-wrench/reviews')) return { body: { items: [review(4)], nextOffset: null } };
    if (c.url.startsWith('/api/v1/public/providers/prairie-wrench')) return { body: { ...facts, ...over.facts } };
    return undefined;
  })();
}

afterEach(() => vi.unstubAllGlobals());

describe('provider page (design 06 provider)', () => {
  it('renders the published page: hero, trust figures, credentials, about, reviews, FAQ and area in the page’s order', async () => {
    mockFetch(api());
    renderApp('/providers/prairie-wrench', { routes });
    expect(await screen.findByRole('heading', { level: 1, name: 'Prairie Wrench' })).toBeInTheDocument();
    expect(screen.getByText('Mobile mechanic · Calgary & Airdrie · since 2019')).toBeInTheDocument();
    expect(screen.getByText('Master tier · verified')).toBeInTheDocument();
    expect(screen.getByRole('note')).toHaveTextContent('Winter tire swaps: book before Oct 15 for $99');
    expect(screen.getByText('4.9')).toBeInTheDocument();
    expect(screen.getByText('312 verified reviews')).toBeInTheDocument();
    expect(screen.getByText('98%')).toBeInTheDocument();
    expect(screen.getByText('0.3%')).toBeInTheDocument();
    for (const tag of ['ID verified', 'AMVIC licensed', '$2M insured', 'instant book']) expect(screen.getByText(tag)).toBeInTheDocument();
    expect(screen.getByText('Mobile mechanic since 2019. Red Seal, AMVIC-licensed.')).toBeInTheDocument();
    const headings = screen.getAllByRole('heading', { level: 2 }).map(h => h.textContent);
    expect(headings.slice(0, 3)).toEqual(['Reviews · 312', 'FAQ', 'Service area']);
    expect(screen.getByText('“Great job number 1.”')).toBeInTheDocument();
    expect(screen.getByText('Customer 1 · verified booking · 3 days ago')).toBeInTheDocument();
    expect(screen.getByText(/Reply from Prairie Wrench/)).toBeInTheDocument();
    expect(screen.getByText('Comes to you in Beltline · Downtown.')).toBeInTheDocument();
    expect(screen.getByText('Do you work in parkades?')).toBeInTheDocument();
  });

  it('offers the services and the booking button in the aside', async () => {
    mockFetch(api());
    renderApp('/providers/prairie-wrench', { routes });
    const aside = await screen.findByRole('complementary', { name: 'Book Prairie Wrench' });
    expect(within(aside).getByText('Compare verified mechanics by trust, price and next available slot.')).toBeInTheDocument();
    expect(within(aside).getByText('Brake inspection')).toBeInTheDocument();
    expect(within(aside).getByText('$89')).toBeInTheDocument();
    expect(within(aside).getByText('Quote')).toBeInTheDocument();
    expect(within(aside).getByText('60 min')).toBeInTheDocument();
    expect(within(aside).getByRole('link', { name: 'Book a visit' })).toHaveAttribute('href', '/providers/prairie-wrench/book');
    expect(within(aside).getByRole('link', { name: 'Not sure? Request a quote' })).toBeInTheDocument();
    expect(within(aside).getByText('Next available: No openings in the next 2 weeks · Extra parts need your approval. Free cancellation until 12 h before.')).toBeInTheDocument();
  });

  it('leaves out sections the business turned off, and says so when it has no reviews yet', async () => {
    mockFetch(api({ page: { sections: [{ kind: 'hero', settings: {} }, { kind: 'reviews', settings: {} }, { kind: 'cta', settings: {} }], announcement: null }, facts: { reviewCount: 0, reviews: { items: [], nextOffset: null } } }));
    renderApp('/providers/prairie-wrench', { routes });
    expect(await screen.findByRole('heading', { name: 'Reviews · 0' })).toBeInTheDocument();
    expect(screen.getAllByText('New on Northline — verified').length).toBeGreaterThan(0);
    expect(screen.queryByText('Mobile mechanic since 2019. Red Seal, AMVIC-licensed.')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Service area' })).not.toBeInTheDocument();
    expect(screen.queryByText('Brake inspection')).not.toBeInTheDocument();
    expect(screen.queryByRole('note')).not.toBeInTheDocument();
  });

  it('loads more reviews on demand', async () => {
    const calls = mockFetch(api());
    renderApp('/providers/prairie-wrench', { routes });
    await user().click(await screen.findByRole('button', { name: 'Show more reviews' }));
    expect(await screen.findByText('“Great job number 4.”')).toBeInTheDocument();
    expect(calls.some(c => c.url === '/api/v1/public/providers/prairie-wrench/reviews?offset=3&limit=10')).toBe(true);
    expect(screen.queryByRole('button', { name: 'Show more reviews' })).not.toBeInTheDocument();
  });

  it('reads in French', async () => {
    mockFetch(api());
    renderApp('/providers/prairie-wrench', { routes, locale: 'fr' });
    expect(await screen.findByText('Niveau Maître · vérifié')).toBeInTheDocument();
    expect(screen.getByText('312 avis vérifiés')).toBeInTheDocument();
    expect(screen.getByText('4,9')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Avis · 312' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Réserver une visite' })).toBeInTheDocument();
    expect(screen.getByText('Customer 1 · réservation vérifiée · il y a 3 jours')).toBeInTheDocument();
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
