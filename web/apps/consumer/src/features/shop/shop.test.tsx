import { Suspense } from 'react';
import { useQueryClient, useSuspenseQuery } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useParams, useSearch } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { DepartmentPage } from './DepartmentPage';
import { ShopSkeleton } from './parts';
import { ShopLanding } from './ShopLanding';
import { StorePage } from './StorePage';
import { loadStore } from './store';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

// Tonight's run: 6–9 pm in Edmonton (MDT = UTC−6), order by 5:20 pm.
const RUN = { windowId: 'W1', label: 'R-611', day: 'today', startsAt: '2026-10-01T00:00:00Z', endsAt: '2026-10-01T03:00:00Z', orderBy: '2026-09-30T23:20:00Z', feeCents: 299, households: 5 };
const TOMORROW = { ...RUN, windowId: 'W2', day: 'tomorrow', startsAt: '2026-10-01T14:00:00Z', endsAt: '2026-10-01T17:00:00Z', orderBy: '2026-10-01T13:05:00Z', households: 0 };
const product = (id: string, name: string, priceCents: number, extra = {}) => ({ productId: id, offerId: `o-${id}`, name, merchantId: 'M1', shopName: 'Glenmore Bakery', unit: '900 g', priceCents, imageUrl: null, sellers: 1, run: RUN, ...extra });
const LANDING = {
  market: 'Calgary', served: true, run: RUN, shopCount: 3,
  departments: [{ slug: 'bakery', name: 'Bakery', shops: 2 }, { slug: 'butcher', name: 'Butcher', shops: 1 }],
  shops: [
    { merchantId: 'M1', name: 'Glenmore Bakery', tier: 'master', departmentSlug: 'bakery', departmentName: 'Bakery', products: 5, run: RUN },
    { merchantId: 'M2', name: 'Little Sprouts', tier: 'trusted', departmentSlug: 'kids', departmentName: 'Kids', products: 1, run: TOMORROW },
    { merchantId: 'M3', name: 'Empty Shelf', tier: 'registered', departmentSlug: 'bakery', departmentName: 'Bakery', products: 1, run: null },
  ],
  popular: [product('P1', 'Country sourdough', 750), product('P2', 'Free-run eggs', 650, { sellers: 2, shopName: 'Bow Valley Dairy', unit: 'dozen' })],
};
const DEPARTMENT = {
  slug: 'bakery', name: 'Bakery', groupName: 'Food & grocery', market: 'Calgary', served: true, run: RUN,
  siblings: [{ slug: 'bakery', name: 'Bakery', shops: 2 }, { slug: 'butcher', name: 'Butcher', shops: 1 }],
  shopCount: 2, onRunCount: 1, productCount: 46,
  shops: [
    { merchantId: 'M1', name: 'Glenmore Bakery', tier: 'master', departmentSlug: 'bakery', departmentName: 'Bakery', products: 5, run: RUN },
    { merchantId: 'M4', name: 'Sidewalk Citizen', tier: 'trusted', departmentSlug: 'bakery', departmentName: 'Bakery', products: 2, run: TOMORROW },
  ],
  products: [product('P1', 'Country sourdough', 750), product('P3', 'Croissant ×4', 1200, { shopName: 'Sidewalk Citizen', unit: 'box' })],
};

type Reply = { status?: number; body?: unknown } | undefined;
let api: (call: Call) => Reply;
const server = (call: Call): Reply => (call.url === '/bff/session' ? { body: { user: null, guestId: 'g_x' } } : api(call));

function Landing() {
  const { market } = useSearch({ strict: false }) as { market?: string };
  return <Suspense fallback={<ShopSkeleton />}><ShopLanding market={market} /></Suspense>;
}
function Department() {
  const { department } = useParams({ strict: false }) as { department: string };
  const { market } = useSearch({ strict: false }) as { market?: string };
  return <Suspense fallback={<ShopSkeleton />}><DepartmentPage slug={department} market={market} /></Suspense>;
}
/** The store route's loader (features/shop/store.ts) and its page. */
function StoreData() {
  const { merchantId } = useParams({ strict: false }) as { merchantId: string };
  const search = useSearch({ strict: false }) as { market?: string; dept?: string };
  const qc = useQueryClient();
  const { data } = useSuspenseQuery({ queryKey: ['test-store', merchantId], queryFn: () => loadStore(qc, merchantId, search, 'en') });
  return data ? <StorePage data={data} market={search.market} /> : <h1>Not found</h1>;
}
const Store = () => <Suspense fallback={<ShopSkeleton />}><StoreData /></Suspense>;
const open = (path: string, locale: 'en' | 'fr' = 'en') => {
  const calls = mockFetch(server);
  const view = renderApp(path, { locale, routes: { shop: () => <Landing />, category: () => <Department />, store: () => <Store /> } });
  return { calls, ...view };
};
const url = (c: Call) => new URL(c.url, 'http://x');

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  api = c => {
    const u = url(c);
    if (u.pathname === '/api/v1/public/shop') return { body: { ...LANDING, market: u.searchParams.get('market') ?? 'Calgary' } };
    if (u.pathname === '/api/v1/public/shop/departments/bakery') return { body: DEPARTMENT };
    return undefined;
  };
});
afterEach(() => { vi.unstubAllGlobals(); });

describe('Shop landing (design 06 shop)', () => {
  it('shows the next run, departments, the shops on it and popular products', async () => {
    const { calls } = open('/shop');
    expect(await screen.findByRole('heading', { level: 1, name: 'Tonight’s pooled run leaves 6:00 p.m.' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('Shop · groceries & goods')).toBeInTheDocument();
    expect(screen.getByText('order by 5:20 p.m. · 3 shops · 5 neighbours in · Calgary')).toBeInTheDocument();
    expect(screen.getByText('Pooled run · Calgary')).toBeInTheDocument();
    // no market chosen: the page asks without one and the api renders its fallback market (region configuration)
    expect(calls.some(c => url(c).pathname === '/api/v1/public/shop' && url(c).searchParams.get('market') === null && url(c).searchParams.get('lang') === 'en')).toBe(true);

    const depts = screen.getByRole('region', { name: 'Shop by department' });
    expect(within(depts).getByRole('link', { name: /Bakery\s*2 shops/ })).toHaveAttribute('href', '/shop/bakery');
    expect(within(depts).getByRole('link', { name: /Butcher\s*1 shop$/ })).toBeInTheDocument();

    const run = screen.getByRole('region', { name: 'All shops on tonight’s run' });
    expect(within(run).getByText('One courier, one fee — every shop below packs for the same run.')).toBeInTheDocument();
    expect(within(run).getByRole('link', { name: /Glenmore Bakery.*Bakery · 5 products.*Order by 5:20 p\.m\./ })).toHaveAttribute('href', '/shop/stores/M1?dept=bakery');
    expect(within(run).getByRole('link', { name: /Little Sprouts.*Tomorrow/ })).toBeInTheDocument();
    expect(within(run).getByRole('link', { name: /Empty Shelf.*Not on a run/ })).toBeInTheDocument();

    const popular = screen.getByRole('region', { name: 'Popular tonight' });
    // E-15: the results' card — the price with its unit price, the shop and size, Add to cart (one seller)
    const sourdough = within(popular).getByRole('article', { name: 'Country sourdough' });
    expect(within(sourdough).getByRole('link', { name: 'Country sourdough' })).toHaveAttribute('href', '/products/P1');
    expect(sourdough).toHaveTextContent(/\$7\.50\$0\.83\/100 g/);
    expect(sourdough).toHaveTextContent('Glenmore Bakery · 900 g');
    expect(within(sourdough).getByRole('button', { name: 'Add to cart: Country sourdough' })).toBeInTheDocument();
    const eggs = within(popular).getByRole('article', { name: 'Free-run eggs' });
    expect(eggs).toHaveTextContent('from $6.50');
    expect(eggs).toHaveTextContent('Bow Valley Dairy + 1 more shop');
    expect(within(eggs).queryByRole('button', { name: /Add to cart/ })).toBeNull(); // several shops: the product page picks
    expect(within(popular).getByRole('link', { name: 'Search everything' })).toHaveAttribute('href', '/search?scope=shop');
  });

  it('marks an age-restricted product on its card before checkout (demo rehearsal), en and fr', async () => {
    const lager = product('P3', 'Craft lager 6-pack', 1599, { shopName: 'Bow Valley Market', unit: '6 × 355 ml', age: { ageClass: 'alcohol', minimumAge: 18 } });
    api = c => (url(c).pathname === '/api/v1/public/shop' ? { body: { ...LANDING, popular: [...LANDING.popular, lager] } } : undefined);
    const first = open('/shop');
    const popular = await screen.findByRole('region', { name: 'Popular tonight' });
    expect(within(popular).getByRole('article', { name: 'Craft lager 6-pack' })).toHaveTextContent(/Bow Valley Market · 6 × 355 ml\s*18\+ · photo ID checked/);
    expect(within(popular).getByRole('article', { name: 'Country sourdough' })).not.toHaveTextContent('photo ID');
    first.unmount();
    open('/shop', 'fr');
    expect(await screen.findByRole('article', { name: 'Craft lager 6-pack' })).toHaveTextContent(/18 ans et plus · pièce d’identité vérifiée/);
  });

  it('is in French', async () => {
    open('/shop', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'La tournée groupée de ce soir part à 18 h 00' })).toBeInTheDocument();
    expect(screen.getByText('Boutique · épicerie et produits')).toBeInTheDocument();
    expect(screen.getByText('commandez avant 17 h 20 · 3 commerces · 5 voisins inscrits · Calgary')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Magasiner par rayon' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Tous les commerces de la tournée' })).toBeInTheDocument();
    expect(screen.getByRole('article', { name: 'Country sourdough' })).toHaveTextContent(/7,50\s\$/);
    expect(screen.getByRole('link', { name: /Glenmore Bakery.*Commandez avant 17 h 20/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Little Sprouts.*Demain/ })).toBeInTheDocument();
  });

  it('follows the visitor to their market and keeps it on links', async () => {
    localStorage.setItem('nl.location', JSON.stringify({ label: 'Whyte Ave, Edmonton', city: 'Edmonton' }));
    const { calls, router } = open('/shop');
    expect(await screen.findByText(/neighbours in · Whyte Ave, Edmonton$/)).toBeInTheDocument();
    expect(calls.some(c => url(c).searchParams.get('market') === 'Edmonton')).toBe(true);
    expect(router.state.location.search).toEqual({ market: 'Edmonton' });
    expect(screen.getByRole('link', { name: /Bakery\s*2 shops/ })).toHaveAttribute('href', '/shop/bakery?market=Edmonton');
    expect(screen.getByRole('link', { name: 'Country sourdough' })).toHaveAttribute('href', '/products/P1?market=Edmonton');
  });

  it('keeps a market given in the link', async () => {
    localStorage.setItem('nl.location', JSON.stringify({ label: 'Beltline, Calgary', city: 'Calgary' }));
    const { calls } = open('/shop?market=Airdrie');
    expect(await screen.findByText(/neighbours in · Airdrie$/)).toBeInTheDocument();
    await waitFor(() => expect(calls.filter(c => url(c).pathname === '/api/v1/public/shop').every(c => url(c).searchParams.get('market') === 'Airdrie')).toBe(true));
  });

  it('says so where Northline Shop doesn’t deliver', async () => {
    api = () => ({ body: { market: 'Red Deer', served: false, run: null, shopCount: 0, departments: [], shops: [], popular: [] } });
    open('/shop?market=Red%20Deer');
    expect(await screen.findByText('Northline Shop doesn’t deliver to Red Deer yet.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Change location' })).toBeInTheDocument(); // the location dialog, in place
    expect(screen.getByRole('heading', { level: 1, name: 'Shop local, delivered together' })).toBeInTheDocument();
  });

  it('has an empty state when no shop delivers yet', async () => {
    api = () => ({ body: { ...LANDING, shopCount: 0, departments: [], shops: [], popular: [] } });
    open('/shop');
    expect(await screen.findByText('No shops deliver to Calgary yet.')).toBeInTheDocument();
  });

  it('shows a skeleton while loading', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})));
    renderApp('/shop', { routes: { shop: () => <Landing /> } });
    expect(await screen.findByText('Loading the Shop…')).toBeInTheDocument();
  });

  it('the api failing: "We can\u2019t reach the shop right now" (never its raw message), then Try again', async () => {
    let fail = true;
    api = () => (fail ? { status: 500, body: { detail: 'Boom' } } : { body: LANDING });
    open('/shop');
    expect(await screen.findByRole('heading', { level: 1, name: 'We can’t reach the shop right now.' })).toBeInTheDocument();
    expect(screen.queryByText('Boom')).toBeNull();
    fail = false;
    await userEvent.setup({ delay: null }).click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Tonight’s pooled run leaves 6:00 p.m.' })).toBeInTheDocument();
  });
});

describe('Department page (design 06 category)', () => {
  it('shows the breadcrumb, counts, sibling departments, shops and popular products', async () => {
    open('/shop/bakery');
    expect(await screen.findByRole('heading', { level: 1, name: 'Bakery' })).toBeInTheDocument();
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(crumbs).getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/');
    expect(within(crumbs).getByRole('link', { name: 'Shop' })).toHaveAttribute('href', '/shop');
    expect(screen.getByText('2 shops in Calgary · 1 on tonight’s run · 46 products')).toBeInTheDocument();

    const chips = screen.getByRole('navigation', { name: 'Departments in Food & grocery' });
    expect(within(chips).getByRole('link', { name: 'Bakery' })).toHaveAttribute('aria-current', 'page');
    expect(within(chips).getByRole('link', { name: 'Butcher' })).toHaveAttribute('href', '/shop/butcher');

    const shops = screen.getByRole('region', { name: 'Shops · Bakery' });
    expect(within(shops).getByRole('link', { name: /Glenmore Bakery.*5 products.*Master.*On tonight’s run/ })).toHaveAttribute('href', '/shop/stores/M1?dept=bakery');
    expect(within(shops).getByRole('link', { name: /Sidewalk Citizen.*Trusted.*Tomorrow/ })).toBeInTheDocument();

    const products = screen.getByRole('region', { name: 'Popular in Bakery' });
    expect(within(products).getByText('Sorted by')).toBeInTheDocument();
    const croissant = within(products).getByRole('article', { name: 'Croissant ×4' });
    expect(within(croissant).getByRole('link', { name: 'Croissant ×4' })).toHaveAttribute('href', '/products/P3');
    expect(croissant).toHaveTextContent(/\$12\.00\$3\.00 each/);
    expect(croissant).toHaveTextContent('Sidewalk Citizen · box');
  });

  it('is in French', async () => {
    api = () => ({ body: { ...DEPARTMENT, name: 'Boulangerie', groupName: 'Alimentation et épicerie', siblings: [{ slug: 'bakery', name: 'Boulangerie', shops: 2 }, { slug: 'butcher', name: 'Boucherie', shops: 1 }] } });
    open('/shop/bakery', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Boulangerie' })).toBeInTheDocument();
    expect(screen.getByText('2 commerces à Calgary · 1 sur la tournée de ce soir · 46 produits')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Fil d’Ariane' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Commerces · Boulangerie' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Glenmore Bakery.*Maître.*Sur la tournée de ce soir/ })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Populaire dans Boulangerie' })).toBeInTheDocument();
  });

  it('has an empty state for a department without shops in the market', async () => {
    api = () => ({ body: { ...DEPARTMENT, shops: [], products: [], shopCount: 0, onRunCount: 0, productCount: 0 } });
    open('/shop/bakery');
    expect(await screen.findByText('No shops in Bakery deliver to Calgary yet.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to Shop' })).toHaveAttribute('href', '/shop');
  });
});

describe('a shop’s page (E-15 S-172)', () => {
  const hit = (id: string, name: string, merchant: { id: string; name: string }) => ({
    id, kind: 'product', name, description: null, merchant: { ...merchant, type: 'seller', slug: null, tier: 'master' }, category: { id: 'shop.bakery', name: 'Bakery' },
    priceCents: 925, pricingMode: 'fixed', rating: 4.7, reviewCount: 12, trustTier: 'master', distanceKm: null, instantBook: false, fulfilment: ['pooled'],
    openNow: false, soldOut: false, onTonightsRun: true, prepMinutes: null, dietary: [], imageKey: null,
  });

  it('shows the shop’s header and its goods from the search, kept to this merchant', async () => {
    const base = api;
    api = c => (url(c).pathname === '/api/v1/search'
      ? { body: { items: [hit('O9', 'Seeded rye, 750 g', { id: 'M1', name: 'Glenmore Bakery' }), hit('O8', 'Glenmore-style bagels', { id: 'M9', name: 'Other Bakery' })], total: 2, facets: null, next: null } }
      : base(c));
    const { calls } = open('/shop/stores/M1?dept=bakery');
    expect(await screen.findByRole('heading', { level: 1, name: 'Glenmore Bakery' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body);
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(crumbs).getByRole('link', { name: 'Bakery' })).toHaveAttribute('href', '/shop/bakery');
    expect(screen.getByText('Bakery · Calgary')).toBeInTheDocument();
    expect(screen.getByText('Master')).toBeInTheDocument();
    expect(screen.getByText('On tonight’s run · order by 5:20 p.m.')).toBeInTheDocument();
    const goods = screen.getByRole('region', { name: 'Products' });
    expect(within(goods).getByRole('link', { name: 'Seeded rye, 750 g' })).toBeInTheDocument();
    expect(within(goods).queryByText('Glenmore-style bagels')).not.toBeInTheDocument();
    const search = calls.find(c => url(c).pathname === '/api/v1/search')!;
    expect(url(search).searchParams.get('q')).toBe('Glenmore Bakery');
    expect(url(search).searchParams.get('kind')).toBe('product');
  });

  it('falls back to the products the Shop pages list for it, and is a 404 when no page lists the shop', async () => {
    open('/shop/stores/M1?dept=bakery');
    const goods = await screen.findByRole('region', { name: 'Products' });
    expect(within(goods).getByRole('link', { name: 'Country sourdough' })).toBeInTheDocument();
    open('/shop/stores/M77');
    expect(await screen.findByRole('heading', { name: 'Not found' })).toBeInTheDocument();
  });

  it('is in French', async () => {
    open('/shop/stores/M1?dept=bakery', 'fr');
    expect(await screen.findByRole('link', { name: 'Chercher dans cette boutique' })).toBeInTheDocument();
    expect(screen.getByText(/Sur la tournée de ce soir · commandez avant 17 h 20/)).toBeInTheDocument();
  });
});
