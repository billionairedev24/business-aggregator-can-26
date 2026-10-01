import { Suspense } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useParams, useSearch } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { DepartmentPage } from './DepartmentPage';
import { ShopSkeleton } from './parts';
import { ShopLanding } from './ShopLanding';

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
const open = (path: string, locale: 'en' | 'fr' = 'en') => {
  const calls = mockFetch(server);
  const view = renderApp(path, { locale, routes: { shop: () => <Landing />, category: () => <Department /> } });
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
    expect(within(run).getByRole('link', { name: /Glenmore Bakery.*Bakery · 5 products.*Order by 5:20 p\.m\./ })).toHaveAttribute('href', '/shop/bakery');
    expect(within(run).getByRole('link', { name: /Little Sprouts.*Tomorrow/ })).toBeInTheDocument();
    expect(within(run).getByRole('link', { name: /Empty Shelf.*Not on a run/ })).toBeInTheDocument();

    const popular = screen.getByRole('region', { name: 'Popular tonight' });
    expect(within(popular).getByRole('link', { name: /Country sourdough\s*\$7\.50\s*Glenmore Bakery · 900 g/ })).toHaveAttribute('href', '/products/P1');
    expect(within(popular).getByRole('link', { name: /Free-run eggs\s*from \$6\.50\s*Bow Valley Dairy \+ 1 more shop/ })).toBeInTheDocument();
    expect(within(popular).getByRole('link', { name: 'Search everything' })).toHaveAttribute('href', '/search?scope=shop');
  });

  it('is in French', async () => {
    open('/shop', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'La tournée groupée de ce soir part à 18 h 00' })).toBeInTheDocument();
    expect(screen.getByText('Boutique · épicerie et produits')).toBeInTheDocument();
    expect(screen.getByText('commandez avant 17 h 20 · 3 commerces · 5 voisins inscrits · Calgary')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Magasiner par rayon' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Tous les commerces de la tournée' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Country sourdough\s*7,50\s\$/ })).toBeInTheDocument();
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
    expect(screen.getByRole('link', { name: /Country sourdough/ })).toHaveAttribute('href', '/products/P1?market=Edmonton');
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
    expect(screen.getByRole('link', { name: 'Change location' })).toHaveAttribute('href', '/location');
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

  it('shows an error with Retry', async () => {
    let fail = true;
    api = () => (fail ? { status: 500, body: { detail: 'Boom' } } : { body: LANDING });
    open('/shop');
    expect(await screen.findByRole('alert')).toHaveTextContent('Boom');
    fail = false;
    await userEvent.setup({ delay: null }).click(screen.getByRole('button', { name: 'Retry' }));
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
    expect(within(shops).getByRole('link', { name: /Glenmore Bakery\s*Master.*5 products.*On tonight’s run/ })).toHaveAttribute('href', '/search?scope=shop&q=Glenmore%20Bakery');
    expect(within(shops).getByRole('link', { name: /Sidewalk Citizen\s*Trusted.*Tomorrow/ })).toBeInTheDocument();

    const products = screen.getByRole('region', { name: 'Popular in Bakery' });
    expect(within(products).getByText('Sorted by')).toBeInTheDocument();
    expect(within(products).getByRole('link', { name: /Croissant ×4\s*\$12\.00\s*Sidewalk Citizen · box/ })).toHaveAttribute('href', '/products/P3');
  });

  it('is in French', async () => {
    api = () => ({ body: { ...DEPARTMENT, name: 'Boulangerie', groupName: 'Alimentation et épicerie', siblings: [{ slug: 'bakery', name: 'Boulangerie', shops: 2 }, { slug: 'butcher', name: 'Boucherie', shops: 1 }] } });
    open('/shop/bakery', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Boulangerie' })).toBeInTheDocument();
    expect(screen.getByText('2 commerces à Calgary · 1 sur la tournée de ce soir · 46 produits')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Fil d’Ariane' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Commerces · Boulangerie' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Glenmore Bakery\s*Maître.*Sur la tournée de ce soir/ })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Populaire dans Boulangerie' })).toBeInTheDocument();
  });

  it('has an empty state for a department without shops in the market', async () => {
    api = () => ({ body: { ...DEPARTMENT, shops: [], products: [], shopCount: 0, onRunCount: 0, productCount: 0 } });
    open('/shop/bakery');
    expect(await screen.findByText('No shops in Bakery deliver to Calgary yet.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to Shop' })).toHaveAttribute('href', '/shop');
  });
});
