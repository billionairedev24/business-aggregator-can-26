import { Suspense } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useParams, useSearch } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { ProductDetail, ProductSkeleton } from './ProductDetail';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

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

const mockAndOpenCheckout = () => {
  const calls = mockFetch(server);
  renderApp('/products/P1', { routes: { product: () => <Page />, checkout: () => <h1>Checkout page</h1> } });
  return { calls };
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

describe('Product detail (redesign § 1.3 PDP)', () => {
  it('says an age-restricted product needs a photo-ID check, with the province\'s age (demo rehearsal), en and fr', async () => {
    api = c => (c.url.startsWith('/api/v1/public/shop/products/P1') ? { body: { ...PRODUCT, name: 'Craft lager 6-pack', age: { ageClass: 'alcohol', minimumAge: 19 } } } : undefined);
    const { unmount } = open('/products/P1');
    const buy = await screen.findByRole('complementary', { name: 'Buy' });
    expect(within(buy).getByText('Photo ID needed')).toBeInTheDocument();
    expect(within(buy).getByText(/You must be 19 or older to buy this where it’s delivered\. You verify your age once with a government photo ID at checkout/)).toBeInTheDocument();
    await expectNoAxeViolations(document.body);
    unmount();
    open('/products/P1', 'fr');
    expect(await screen.findByText(/Vous devez avoir 19 ans ou plus pour acheter ce produit/)).toBeInTheDocument();
  });

  it('says nothing about ID for a product that is not age-restricted', async () => {
    open('/products/P1');
    await screen.findByRole('complementary', { name: 'Buy' });
    expect(screen.queryByText('Photo ID needed')).toBeNull();
  });

  it('shows the product, the shop, options, price and the delivery cut-off', async () => {
    open('/products/P1');
    expect(await screen.findByRole('heading', { level: 1, name: 'Country sourdough' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body); // S-109
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(crumbs).getByRole('link', { name: 'Shop' })).toHaveAttribute('href', '/shop');
    expect(within(crumbs).getByRole('link', { name: 'Bakery' })).toHaveAttribute('href', '/shop/bakery');
    expect(within(crumbs).getByRole('link', { name: 'Glenmore Bakery' })).toHaveAttribute('href', '/shop/stores/M1?dept=bakery');
    expect(screen.getByRole('link', { name: 'Visit Glenmore Bakery' })).toHaveAttribute('href', '/shop/stores/M1?dept=bakery');
    expect(screen.getAllByRole('img', { name: '4.8 out of 5 stars' }).length).toBeGreaterThan(0);
    expect(screen.queryByText(/Master/)).toBeNull(); // § 3.13: stars and reviews, not tier tags
    expect(screen.getByText('On tonight’s run')).toBeInTheDocument();
    expect(screen.getAllByText('$7.50')).toHaveLength(3); // the info column, the buy box and the phone's sticky bar
    expect(screen.getAllByText('$0.83/100 g').length).toBeGreaterThan(0); // the unit price from "900 g loaf"
    const buy = screen.getByRole('complementary', { name: 'Buy' });
    expect(within(buy).getByText('In stock')).toBeInTheDocument();
    expect(within(buy).getByText('Delivery tonight, 6–9 p.m.')).toBeInTheDocument();
    expect(within(buy).getByText('Pooled run · $2.99 · or tomorrow, 8–11 a.m.')).toBeInTheDocument();
    expect(within(buy).getByText('Direct courier in 45 min · $9.99')).toBeInTheDocument();
    expect(within(buy).getByRole('button', { name: 'Add to cart' })).toBeEnabled();
    expect(within(buy).getByRole('button', { name: 'Buy now' })).toBeEnabled();
    expect(within(buy).getByText('Held until you confirm delivery')).toBeInTheDocument();
    expect(within(buy).getByText('Final sale')).toBeInTheDocument();
    const specs = screen.getByRole('table', { name: 'Product details' });
    expect(within(specs).getByRole('rowheader', { name: 'Size' })).toBeInTheDocument();
    expect(within(specs).getByRole('cell', { name: '900 g loaf' })).toBeInTheDocument();
    expect(within(specs).getByRole('cell', { name: 'Final sale' })).toBeInTheDocument();
    expect(screen.getByText('900 g loaf · GST, if any, added at checkout')).toBeInTheDocument();
    expect(screen.getByText(/36-hour ferment/)).toBeInTheDocument();
    const size = screen.getByRole('radiogroup', { name: 'Size' });
    expect(within(size).getByRole('radio', { name: 'Whole · $7.50' })).toBeChecked();
    expect(within(size).getByRole('radio', { name: 'Half · sold out' })).toBeInTheDocument();
    const more = screen.getByRole('region', { name: 'More from Glenmore Bakery' });
    expect(within(more).getByRole('link', { name: 'Rye' })).toHaveAttribute('href', '/products/P2');
    expect(screen.getByRole('region', { name: 'Customer reviews' })).toHaveTextContent('4.8 out of 5 · 211 ratings of Glenmore Bakery');
  });

  it('counts down to the run’s order-by time in the buy box (E-15: "Order within 2 h 48 min")', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-09-30T20:32:00Z'));
    try {
      open('/products/P1');
      const buy = await screen.findByRole('complementary', { name: 'Buy' });
      expect(await within(buy).findByText('Order within 2 h 48 min')).toBeInTheDocument();
    } finally { vi.useRealTimers(); }
  });

  it('adds the chosen option and quantity to the cart', async () => {
    const { calls } = open('/products/P1');
    const user = userEvent.setup({ delay: null });
    await user.click(await screen.findByRole('radio', { name: 'Sliced · $7.50' }));
    await user.click(screen.getByRole('button', { name: 'One more Country sourdough' }));
    await user.click(within(screen.getByRole('complementary', { name: 'Buy' })).getByRole('button', { name: 'Add to cart' }));
    expect(await screen.findByRole('heading', { name: 'Cart page' })).toBeInTheDocument();
    expect(calls.find(c => c.url === '/api/v1/cart/items')?.body).toEqual({ offerId: 'O1', variantId: 'V2', qty: 2 });
  });

  it('buys now: adds to the cart and opens the checkout', async () => {
    const { calls } = mockAndOpenCheckout();
    await userEvent.setup({ delay: null }).click(await screen.findByRole('button', { name: 'Buy now' }));
    expect(await screen.findByRole('heading', { name: 'Checkout page' })).toBeInTheDocument();
    expect(calls.find(c => c.url === '/api/v1/cart/items')?.body).toEqual({ offerId: 'O1', variantId: 'V1', qty: 1 });
  });

  it("shows the chosen option's own photos (S-65), else the offer's", async () => {
    const withPhotos = { ...GLENMORE, images: ['/img/loaf.jpg'], variants: [GLENMORE.variants[0], { ...GLENMORE.variants[1], images: ['/img/sliced.jpg', '/img/sliced-2.jpg'] }] };
    api = c => (c.url.startsWith('/api/v1/public/shop/products/P1') ? { body: { ...PRODUCT, offers: [withPhotos] } } : undefined);
    open('/products/P1');
    expect(await screen.findByRole('img', { name: 'Country sourdough' })).toHaveAttribute('src', '/img/loaf.jpg');
    await userEvent.setup({ delay: null }).click(screen.getByRole('radio', { name: 'Sliced · $7.50' }));
    expect(screen.getByRole('img', { name: 'Country sourdough' })).toHaveAttribute('src', '/img/sliced.jpg');
    expect(within(screen.getByRole('group', { name: 'Photos' })).getByRole('button', { name: 'Show photo 2' })).toBeInTheDocument();
  });

  it('never lets the quantity pass the stock', async () => {
    open('/products/P1?offer=O2');
    const user = userEvent.setup({ delay: null });
    expect(await screen.findByText('Only 2 left')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'One more Country sourdough' }));
    expect(screen.getByRole('button', { name: 'One more Country sourdough' })).toBeDisabled();
    expect(screen.getByRole('group', { name: 'Quantity of Country sourdough' })).toHaveTextContent('2');
    expect(within(screen.getByRole('complementary', { name: 'Buy' })).getByRole('button', { name: 'Add to cart' })).toBeEnabled();
  });

  it('lists the other shops selling it and switches to one', async () => {
    const { router } = open('/products/P1');
    const sellers = await screen.findByRole('region', { name: 'Other sellers' });
    expect(within(sellers).getByText('Sidewalk Citizen')).toBeInTheDocument();
    expect(within(sellers).getByText('On tomorrow’s run')).toBeInTheDocument();
    await userEvent.setup({ delay: null }).click(within(sellers).getByRole('link', { name: 'Choose — Sidewalk Citizen' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ offer: 'O2' }));
    expect((await screen.findAllByText('$7.00')).length).toBeGreaterThan(0);
    expect(screen.getByText('Delivery tomorrow, 8–11 a.m.')).toBeInTheDocument();
    expect(screen.getByText('Pooled run · $1.99')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Other sellers' })).getByText('Glenmore Bakery')).toBeInTheDocument();
  });

  it('says when it is out of stock and disables Add', async () => {
    api = () => ({ body: { ...PRODUCT, offers: [{ ...GLENMORE, stock: 0, variants: [], runs: [] }] } });
    open('/products/P1');
    expect(await screen.findByText('Out of stock at Glenmore Bakery right now.')).toBeInTheDocument();
    for (const add of screen.getAllByRole('button', { name: 'Add to cart' })) expect(add).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Buy now' })).toBeDisabled();
  });

  it('shows an error when the cart refuses', async () => {
    api = c => (c.url === '/api/v1/cart/items' ? { status: 500, body: { detail: 'no' } } : { body: PRODUCT });
    open('/products/P1');
    await userEvent.setup({ delay: null }).click(within(await screen.findByRole('complementary', { name: 'Buy' })).getByRole('button', { name: 'Add to cart' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('We couldn’t add it to your cart. Try again.');
  });

  it('is in French', async () => {
    open('/products/P1', 'fr');
    expect(await screen.findByRole('link', { name: 'Visiter Glenmore Bakery' })).toBeInTheDocument();
    expect(screen.getAllByRole('img', { name: '4,8 sur 5 étoiles' }).length).toBeGreaterThan(0);
    expect(screen.getByText('Sur la tournée de ce soir')).toBeInTheDocument();
    expect(screen.getByText('900 g loaf · TPS, s’il y a lieu, ajoutée au paiement')).toBeInTheDocument();
    const buy = screen.getByRole('complementary', { name: 'Acheter' });
    expect(within(buy).getByRole('button', { name: 'Ajouter au panier' })).toBeInTheDocument();
    expect(within(buy).getByRole('button', { name: 'Acheter maintenant' })).toBeInTheDocument();
    expect(within(buy).getByText('Livraison ce soir, 18 h – 21 h')).toBeInTheDocument();
    expect(screen.getByRole('radiogroup', { name: 'Format' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Autres vendeurs' })).toBeInTheDocument();
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
