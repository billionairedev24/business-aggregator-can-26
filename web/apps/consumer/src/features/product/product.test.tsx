import { Suspense } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useParams, useSearch } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { ProductDetail, ProductSkeleton } from './ProductDetail';

// Tonight 6–9 pm (Edmonton), shops pack by 5:45, customers order by 5:20; then tomorrow 8–11 am.
const TONIGHT = { windowId: 'W1', label: 'R-611', day: 'today', startsAt: '2026-10-01T00:00:00Z', endsAt: '2026-10-01T03:00:00Z', orderBy: '2026-09-30T23:20:00Z', packBy: '2026-09-30T23:45:00Z', feeCents: 299, households: 5 };
const MORNING = { ...TONIGHT, windowId: 'W2', day: 'tomorrow', startsAt: '2026-10-01T14:00:00Z', endsAt: '2026-10-01T17:00:00Z', orderBy: '2026-10-01T13:05:00Z', packBy: '2026-10-01T13:30:00Z', feeCents: 199 };
const GLENMORE = {
  offerId: 'O1', merchantId: 'M1', shopName: 'Glenmore Bakery', tier: 'master', rating: 4.8, ratingCount: 211, priceCents: 750,
  compareAtCents: null, condition: 'new', stock: 14, lowStock: false, returnsPolicy: 'final_sale', variantTheme: 'size',
  variants: [{ variantId: 'V1', value: 'Whole', priceCents: 750, stock: 10 }, { variantId: 'V2', value: 'Sliced', priceCents: 750, stock: 4 }, { variantId: 'V3', value: 'Half', priceCents: 400, stock: 0 }],
  runs: [TONIGHT, MORNING], images: [], more: [{ productId: 'P2', name: 'Rye', priceCents: 800 }, { productId: 'P3', name: 'Baguette', priceCents: 450 }],
};
const SIDEWALK = { ...GLENMORE, offerId: 'O2', merchantId: 'M2', shopName: 'Sidewalk Citizen', tier: 'trusted', rating: 0, ratingCount: 0, priceCents: 700, stock: 2, lowStock: true, variants: [], runs: [MORNING], more: [] };
const PRODUCT = {
  productId: 'P1', name: 'Country sourdough', brand: null, description: 'Naturally leavened, 36-hour ferment, Alberta hard red wheat. Sliced on request.',
  bullets: [], unit: '900 g loaf', departmentSlug: 'bakery', departmentName: 'Bakery', market: 'Calgary', served: true,
  offers: [GLENMORE, SIDEWALK], direct: { etaMinutes: 45, feeCents: 999 },
};

type Reply = { status?: number; body?: unknown } | undefined;
let api: (call: Call) => Reply;
const server = (call: Call): Reply => (call.url === '/bff/session' ? { body: { user: null, guestId: 'g_x' } } : api(call));

function Page() {
  const { productId } = useParams({ strict: false }) as { productId: string };
  const { market, offer } = useSearch({ strict: false }) as { market?: string; offer?: string };
  return <Suspense fallback={<ProductSkeleton />}><ProductDetail productId={productId} market={market} offerId={offer} /></Suspense>;
}
const open = (path: string, locale: 'en' | 'fr' = 'en') => {
  const calls = mockFetch(server);
  const view = renderApp(path, { locale, routes: { product: () => <Page />, cart: () => <h1>Cart page</h1> } });
  return { calls, ...view };
};

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  api = c => {
    if (c.url.startsWith('/api/v1/public/shop/products/P1')) return { body: PRODUCT };
    if (c.url === '/api/v1/cart/items' && c.method === 'POST') return { status: 201, body: { itemCount: 3 } };
    return undefined;
  };
});
afterEach(() => { vi.unstubAllGlobals(); });

describe('Product detail (design 06 product)', () => {
  it('shows the product, the shop, options, price and the delivery cut-off', async () => {
    open('/products/P1');
    expect(await screen.findByRole('heading', { level: 1, name: 'Country sourdough' })).toBeInTheDocument();
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(crumbs).getByRole('link', { name: 'Shop' })).toHaveAttribute('href', '/shop');
    expect(within(crumbs).getByRole('link', { name: 'Bakery' })).toHaveAttribute('href', '/shop/bakery');
    expect(screen.getByText(/Master tier · ★ 4\.8 \(211 verified\)/)).toBeInTheDocument();
    expect(screen.getByText('On tonight’s run')).toBeInTheDocument();
    expect(screen.getByText('$7.50')).toBeInTheDocument();
    expect(screen.getByText('900 g loaf · GST included at checkout')).toBeInTheDocument();
    expect(screen.getByText(/36-hour ferment/)).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Options' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Whole' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Half' })).toBeDisabled();
    expect(screen.getByText(/Order by 5:20 p\.m\. for tonight 6–9 p\.m\. pooled \(\$2\.99\), tomorrow 8–11 a\.m\., or direct courier in 45 min\. Glenmore Bakery packs at 5:45 p\.m\.; the shop is paid only after you confirm delivery\./)).toBeInTheDocument();
    const more = screen.getByRole('region', { name: 'Also from Glenmore Bakery' });
    expect(within(more).getByRole('link', { name: 'Rye · $8.00' })).toHaveAttribute('href', '/products/P2');
  });

  it('adds the chosen option and quantity to the cart', async () => {
    const { calls } = open('/products/P1');
    const user = userEvent.setup({ delay: null });
    await user.click(await screen.findByRole('button', { name: 'Sliced' }));
    await user.click(screen.getByRole('button', { name: 'One more' }));
    await user.click(screen.getByRole('button', { name: 'Add 2 to cart · $15.00' }));
    expect(await screen.findByRole('heading', { name: 'Cart page' })).toBeInTheDocument();
    expect(calls.find(c => c.url === '/api/v1/cart/items')?.body).toEqual({ offerId: 'O1', variantId: 'V2', qty: 2 });
  });

  it("shows the chosen option's own photos (S-65), else the offer's", async () => {
    const withPhotos = { ...GLENMORE, images: ['/img/loaf.jpg'], variants: [GLENMORE.variants[0], { ...GLENMORE.variants[1], images: ['/img/sliced.jpg', '/img/sliced-2.jpg'] }] };
    api = c => (c.url.startsWith('/api/v1/public/shop/products/P1') ? { body: { ...PRODUCT, offers: [withPhotos] } } : undefined);
    open('/products/P1');
    const gallery = await screen.findByRole('group', { name: 'Photos' });
    expect(within(gallery).getByRole('img', { name: 'Country sourdough' })).toHaveAttribute('src', '/img/loaf.jpg');
    await userEvent.setup({ delay: null }).click(screen.getByRole('button', { name: 'Sliced' }));
    expect(within(gallery).getByRole('img', { name: 'Country sourdough' })).toHaveAttribute('src', '/img/sliced.jpg');
    expect(within(gallery).getByRole('button', { name: 'Show photo 2' })).toBeInTheDocument();
  });

  it('never lets the quantity pass the stock', async () => {
    open('/products/P1?offer=O2');
    const user = userEvent.setup({ delay: null });
    expect(await screen.findByText('Only 2 left')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'One more' }));
    expect(screen.getByRole('button', { name: 'One more' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Add 2 to cart · $14.00' })).toBeEnabled();
  });

  it('lists the other shops selling it and switches to one', async () => {
    const { router } = open('/products/P1');
    const sellers = await screen.findByRole('region', { name: 'Also sold by' });
    expect(within(sellers).getByText('Sidewalk Citizen')).toBeInTheDocument();
    expect(within(sellers).getByText('On tomorrow’s run')).toBeInTheDocument();
    await userEvent.setup({ delay: null }).click(within(sellers).getByRole('link', { name: 'Choose — Sidewalk Citizen' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ offer: 'O2' }));
    expect(await screen.findByText('$7.00')).toBeInTheDocument();
    expect(screen.getByText(/Order by 7:05 a\.m\. for tomorrow 8–11 a\.m\. pooled \(\$1\.99\), or direct courier in 45 min\. Sidewalk Citizen packs at 7:30 a\.m\./)).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Also sold by' })).getByText('Glenmore Bakery')).toBeInTheDocument();
  });

  it('says when it is out of stock and disables Add', async () => {
    api = () => ({ body: { ...PRODUCT, offers: [{ ...GLENMORE, stock: 0, variants: [], runs: [] }] } });
    open('/products/P1');
    expect(await screen.findByText('Out of stock at Glenmore Bakery right now.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Add 1 to cart/ })).toBeDisabled();
  });

  it('shows an error when the cart refuses', async () => {
    api = c => (c.url === '/api/v1/cart/items' ? { status: 500, body: { detail: 'no' } } : { body: PRODUCT });
    open('/products/P1');
    await userEvent.setup({ delay: null }).click(await screen.findByRole('button', { name: /Add 1 to cart/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('We couldn’t add it to your cart. Try again.');
  });

  it('is in French', async () => {
    open('/products/P1', 'fr');
    expect(await screen.findByText(/Niveau Maître · ★ 4,8 \(211 vérifiés\)/)).toBeInTheDocument();
    expect(screen.getByText('Sur la tournée de ce soir')).toBeInTheDocument();
    expect(screen.getByText('900 g loaf · TPS calculée au paiement')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Ajouter 1 au panier · 7,50\s\$/ })).toBeInTheDocument();
    expect(screen.getByText(/Commandez avant 17 h 20 pour ce soir 18 h – 21 h en tournée groupée \(2,99\s\$\)/)).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Aussi vendu par' })).toBeInTheDocument();
  });

  it('has empty states when no shop of the market sells it', async () => {
    api = () => ({ body: { ...PRODUCT, offers: [] } });
    open('/products/P1');
    expect(await screen.findByText('No shop in Calgary sells this right now.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to Shop' })).toHaveAttribute('href', '/shop');
  });

  it('shows a skeleton while loading', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})));
    renderApp('/products/P1', { routes: { product: () => <Page /> } });
    expect(await screen.findByText('Loading the product…')).toBeInTheDocument();
  });
});
