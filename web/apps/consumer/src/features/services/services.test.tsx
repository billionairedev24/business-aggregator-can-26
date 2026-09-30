import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useParams } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { ServicesLanding } from './ServicesLanding';
import { ServiceCategory } from './ServiceCategory';
import { applyFilters, placeOf, ProviderList } from './ProviderList';
import { nextAvailable, price } from './format';
import type { ProviderCard } from './api';

const user = () => userEvent.setup({ delay: null });
const guest = (c: Call) => (c.url === '/bff/session' ? { body: { user: null, guestId: 'g_x' } } : undefined);

const Category = () => { const { category } = useParams({ strict: false }) as { category: string }; return <ServiceCategory slug={category} />; };
const Providers = () => { const { category } = useParams({ strict: false }) as { category: string }; return <ProviderList slug={category} />; };
const routes = { services: () => <ServicesLanding />, svcCategory: Category, providers: Providers };

const landing = {
  liveCategories: 4, providers: 1, provinces: ['AB'],
  groups: [
    { id: 'service.automotive', key: 'automotive', names: { en: 'Automotive' }, note: 'AMVIC licence checked', items: [
      { slug: 'mobile-mechanic', names: { en: 'Mobile mechanic' }, kind: 'visit', providers: 1 },
      { slug: 'detailing', names: { en: 'Detailing' }, kind: 'visit', providers: 0 },
    ] },
    { id: 'service.professional', key: 'professional', names: { en: 'Professional' }, note: 'Regulated professions verified', items: [
      { slug: 'real-estate-agent', names: { en: 'Real-estate agent' }, kind: 'consult', providers: 0 },
    ] },
  ],
};
const group = landing.groups[0]!;
const mechanic = {
  id: 'service.automotive.mobile-mechanic', slug: 'mobile-mechanic', names: { en: 'Mobile mechanic' }, group, kind: 'visit', vehicle: true,
  regulatedRegistry: 'AMVIC', providers: 14, quoteable: true,
  jobs: [
    { name: 'Brake inspection', included: 'Pads, rotors, fluid — written report', pricingMode: 'fixed', priceCents: 8900, durationMin: 60 },
    { name: 'Alternator / starter', included: 'Quoted after diagnosis', pricingMode: 'quote', priceCents: null, durationMin: 60 },
  ],
};
const plumber = { ...mechanic, id: 'service.home-trades.plumber', slug: 'plumber', names: { en: 'Plumber' }, vehicle: false, regulatedRegistry: 'Safety Codes', providers: 1, jobs: [] };
const realtor = { ...mechanic, id: 'service.professional.real-estate-agent', slug: 'real-estate-agent', names: { en: 'Real-estate agent' }, kind: 'consult', vehicle: false, regulatedRegistry: 'RECA', quoteable: false, jobs: [] };

const soon = new Date(Date.now() + 2 * 3_600_000).toISOString();
const card = (over: Partial<ProviderCard>): ProviderCard => ({
  merchantId: 'm1', slug: 'prairie-wrench', name: 'Prairie Wrench', tier: 'master', brandColor: '#2f5d3a',
  blurb: 'Red Seal · AMVIC · parts at cost, receipts on every job.', rating: 4.9, reviewCount: 312, onTimePct: 98, disputePct: 0.3,
  rebookPct: 71, fromCents: 7900, pricingMode: 'fixed', instantBook: true, nextAvailable: soon, zones: ['Beltline'], ...over,
});
const list = { categorySlug: 'mobile-mechanic', kind: 'visit', area: 'Beltline', city: 'Calgary', items: [
  card({}),
  card({ merchantId: 'm2', slug: 'bow-river', name: 'Bow River Mechanics', tier: 'trusted', rating: 4.7, reviewCount: 88, onTimePct: 93, fromCents: 6900, nextAvailable: null }),
] };

function api(over: Record<string, unknown> = {}) {
  return (c: Call) => guest(c) ?? (() => {
    const path = c.url.split('?')[0]!;
    if (path in over) return { body: over[path] };
    if (path === '/api/v1/public/services') return { body: landing };
    if (path === '/api/v1/public/services/mobile-mechanic') return { body: mechanic };
    if (path === '/api/v1/public/services/plumber') return { body: plumber };
    if (path === '/api/v1/public/services/real-estate-agent') return { body: realtor };
    if (path === '/api/v1/public/services/mobile-mechanic/providers') return { body: list };
    return undefined;
  })();
}

function geo(lat: number, lng: number) {
  return { getCurrentPosition: (ok: PositionCallback) => ok({ coords: { latitude: lat, longitude: lng, accuracy: 20 } } as GeolocationPosition) } as unknown as Geolocation;
}
const denied = { getCurrentPosition: (_: PositionCallback, fail?: PositionErrorCallback | null) => fail?.({ code: 1 } as GeolocationPositionError) } as unknown as Geolocation;

beforeEach(() => { localStorage.clear(); sessionStorage.clear(); });
afterEach(() => { vi.unstubAllGlobals(); });

describe('services landing (design 06 services)', () => {
  it('lists every group with its line and categories, each opening its page', async () => {
    const calls = mockFetch(api());
    renderApp('/services', { routes });
    expect(await screen.findByRole('heading', { level: 1, name: 'Services' })).toBeInTheDocument();
    expect(screen.getByText('4 categories live in Alberta · every provider verified, every job paid into escrow')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Automotive' })).toBeInTheDocument();
    expect(screen.getByText('Mobile visits · fixed prices · licence checked')).toBeInTheDocument();
    expect(screen.getByText('Free consultation · licensed professionals · written agreement')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Mobile mechanic' })).toHaveAttribute('href', '/services/mobile-mechanic');
    expect(calls.some(c => c.url === '/api/v1/public/services?lang=en')).toBe(true);
  });

  it('reads in French, category names included', async () => {
    const calls = mockFetch(api());
    renderApp('/services', { routes, locale: 'fr' });
    expect(await screen.findByText('4 catégories actives en Alberta · chaque prestataire vérifié, chaque travail payé en fiducie')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Automobile' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Mécanicien mobile' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Courtier immobilier' })).toBeInTheDocument();
    expect(calls.some(c => c.url === '/api/v1/public/services?lang=fr')).toBe(true);
  });

  it('shows the empty state when there are no categories', async () => {
    mockFetch(api({ '/api/v1/public/services': { liveCategories: 0, providers: 0, groups: [] } }));
    renderApp('/services', { routes });
    expect(await screen.findByText('No service categories yet.')).toBeInTheDocument();
  });
});

describe('service category (design 06 svcCategory)', () => {
  it('shows the booking type, licence, typical prices, how booking works and the way in', async () => {
    mockFetch(api());
    renderApp('/services/mobile-mechanic', { routes });
    expect(await screen.findByRole('heading', { level: 1, name: 'Mobile mechanic' })).toBeInTheDocument();
    expect(screen.getByText('On-site visit · instant book')).toBeInTheDocument();
    expect(screen.getByText('At your driveway or parkade')).toBeInTheDocument();
    expect(screen.getByText('14 verified providers')).toBeInTheDocument();
    expect(screen.getByText('Licence checked · AMVIC')).toBeInTheDocument();
    expect(screen.getByText(/^Licensed technicians who bring the shop to you\./)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Common jobs & typical prices' })).toBeInTheDocument();
    const brake = screen.getByRole('link', { name: /Brake inspection/ });
    expect(brake).toHaveTextContent('$89');
    expect(brake).toHaveAttribute('href', '/services/mobile-mechanic/providers');
    expect(screen.getByRole('link', { name: /Alternator \/ starter/ })).toHaveTextContent('Quote');
    expect(screen.getByRole('heading', { name: 'How booking works for mobile mechanic' })).toBeInTheDocument();
    expect(screen.getByText('Describe & vehicle')).toBeInTheDocument();
    expect(screen.getByText('Held now, released on your sign-off')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'See 14 mechanics' })).toHaveAttribute('href', '/services/mobile-mechanic/providers');
    expect(screen.getByRole('link', { name: 'Describe the job, get 3 quotes' })).toHaveAttribute('href', '/services/mobile-mechanic/quote');
    expect(screen.getByText('Extra parts need your approval. Free cancellation until 12 h before.')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).toHaveTextContent('Home › Services › Mobile mechanic');
  });

  it('uses the generic wording outside the design’s example categories, and no quotes for consultations', async () => {
    mockFetch(api());
    const { router } = renderApp('/services/plumber', { routes });
    expect(await screen.findByText(/^Licensed, insured pros who come to you\./)).toBeInTheDocument();
    expect(screen.getByText('At your home or business')).toBeInTheDocument();
    expect(screen.getByText('No prices listed yet — describe the job and providers quote a fixed price.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'See 1 providers' })).toBeInTheDocument();
    await router.navigate({ to: '/services/$category', params: { category: 'real-estate-agent' } });
    expect(await screen.findByText('Free consultation · licensed')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Describe the job, get 3 quotes' })).not.toBeInTheDocument();
    expect(screen.getByText(/^Licensed realtors, verified against RECA\./)).toBeInTheDocument();
  });

  it('reads in French', async () => {
    mockFetch(api());
    renderApp('/services/mobile-mechanic', { routes, locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Mécanicien mobile' })).toBeInTheDocument();
    expect(screen.getByText('Travaux courants et prix typiques')).toBeInTheDocument();
    expect(screen.getByText('Permis vérifié · AMVIC')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Voir 14 mécaniciens' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Décrivez le travail, obtenez 3 devis' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Brake inspection/ })).toHaveTextContent('89 $');
  });
});

describe('provider list (design 06 providers)', () => {
  it('asks for the providers covering the device’s location and lists them most trusted first', async () => {
    const calls = mockFetch(api());
    renderApp('/services/mobile-mechanic/providers', { routes, geolocation: geo(51.0385, -114.072) });
    expect(await screen.findByRole('heading', { level: 1, name: 'Mobile mechanics · 2 come to Beltline' })).toBeInTheDocument();
    expect(screen.getByText('Sorted by trust · tier, on-time rate, dispute rate and re-book rate')).toBeInTheDocument();
    const rows = screen.getAllByRole('link', { name: /Prairie Wrench|Bow River/ });
    expect(rows[0]).toHaveAttribute('href', '/providers/prairie-wrench');
    expect(within(rows[0]!).getByText('Master')).toBeInTheDocument();
    expect(within(rows[0]!).getByText('★ 4.9 (312) · 98% on time')).toBeInTheDocument();
    expect(within(rows[0]!).getByText('from $79')).toBeInTheDocument();
    expect(within(rows[0]!).getByText(/^(Today|Tomorrow) /)).toBeInTheDocument();
    expect(within(rows[1]!).getByText('No openings in the next 2 weeks')).toBeInTheDocument();
    const url = calls.find(c => c.url.startsWith('/api/v1/public/services/mobile-mechanic/providers'))!.url;
    expect(url).toContain('lat=51.03850');
    expect(url).toContain('lng=-114.07200');
  });

  it('filters by tier, instant book, today and price; clearing brings everyone back', async () => {
    mockFetch(api());
    renderApp('/services/mobile-mechanic/providers', { routes, geolocation: geo(51.0385, -114.072) });
    await screen.findByText('Bow River Mechanics');
    await user().click(screen.getByRole('checkbox', { name: 'Master tier' }));
    expect(screen.queryByText('Bow River Mechanics')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Mobile mechanics · 1 come to Beltline');
    await user().click(screen.getByRole('checkbox', { name: 'Under $80' }));
    expect(screen.getByText('Prairie Wrench')).toBeInTheDocument();
    expect(applyFilters(list.items as ProviderCard[], new Set(['under80']))).toHaveLength(2);
    expect(applyFilters([card({ fromCents: 8900 })], new Set(['under80']))).toHaveLength(0);
    expect(applyFilters([card({ instantBook: false })], new Set(['instant']))).toHaveLength(0);
    expect(applyFilters([card({ nextAvailable: null })], new Set(['today']))).toHaveLength(0);
  });

  it('with only the Calgary fallback, asks by city and says so', async () => {
    const calls = mockFetch(api({ '/api/v1/public/services/mobile-mechanic/providers': { ...list, area: null } }));
    renderApp('/services/mobile-mechanic/providers', { routes, geolocation: denied });
    expect(await screen.findByRole('heading', { level: 1, name: 'Mobile mechanics · 2 come to Calgary' })).toBeInTheDocument();
    const url = calls.find(c => c.url.startsWith('/api/v1/public/services/mobile-mechanic/providers'))!.url;
    expect(url).toBe('/api/v1/public/services/mobile-mechanic/providers?lang=en&city=Calgary');
    expect(placeOf({ status: 'fallback', city: 'Calgary', lat: 51, lng: -114, source: 'default' })).toEqual({ city: 'Calgary' });
  });

  it('shows the empty and error states', async () => {
    let fail = true;
    mockFetch(c => (c.url.startsWith('/api/v1/public/services/mobile-mechanic/providers')
      ? (fail ? { status: 500, body: { detail: 'boom' } } : { body: { ...list, items: [] } })
      : api()(c)));
    renderApp('/services/mobile-mechanic/providers', { routes, geolocation: geo(51.0385, -114.072) });
    expect(await screen.findByText("We couldn't load this list.")).toBeInTheDocument();
    fail = false;
    await user().click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('No verified providers cover Beltline for this service yet.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'See all services' })).toHaveAttribute('href', '/services');
  });

  it('reads in French', async () => {
    mockFetch(api());
    renderApp('/services/mobile-mechanic/providers', { routes, locale: 'fr', geolocation: geo(51.0385, -114.072) });
    expect(await screen.findByRole('heading', { level: 1, name: 'Mécaniciens mobiles · 2 se déplacent à Beltline' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Niveau Maître' })).toBeInTheDocument();
    expect(screen.getByText('★ 4,9 (312) · 98 % à l’heure')).toBeInTheDocument();
    expect(screen.getByText('à partir de 79 $')).toBeInTheDocument();
  });
});

describe('formatting', () => {
  const t = ((k: string, v?: Record<string, unknown>) => ({ priceQuote: 'Quote', priceFree: 'Free', perHour: `${v?.price}/h`, priceFrom: `from ${v?.price}`, availToday: `Today ${v?.time}`, availTomorrow: `Tomorrow ${v?.time}`, availDay: `${v?.day} ${v?.time}`, noOpenings: 'none' } as Record<string, string>)[k] ?? k) as never;
  it('prices as the design writes them', () => {
    expect(price(t, 'en', 'fixed', 8900)).toBe('$89');
    expect(price(t, 'en', 'hourly', 4500, true)).toBe('from $45/h');
    expect(price(t, 'en', 'fixed', 6450)).toBe('$64.50');
    expect(price(t, 'en', 'quote', null)).toBe('Quote');
  });
  it('says today / tomorrow / weekday in Calgary time', () => {
    const now = new Date('2026-09-30T18:00:00Z'); // noon in Calgary
    expect(nextAvailable(t, 'en', '2026-09-30T21:00:00Z', now)).toBe('Today 3 pm');
    expect(nextAvailable(t, 'en', '2026-10-01T15:30:00Z', now)).toBe('Tomorrow 9:30 am');
    expect(nextAvailable(t, 'en', '2026-10-02T15:00:00Z', now)).toBe('Fri 9 am');
    expect(nextAvailable(t, 'fr', '2026-10-02T21:00:00Z', now)).toBe('ven. 15 h');
  });
});
