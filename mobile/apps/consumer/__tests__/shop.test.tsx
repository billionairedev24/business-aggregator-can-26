import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Linking from 'expo-linking';
import { act, fireEvent, screen, waitFor, within } from 'expo-router/testing-library';

import { parseDeepLink, routeOf } from '@northline/mobile-kit';

import type { Started } from '../src/api/shop';
import { FIXTURE_PROOF, seedOrder } from '../src/fixtures/shop';
import { setServices } from '../src/services';
import { appRoute } from '../src/shop/common';
import { TRACK_POLL_MS } from '../src/shop/Order';
import { setCardPayments, stripeCardPayments, type CardPayments } from '../src/shop/payments';
import { start } from './support';

jest.mock('@stripe/stripe-react-native', () => ({
  initStripe: jest.fn(async () => undefined),
  initPaymentSheet: jest.fn(async () => ({})),
  presentPaymentSheet: jest.fn(async () => ({})),
  retrievePaymentIntent: jest.fn(async () => ({ paymentIntent: { paymentMethod: { id: 'pm_new' } } })),
  confirmPayment: jest.fn(async () => ({ paymentIntent: { status: 'RequiresCapture' } })),
  PaymentSheetError: { Canceled: 'Canceled', Failed: 'Failed' },
  handleURLCallback: jest.fn(async () => true),
}));

afterEach(async () => {
  setServices(null);
  setCardPayments(null);
  jest.clearAllMocks();
  await AsyncStorage.clear();
});

/** A complete address saved on the phone's Location screen (S-98), in the fixture market. */
const HOME = {
  label: '1204 Example Ave, Sampleville',
  city: 'Sampleville',
  province: 'XA',
  street: '1204 Example Ave',
  postalCode: 'A1A 1A1',
  unit: 'Buzz 0804 · leave at door',
  marketId: 'mkt-sampleville',
};
const saved = (l: object = HOME) => ({ 'nl.location': JSON.stringify(l) });
const netinfo = () => jest.requireMock('@react-native-community/netinfo') as { __emit: (s: object) => void };

type Wrap = (f: typeof fetch) => typeof fetch;
/** Answers `match`ing requests with `answer` (status, body); everything else goes to the fixture backend. */
const intercept =
  (match: (url: string, init: RequestInit) => boolean, answer: () => [number, unknown] | Promise<[number, unknown]>): Wrap =>
  (f) =>
    (async (input: RequestInfo | URL, init: RequestInit = {}) => {
      if (match(String(input), init)) {
        const [status, body] = await answer();
        return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
      }
      return f(input, init);
    }) as typeof fetch;
const headersOf = (init: RequestInit) => Object.fromEntries(Object.entries((init.headers ?? {}) as Record<string, string>).map(([k, v]) => [k.toLowerCase(), v]));

/** The app on the fixture backend with an order in the given state (seeded, then the screen's queries re-read). */
async function withOrder(orderState: Parameters<typeof seedOrder>[2], url: string) {
  const started = await start({ signedIn: true, store: saved(), url });
  seedOrder(started.server.shop, Date.now(), orderState);
  await act(async () => {
    await started.services.queryClient.invalidateQueries();
  });
  return started;
}

describe('B1 Home', () => {
  it('shows where you are, tonight’s pooled run, the departments, the service tiles and trusted providers — to a guest', async () => {
    const { server } = await start({ welcomed: true, store: saved() });
    expect(await screen.findByText('1204 Example Ave, Sampleville ▾')).toBeTruthy();
    expect(screen.getByText(/^Good (morning|afternoon|evening)$/)).toBeTruthy();
    expect(await screen.findByText("Tonight's pooled run")).toBeTruthy();
    expect(screen.getByText(/^Leaves 6:00 p\.m\. · \$2\.99 · order by 5:19 p\.m\.$/)).toBeTruthy();
    expect(screen.getByText('5 neighbours')).toBeTruthy();
    for (const tile of ['Bakery', 'Butcher', 'Produce', 'Mechanic', 'Cleaning', 'Events & bar', 'Realtor']) expect(screen.getByRole('button', { name: tile })).toBeTruthy();
    expect(await screen.findByText('Trusted near you')).toBeTruthy();
    expect(screen.getByText('Tidy Nook Cleaners')).toBeTruthy();
    expect(server.calls.find((c) => c.path === '/public/shop')).toMatchObject({ signed: false });
    expect(screen.queryByText('Your week')).toBeNull();
  });

  it('greets the person by name, shows their week, and opens search, a department and a service', async () => {
    const { view } = await withOrder('picked_up', '/home');
    expect(await screen.findByText(/^Good (morning|afternoon|evening), Ada$/)).toBeTruthy();
    expect(await screen.findByText('Your week')).toBeTruthy();
    fireEvent.press(screen.getByText('Order NL-1001'));
    await waitFor(() => expect(view.getPathname()).toBe('/orders/ord-1001/track'));
  });

  it('opens a department as a filtered browse, and the services', async () => {
    const { view } = await start({ welcomed: true, store: saved() });
    fireEvent.press(await screen.findByRole('button', { name: 'Bakery' }));
    expect(await screen.findByText('Department · Bakery')).toBeTruthy();
    expect(await screen.findByText('Country sourdough')).toBeTruthy();
    expect(screen.queryByText('Sourdough rye')).toBeNull(); // not on tonight's run
    fireEvent.press(screen.getByRole('checkbox', { name: "On tonight's run" }));
    expect(await screen.findByText('Sourdough rye')).toBeTruthy();
    expect(view.getPathname()).toBe('/search');
  });

  it('loading: a skeleton; error: the reason and Try again, which recovers', async () => {
    let fail = true;
    await start({ welcomed: true, store: saved(), wrap: intercept((u) => u.includes('/public/shop?') && fail, () => [503, { detail: 'down' }]) });
    expect(screen.getByTestId('loading')).toBeTruthy();
    expect(await screen.findByText('Northline is having trouble right now. Try again in a moment.', {}, { timeout: 8000 })).toBeTruthy();
    fail = false;
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText("Tonight's pooled run")).toBeTruthy();
  }, 15000);

  it('offline: the banner, and what was shown stays', async () => {
    await start({ welcomed: true, store: saved() });
    await screen.findByText("Tonight's pooled run");
    act(() => netinfo().__emit({ isConnected: false, isInternetReachable: false }));
    expect(await screen.findByText("You're offline. Northline will catch up when you're back online.")).toBeTruthy();
    expect(screen.getByText("Tonight's pooled run")).toBeTruthy();
    act(() => netinfo().__emit({ isConnected: true, isInternetReachable: true }));
  });

  it('says when Northline doesn’t deliver there yet (empty)', async () => {
    await start({ welcomed: true, store: saved({ label: '77 Harbour Road, Faraway', city: 'Faraway', province: 'XC' }) });
    expect(await screen.findByText("Northline doesn't deliver to Faraway yet. Look around, or choose another address.")).toBeTruthy();
    expect(screen.getByText('No shops deliver here yet.')).toBeTruthy();
  });

  it('is in French on a French phone', async () => {
    const { getLocales } = jest.requireMock('expo-localization') as { getLocales: jest.Mock };
    getLocales.mockReturnValueOnce([{ languageTag: 'fr-CA' }]);
    await start({ welcomed: true, store: saved() });
    expect(await screen.findByText('La tournée groupée de ce soir')).toBeTruthy();
    expect(screen.getByText(/^Départ 18 h 00 · 2,99 \$ · commandez avant 17 h 19$/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Événements et bar' })).toBeTruthy();
  });
});

describe('B2 Search', () => {
  it('searches the shops with "On tonight\'s run" on, filters, sorts, and opens a result', async () => {
    const { server, view } = await start({ welcomed: true, store: saved(), url: '/search' });
    fireEvent.changeText(await screen.findByTestId('search-input'), 'sour');
    expect(await screen.findByText('1 result · sorted by')).toBeTruthy();
    expect(screen.getByText('Country sourdough')).toBeTruthy();
    const call = server.calls.filter((c) => c.path === '/search').at(-1)!;
    expect(call.signed).toBe(false);
    fireEvent.press(screen.getByRole('checkbox', { name: "On tonight's run" }));
    expect(await screen.findByText('Sourdough rye')).toBeTruthy();
    expect(screen.getByText('2 results · sorted by')).toBeTruthy();
    fireEvent.press(screen.getByTestId('search-sort'));
    expect(await screen.findByText('price, low to high ▾')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: /^Sourdough rye/ }));
    await waitFor(() => expect(view.getPathname()).toBe('/product/o-rye'));
  });

  it('sends the design’s filters to the api and offers to clear them when nothing matches', async () => {
    const urls: string[] = [];
    await start({
      welcomed: true,
      store: saved(),
      url: '/search?q=ribeye',
      wrap: (f) => (async (i: RequestInfo | URL, init?: RequestInit) => (urls.push(String(i)), f(i, init))) as typeof fetch,
    });
    expect(await screen.findByText('Ribeye, AAA')).toBeTruthy();
    fireEvent.press(screen.getByRole('checkbox', { name: 'Under $10' }));
    expect(await screen.findByText('Nothing matches “ribeye” with these filters.')).toBeTruthy();
    const last = new URL(urls.filter((u) => u.includes('/search?')).at(-1)!);
    expect(Object.fromEntries(last.searchParams)).toMatchObject({ q: 'ribeye', kind: 'product', market: 'XA', delivery: 'tonight', maxPrice: '999' });
    fireEvent.press(screen.getByRole('button', { name: 'Clear filters' }));
    expect(await screen.findByText('Ribeye, AAA')).toBeTruthy();
    fireEvent.press(screen.getByRole('checkbox', { name: 'Halal' }));
    fireEvent.press(screen.getByRole('checkbox', { name: 'Master sellers' }));
    expect(await screen.findByText('Nothing matches “ribeye” with these filters.')).toBeTruthy();
  });

  it('error: the api’s words and Try again', async () => {
    await start({ welcomed: true, store: saved(), url: '/search?q=x', wrap: intercept((u) => u.includes('/search?'), () => [429, { code: 'rate_limited' }]) });
    expect(await screen.findByText('Too many attempts. Wait a moment and try again.', {}, { timeout: 8000 })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
  }, 15000);
});

describe('B3 Product and B4 Cart', () => {
  it('shows the product as the design does and adds it — a guest, by the guest id — then opens the cart with its badge', async () => {
    const { server, view, services } = await start({ welcomed: true, store: saved(), url: '/product/p-sourdough' });
    expect(await screen.findByText('Country sourdough')).toBeTruthy();
    expect(screen.getByText('Maple Lane Bakery')).toBeTruthy(); // the header: the shop
    expect(screen.getByText('Maple Lane Bakery · Master tier · ★ 4.8 (211)')).toBeTruthy();
    expect(screen.getByText('$7.50')).toBeTruthy();
    expect(screen.getByText('900 g loaf')).toBeTruthy();
    expect(screen.getByText("On tonight's run")).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'Whole' }).props.accessibilityState).toMatchObject({ checked: true });
    fireEvent.press(screen.getByRole('radio', { name: 'Sliced' }));
    fireEvent.press(screen.getByRole('button', { name: 'One more' }));
    expect(screen.getByRole('button', { name: 'Add 2 · $15.00' })).toBeTruthy();
    fireEvent.press(screen.getByTestId('product-add'));
    await waitFor(() => expect(view.getPathname()).toBe('/cart'));
    expect(await screen.findByText('2 items · 1 shop · one delivery')).toBeTruthy();
    expect(screen.getByText('Sliced · 900 g loaf')).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'Cart, 2 items in your cart' })).toBeTruthy();
    const add = server.calls.find((c) => c.method === 'POST' && c.path === '/cart/items')!;
    expect(add).toMatchObject({ signed: false, guest: services.guestId() });
  });

  it('changes quantities and removes lines; the sums and Checkout follow', async () => {
    const { server, services } = await start({ signedIn: true, store: saved(), url: '/cart' });
    server.shop.carts.set('user', [
      { itemId: 'ci-1', offerId: 'o-ribeye', qty: 2 },
      { itemId: 'ci-2', offerId: 'o-kale', qty: 1 },
    ]);
    await act(async () => {
      await services.queryClient.invalidateQueries({ queryKey: ['cart'] });
    });
    expect(await screen.findByText('3 items · 2 shops · one delivery')).toBeTruthy();
    expect(screen.getByText('Riverside Butcher')).toBeTruthy();
    expect(screen.getByText('$37.00')).toBeTruthy();
    expect(screen.getByText('from $2.99')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Checkout · $41.25' })).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'One more Kale & chard mix' }));
    expect(await screen.findByRole('button', { name: 'Checkout · $45.50' })).toBeTruthy();
    // stock is 2: no more
    expect(screen.getByRole('button', { name: 'One more Kale & chard mix' }).props.accessibilityState).toMatchObject({ disabled: true });
    fireEvent.press(screen.getByRole('button', { name: 'One less Ribeye, AAA' }));
    expect(await screen.findByRole('button', { name: 'Checkout · $27.00' })).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Remove Ribeye, AAA' }));
    expect(await screen.findByText('2 items · 1 shop · one delivery')).toBeTruthy();
    expect(screen.queryByText('Riverside Butcher')).toBeNull();
    expect(server.calls.filter((c) => c.path.startsWith('/cart/items/')).map((c) => c.method)).toEqual(['PATCH', 'PATCH', 'DELETE']);
  });

  it('empty: one line and Browse; a guest’s Checkout asks to sign in', async () => {
    const { view } = await start({ welcomed: true, store: saved(), url: '/cart' });
    expect(await screen.findByText('Your cart is empty.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Browse the shops' }));
    await waitFor(() => expect(view.getPathname()).toBe('/search'));
    screen.unmount();
    setServices(null);
    await start({ welcomed: true, store: saved(), url: '/checkout' });
    expect(await screen.findByText('Sign in to check out. Your cart comes with you.')).toBeTruthy();
    expect(screen.getByTestId('sign-in-prompt')).toBeTruthy();
  });

  it('cart error and product states: no answer, not sold here, adding offline keeps the screen', async () => {
    await start({ welcomed: true, store: saved(), url: '/cart', wrap: intercept((u) => u.includes('/cart?'), () => [500, {}]) });
    expect(await screen.findByText('Northline is having trouble right now. Try again in a moment.', {}, { timeout: 8000 })).toBeTruthy();
    screen.unmount();
    setServices(null);
    await start({ welcomed: true, store: saved({ label: 'Faraway', city: 'Faraway', province: 'XC' }), url: '/product/p-kale' });
    expect(await screen.findByText('Not sold near Faraway yet.')).toBeTruthy();
    screen.unmount();
    setServices(null);
    await start({
      welcomed: true,
      store: saved(),
      url: '/product/p-kale',
      wrap: intercept((u, i) => u.includes('/cart/items') && i.method === 'POST', () => {
        throw new TypeError('Network request failed');
      }),
    });
    expect(await screen.findByText('Only 2 left')).toBeTruthy();
    fireEvent.press(screen.getByTestId('product-add'));
    expect(await screen.findByText("We couldn't reach Northline. Check your connection and try again.")).toBeTruthy();
    expect(screen.getByText('Kale & chard mix')).toBeTruthy();
  }, 20000);
});

/** Signed in with sourdough ×2 and kale in the cart. */
async function signedInWithCart(options: Parameters<typeof start>[0] = {}) {
  const started = await start({ signedIn: true, store: saved(), ...options });
  started.server.shop.carts.set('user', [
    { itemId: 'ci-1', offerId: 'o-sourdough', qty: 2 },
    { itemId: 'ci-2', offerId: 'o-kale', qty: 1 },
  ]);
  await act(async () => {
    await started.services.queryClient.invalidateQueries();
  });
  return started;
}

describe('B5 Checkout', () => {
  it('delivers to the saved address, offers the windows and substitutions, and quotes the province’s tax', async () => {
    const { view, server } = await signedInWithCart({ url: '/checkout' });
    expect(await screen.findByText('1204 Example Ave')).toBeTruthy();
    expect(screen.getByText('Sampleville XA A1A 1A1 · Buzz 0804 · leave at door')).toBeTruthy();
    expect(screen.getByRole('radio', { name: /^Tonight 6:00 p\.m\.–9:00 p\.m\., Pooled with 5 neighbours, \$2\.99$/ }).props.accessibilityState).toMatchObject({ selected: true });
    expect(screen.getByRole('radio', { name: /^Tomorrow 8:00 a\.m\.–11:00 a\.m\., Pooled run · order by 7:19 a\.m\., \$1\.99$/ })).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'Now · 45 min, Direct courier, $9.99' })).toBeTruthy();
    expect(await screen.findByText('GST 5%')).toBeTruthy();
    expect(screen.getByText('$19.25')).toBeTruthy();
    expect(screen.getByText('$1.11')).toBeTruthy();
    expect(screen.getByText('$23.35')).toBeTruthy();
    fireEvent.press(screen.getByRole('radio', { name: 'Now · 45 min, Direct courier, $9.99' }));
    expect(await screen.findByText('$30.70')).toBeTruthy();
    fireEvent.press(screen.getByRole('radio', { name: 'Text me' }));
    await waitFor(() => expect(screen.getByTestId('checkout-continue').props.accessibilityState).toMatchObject({ disabled: false }));
    fireEvent.press(screen.getByTestId('checkout-continue'));
    await waitFor(() => expect(view.getPathname()).toBe('/pay'));
    expect(view.getSearchParams()).toMatchObject({ kind: 'direct', sub: 'ask', address: 'location' });
    expect(server.calls.filter((c) => c.path === '/me/checkout/quote').every((c) => c.signed)).toBe(true);
  });

  it('age-restricted cart: the age step holds Continue until the ID check, which opens in the in-app browser (2026-10-04)', async () => {
    const { server, services } = await signedInWithCart({ url: '/checkout' });
    server.shop.age = { required: true, minimumAge: 19, classes: ['alcohol'], state: 'none' };
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText('Your cart has alcohol. You must be 19 or older to buy them where they’re delivered.')).toBeTruthy();
    expect(screen.getByText('Verify your age to continue.')).toBeTruthy();
    expect(screen.getByTestId('checkout-continue').props.accessibilityState).toMatchObject({ disabled: true });
    const WebBrowser = jest.requireMock('expo-web-browser') as { openAuthSessionAsync: jest.Mock };
    WebBrowser.openAuthSessionAsync.mockResolvedValueOnce({ type: 'success', url: 'ca.northline.app:/age-verified' });
    fireEvent.press(screen.getByRole('button', { name: 'Verify my age' }));
    await waitFor(() => expect(WebBrowser.openAuthSessionAsync).toHaveBeenCalledWith('https://identity.fixture.invalid/session/1', 'ca.northline.app:/age-verified'));
    expect(await screen.findByText(/^Verified 19\+/)).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId('checkout-continue').props.accessibilityState).toMatchObject({ disabled: false }));
    expect(server.calls.some((c) => c.method === 'POST' && c.path === '/me/age-verification' && c.signed)).toBe(true);
  });

  it('age step in French; under age the items can’t be bought', async () => {
    const { getLocales } = jest.requireMock('expo-localization') as { getLocales: jest.Mock };
    getLocales.mockReturnValueOnce([{ languageTag: 'fr-CA' }]);
    const { server, services } = await signedInWithCart({ url: '/checkout' });
    server.shop.age = { required: true, minimumAge: 18, classes: ['alcohol', 'tobacco'], state: 'none' };
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText('Votre panier contient de l’alcool et des produits du tabac ou de vapotage. Vous devez avoir 18 ans ou plus pour les acheter là où ils sont livrés.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Vérifier mon âge' })).toBeTruthy();
    server.shop.age = { ...server.shop.age, state: 'under_age' };
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText(/moins de 18 ans/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Continuer vers le paiement' }).props.accessibilityState).toMatchObject({ disabled: true });
  });

  it('asks for an address when none is complete, and the api’s rules come in the person’s language', async () => {
    const { view } = await signedInWithCart({ url: '/checkout', store: saved({ label: 'Sampleville', city: 'Sampleville' }) });
    expect(await screen.findByText('Where should we bring it?')).toBeTruthy();
    fireEvent.press(screen.getByTestId('checkout-add-address'));
    await waitFor(() => expect(view.getPathname()).toBe('/location'));
    expect(view.getSearchParams()).toMatchObject({ next: '/checkout' });
    screen.unmount();
    setServices(null);
    const { getLocales } = jest.requireMock('expo-localization') as { getLocales: jest.Mock };
    getLocales.mockReturnValueOnce([{ languageTag: 'fr-CA' }]);
    await signedInWithCart({ url: '/checkout', store: saved({ ...HOME, postalCode: '12345' }) });
    expect(await screen.findByText('Entrez un code postal canadien.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Continuer vers le paiement' }).props.accessibilityState).toMatchObject({ disabled: true });
  });

  it('empty cart: one line and Browse; and the account’s saved address when the phone has none', async () => {
    await start({ signedIn: true, store: saved(), url: '/checkout' });
    expect(await screen.findByText('Your cart is empty.')).toBeTruthy();
    screen.unmount();
    setServices(null);
    const { server, services } = await signedInWithCart({ url: '/checkout', store: {} });
    server.shop.addresses.push({ id: 'addr-1', street: '9 Sample St', unit: '3', city: 'Sampleville', province: 'XA', postal: 'A1A 1A1', isDefault: true });
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText('9 Sample St, 3')).toBeTruthy();
  });
});

describe('B6 Payment', () => {
  const PAY = '/pay?kind=pooled&window=w-today&sub=similar&address=location';

  it('pays with the stand-in: the bank step, then the order is placed with one Idempotency-Key, and the cart empties', async () => {
    const keys: string[] = [];
    const { view } = await signedInWithCart({
      url: PAY,
      wrap: (f) => (async (i: RequestInfo | URL, init: RequestInit = {}) => {
        if (String(i).includes('/me/checkouts')) keys.push(headersOf(init)['idempotency-key'] ?? '');
        return f(i, init);
      }) as typeof fetch,
    });
    expect(await screen.findByRole('radio', { name: 'Visa ··4471, 09/28' })).toBeTruthy();
    expect(screen.getByText('Test payments · nothing is charged. Stripe isn’t configured here, so a stand-in approves every payment.')).toBeTruthy();
    expect(await screen.findByText(/^Charged \$23\.35 now\./)).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Pay $23.35' }));
    expect(await screen.findByText('Confirm this payment')).toBeTruthy();
    expect(screen.getByText('Northline Marketplace · $23.35. Approve in your banking app or enter the code we texted.')).toBeTruthy();
    fireEvent.press(screen.getByTestId('bank-approve'));
    await waitFor(() => expect(view.getPathname()).toBe('/orders/ord-48213/confirmed'));
    expect(await screen.findByText(/^Order placed\. Arriving tonight 6:00 p\.m\.–9:00 p\.m\.$/)).toBeTruthy();
    expect(screen.getByText('Order NL-48213 · $23.35 · 2 shops packing now. We’ll notify you when the courier leaves.')).toBeTruthy();
    expect(screen.getByLabelText('Paid · receipt emailed, done')).toBeTruthy();
    expect(screen.getByLabelText('Shops packing · 0 of 2 accepted, now')).toBeTruthy();
    expect(keys).toHaveLength(2);
    expect(keys.every((k) => k.length >= 16)).toBe(true);
    expect(screen.queryByRole('tab')).toBeNull(); // a sub-screen
    fireEvent.press(screen.getByRole('button', { name: 'Back to home' }));
    await waitFor(() => expect(view.getPathname()).toBe('/home'));
    expect(screen.getByRole('tab', { name: 'Cart' })).toBeTruthy(); // no badge: the cart is empty
  });

  it('keeps the same Idempotency-Key when there was no answer, so trying again can’t pay twice', async () => {
    const keys: string[] = [];
    let drop = true;
    await signedInWithCart({
      url: PAY,
      wrap: (f) => (async (i: RequestInfo | URL, init: RequestInit = {}) => {
        if (String(i).includes('/me/checkouts?')) {
          keys.push(headersOf(init)['idempotency-key'] ?? '');
          if (drop) {
            drop = false;
            throw new TypeError('Network request failed');
          }
        }
        return f(i, init);
      }) as typeof fetch,
    });
    fireEvent.press(await screen.findByRole('button', { name: 'Pay $23.35' }));
    expect(await screen.findByText("We couldn't reach Northline. Check your connection and try again.")).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Pay $23.35' }));
    expect(await screen.findByText('Confirm this payment')).toBeTruthy();
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it('asks for the authenticator code when the api wants a step-up, and sends the proof as X-Step-Up', async () => {
    const stepUps: string[] = [];
    const { server } = await signedInWithCart({
      url: PAY,
      wrap: (f) => {
        const inner = intercept((u) => u.endsWith('/api/auth/step-up/totp'), () => [200, { proof: FIXTURE_PROOF, expiresAt: '2026-10-02T00:05:00Z' }])(f);
        return (async (i: RequestInfo | URL, init: RequestInit = {}) => {
          if (String(i).includes('/me/checkouts?')) stepUps.push(headersOf(init)['x-step-up'] ?? '');
          if (String(i).endsWith('/api/auth/step-up/totp')) expect(init.credentials).toBe('include');
          return inner(i, init);
        }) as typeof fetch;
      },
    });
    server.shop.stepUp = 'required';
    fireEvent.press(await screen.findByRole('button', { name: 'Pay $23.35' }));
    expect(await screen.findByText('Confirm it’s you')).toBeTruthy();
    fireEvent.press(screen.getByTestId('step-up-confirm'));
    expect(await screen.findByText('That code didn’t work. Check it and try again.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('step-up-code'), '123456');
    fireEvent.press(screen.getByTestId('step-up-confirm'));
    expect(await screen.findByText('Confirm this payment')).toBeTruthy();
    expect(stepUps).toEqual(['', FIXTURE_PROOF]);
  });

  it('words a wrong or locked authenticator code (not the raw key)', async () => {
    let answer: [number, unknown] = [422, { code: 'invalid_code', detail: 'mismatch' }];
    const { server } = await signedInWithCart({ url: PAY, wrap: intercept((u) => u.endsWith('/api/auth/step-up/totp'), () => answer) });
    server.shop.stepUp = 'required';
    fireEvent.press(await screen.findByRole('button', { name: 'Pay $23.35' }));
    fireEvent.changeText(await screen.findByTestId('step-up-code'), '000000');
    fireEvent.press(screen.getByTestId('step-up-confirm'));
    expect(await screen.findByText('That code didn’t work. Check it and try again.')).toBeTruthy();
    expect(screen.queryByText(/shop\.stepUp\./)).toBeNull();
    answer = [429, { code: 'rate_limited' }];
    fireEvent.press(screen.getByTestId('step-up-confirm'));
    expect(await screen.findByText('Too many tries. Wait a few minutes, then try again.')).toBeTruthy();
  });

  it('a phone that can’t step up is told so; an account without a second factor is sent to add one', async () => {
    const { server } = await signedInWithCart({ url: PAY, wrap: intercept((u) => u.endsWith('/api/auth/step-up/totp'), () => [401, { code: 'unauthenticated' }]) });
    server.shop.stepUp = 'required';
    fireEvent.press(await screen.findByRole('button', { name: 'Pay $23.35' }));
    fireEvent.changeText(await screen.findByTestId('step-up-code'), '123456');
    fireEvent.press(screen.getByTestId('step-up-confirm'));
    expect(await screen.findByText('This phone can’t confirm your second factor right now. Sign in again here, or pay on the Northline website.')).toBeTruthy();
    screen.unmount();
    setServices(null);
    const again = await signedInWithCart({ url: PAY });
    again.server.shop.stepUp = 'enrol';
    fireEvent.press(await screen.findByRole('button', { name: 'Pay $23.35' }));
    expect(await screen.findByText('Add a second factor to pay')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Open the Northline website' }));
    expect(Linking.openURL).toHaveBeenCalledWith('http://localhost:3000/account?tab=security');
  });

  it('pays through the card port when Stripe runs the payments: the saved card, or a new one; a cancel charges nothing', async () => {
    const pay = jest.fn<ReturnType<CardPayments['pay']>, Parameters<CardPayments['pay']>>(async () => ({ status: 'cancelled' }));
    setCardPayments({ pay });
    const { server, view, services } = await signedInWithCart({ url: PAY });
    server.shop.provider = 'stripe';
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    fireEvent.press(await screen.findByRole('radio', { name: '+ New card' }));
    expect(await screen.findByText('You’ll enter the card on Stripe’s secure form. Card details go to Stripe only — never Northline.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Pay $23.35' }));
    expect(await screen.findByText('Payment cancelled. Nothing was charged.')).toBeTruthy();
    expect(pay.mock.calls[0]![1]).toEqual({ kind: 'new' });
    expect(pay.mock.calls[0]![0].intents.map((i) => i.status)).toEqual(['requires_payment_method', 'requires_payment_method', 'requires_payment_method']);
    pay.mockResolvedValueOnce({ status: 'paid' });
    fireEvent.press(screen.getByRole('radio', { name: 'Visa ··4471, 09/28' }));
    fireEvent.press(screen.getByRole('button', { name: 'Pay $23.35' }));
    await waitFor(() => expect(view.getPathname()).toBe('/orders/ord-48213/confirmed'));
    expect(pay.mock.calls[1]![1]).toEqual({ kind: 'saved', paymentMethodId: 'pm_fixture_visa' });
    // the same checkout both times (the replayed start): one order
    expect(pay.mock.calls[1]![0].checkoutId).toBe(pay.mock.calls[0]![0].checkoutId);
  });

  it('says what went wrong: sold out, and a declined card', async () => {
    const { server } = await signedInWithCart({ url: PAY });
    server.shop.carts.get('user')![1]!.qty = 3; // kale: only 2 left
    fireEvent.press(await screen.findByRole('button', { name: /^Pay \$/ }));
    expect(await screen.findByText('Something in your cart just sold out. Check your cart and try again.')).toBeTruthy();
    screen.unmount();
    setServices(null);
    setCardPayments({ pay: async () => ({ status: 'failed', message: 'Your card has insufficient funds.' }) });
    const again = await signedInWithCart({ url: PAY });
    again.server.shop.provider = 'stripe';
    fireEvent.press(await screen.findByRole('button', { name: 'Pay $23.35' }));
    expect(await screen.findByText('Your card was declined: Your card has insufficient funds.')).toBeTruthy();
  });

  it('without the checkout’s choices sends the person back to Checkout; guests are asked to sign in', async () => {
    const { view } = await start({ signedIn: true, store: saved(), url: '/pay' });
    fireEvent.press(await screen.findByRole('button', { name: 'Back to checkout' }));
    await waitFor(() => expect(view.getPathname()).toBe('/checkout'));
    screen.unmount();
    setServices(null);
    await start({ welcomed: true, url: PAY });
    expect(await screen.findByTestId('sign-in-prompt')).toBeTruthy();
  });
});

describe('the Stripe adapter (the payment port’s real side, Stripe’s SDK mocked)', () => {
  const stripe = () => jest.requireMock('@stripe/stripe-react-native') as Record<string, jest.Mock>;
  const started: Started = {
    checkoutId: 'co-1',
    orderId: 'ord-1',
    ref: 'NL-1',
    totalCents: 2335,
    expiresAt: '2026-10-02T00:15:00Z',
    payment: { provider: 'stripe', publishableKey: 'pk_test_x' },
    intents: [
      { paymentIntent: 'pi_1', clientSecret: 'pi_1_secret', status: 'requires_payment_method', amountCents: 1500 },
      { paymentIntent: 'pi_2', clientSecret: 'pi_2_secret', status: 'requires_payment_method', amountCents: 446 },
      { paymentIntent: 'pi_3', clientSecret: 'pi_3_secret', status: 'requires_payment_method', amountCents: 389 },
    ],
  };

  it('a new card: PaymentSheet for the first PaymentIntent, its PaymentMethod for the others', async () => {
    expect(await stripeCardPayments.pay(started, { kind: 'new' })).toEqual({ status: 'paid' });
    expect(stripe().initStripe).toHaveBeenCalledWith({ publishableKey: 'pk_test_x', urlScheme: 'ca.northline.app', setReturnUrlSchemeOnAndroid: true });
    expect(stripe().initPaymentSheet).toHaveBeenCalledWith(expect.objectContaining({ paymentIntentClientSecret: 'pi_1_secret', merchantDisplayName: 'Northline' }));
    expect(stripe().presentPaymentSheet).toHaveBeenCalled();
    expect(stripe().confirmPayment!.mock.calls).toEqual([
      ['pi_2_secret', { paymentMethodType: 'Card', paymentMethodData: { paymentMethodId: 'pm_new' } }],
      ['pi_3_secret', { paymentMethodType: 'Card', paymentMethodData: { paymentMethodId: 'pm_new' } }],
    ]);
  });

  it('a saved card confirms every PaymentIntent with it; cancel and decline are told apart', async () => {
    expect(await stripeCardPayments.pay(started, { kind: 'saved', paymentMethodId: 'pm_saved' })).toEqual({ status: 'paid' });
    expect(stripe().presentPaymentSheet).not.toHaveBeenCalled();
    expect(stripe().confirmPayment).toHaveBeenCalledTimes(3);
    stripe().presentPaymentSheet!.mockResolvedValueOnce({ error: { code: 'Canceled', message: 'closed' } });
    expect(await stripeCardPayments.pay(started, { kind: 'new' })).toEqual({ status: 'cancelled' });
    stripe().confirmPayment!.mockResolvedValueOnce({ error: { code: 'Failed', message: 'declined', localizedMessage: 'Your card was declined.' } });
    expect(await stripeCardPayments.pay(started, { kind: 'saved', paymentMethodId: 'pm_saved' })).toEqual({ status: 'failed', message: 'Your card was declined.' });
  });
});

describe('the 3-D Secure return link', () => {
  it('hands the bank’s link to Stripe’s SDK and goes on', async () => {
    const spy = jest.spyOn(Linking, 'useURL').mockReturnValue('ca.northline.app://stripe-redirect?payment_intent=pi_1');
    const { view } = await start({ welcomed: true, url: '/stripe-redirect' });
    const sdk = jest.requireMock('@stripe/stripe-react-native') as { handleURLCallback: jest.Mock };
    await waitFor(() => expect(sdk.handleURLCallback).toHaveBeenCalledWith('ca.northline.app://stripe-redirect?payment_intent=pi_1'));
    await waitFor(() => expect(view.getPathname()).toBe('/cart'));
    spy.mockRestore();
  });
});

describe('B7–B9 the order: confirmed, tracking, delivered', () => {
  it('tracks the order: arriving, the courier, the PIN, where the run is; read again every 15 s', async () => {
    const { view } = await withOrder('picked_up', '/orders/ord-1001/track');
    expect(await screen.findByText('Order NL-1001')).toBeTruthy();
    expect(screen.getByText(/^Arriving 6:52 p\.m\.$/)).toBeTruthy();
    expect(screen.getByText('Robin · Northline courier')).toBeTruthy();
    expect(screen.getByText('Drop-off PIN 4827')).toBeTruthy();
    expect(screen.getByLabelText("Delivery map: You're next")).toBeTruthy();
    expect(screen.getByText('On the way')).toBeTruthy();
    expect(screen.getByLabelText('Courier picks up, now')).toBeTruthy();
    expect(TRACK_POLL_MS).toBe(15_000);
    expect(view.getPathname()).toBe('/orders/ord-1001/track');
  });

  it('delivered: the proof, when shops are paid, All good releases them; Something’s wrong opens the report', async () => {
    const { server } = await withOrder('delivered', '/orders/ord-1001/delivered');
    expect(await screen.findByText(/^Delivered at /)).toBeTruthy();
    expect(screen.getByText(/^proof-of-delivery photo · .* · at your door$/)).toBeTruthy();
    expect(screen.getByText(/^Everything there\? Shops are paid when you confirm — or automatically on /)).toBeTruthy();
    fireEvent.press(screen.getByTestId('delivered-confirm'));
    expect(await screen.findByText('Thanks. 2 shops paid.')).toBeTruthy();
    expect(server.calls.some((c) => c.method === 'POST' && c.path === '/me/orders/ord-1001/confirm')).toBe(true);
    screen.unmount();
    setServices(null);
    const again = await withOrder('delivered', '/orders/ord-1001/delivered');
    fireEvent.press(await screen.findByTestId('delivered-problem'));
    await waitFor(() => expect(again.view.getPathname()).toBe('/problem/order/ord-1001'));
  });

  it('opens from S-102’s order links (`/orders/<id>` → tracking)', async () => {
    const link = parseDeepLink('ca.northline.app://orders/ord-1001', ['example.test']);
    expect(link).not.toBeNull();
    const { view } = await withOrder('picked_up', routeOf(link!));
    await waitFor(() => expect(view.getPathname()).toBe('/orders/ord-1001/track'));
    expect(await screen.findByText('Robin · Northline courier')).toBeTruthy();
  });

  it('not delivered yet, unknown orders and guests', async () => {
    await withOrder('packing', '/orders/ord-1001/delivered');
    expect(await screen.findByText('This order hasn’t been delivered yet.')).toBeTruthy();
    screen.unmount();
    setServices(null);
    await start({ signedIn: true, store: saved(), url: '/orders/nope/confirmed' });
    expect(await screen.findByText('We couldn’t find this order.')).toBeTruthy();
    screen.unmount();
    setServices(null);
    await start({ welcomed: true, url: '/orders/ord-1001/confirmed' });
    expect(await screen.findByText('Sign in to see your order.')).toBeTruthy();
  });
});

describe('B10 Report a problem', () => {
  it('checks the choices, then opens a case: in review, the seller’s payout paused, the steps', async () => {
    const { view } = await withOrder('delivered', '/problem/order/ord-1001');
    expect(await screen.findByText(/^Pick what went wrong\./)).toBeTruthy();
    fireEvent.press(screen.getByTestId('refund-submit'));
    expect(await screen.findByText('Pick at least one item.')).toBeTruthy();
    expect(screen.getByText('Pick what went wrong.')).toBeTruthy();
    fireEvent.press(screen.getByRole('checkbox', { name: 'Kale & chard mix, $4.46' }));
    fireEvent.press(screen.getByRole('radio', { name: 'Damaged' }));
    fireEvent.changeText(screen.getByTestId('refund-note'), 'Wilted.');
    expect(screen.getByRole('button', { name: 'Request $4.46 refund' })).toBeTruthy();
    fireEvent.press(screen.getByTestId('refund-submit'));
    expect(await screen.findByText('Case RF-2201 · in review')).toBeTruthy();
    expect(screen.getByText('Request received · $4.46')).toBeTruthy();
    expect(screen.getByText('The seller’s payout for these items is paused while we look.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Back to orders' }));
    await waitFor(() => expect(view.getPathname()).toBe('/orders'));
  });

  it('says when there is nothing to report yet', async () => {
    await withOrder('packing', '/problem/order/ord-1001');
    expect(await screen.findByText('You can report a problem once it’s delivered.')).toBeTruthy();
  });
});

describe('links from the api', () => {
  it('maps the consumer web’s paths to the app’s routes', () => {
    expect(appRoute('/orders/ord-1')).toBe('/orders/ord-1/track');
    expect(appRoute('/account/problem/order/ord-1')).toBe('/problem/order/ord-1');
    expect(appRoute('/providers/x/book?step=done&booking=bk-1')).toBe('/bookings/bk-1/booked');
    expect(appRoute('/quotes/q-1')).toBe('/quotes/q-1');
    expect(appRoute('/quotes/requests/r-1')).toBeNull();
    expect(appRoute('/food/orders/f-1')).toBeNull();
  });
});


describe('mobile gaps part 2: promo codes, points and the courier’s tip', () => {
  it('checkout: the code’s rule in words, then the code, points and a tip in the sums, sent with the payment', async () => {
    const { view, server } = await signedInWithCart({ url: '/checkout' });
    expect(await screen.findByText('$23.35')).toBeTruthy();
    // $19.25 of items: under the code's minimum spend — the api's words at the field, no error screen
    fireEvent.changeText(screen.getByTestId('promo-code'), 'welcome5');
    fireEvent.press(screen.getByTestId('promo-apply'));
    expect(await screen.findByText('Spend at least $20.00 to use this code.')).toBeTruthy();
    expect(screen.queryByText('Try again')).toBeNull();
    fireEvent.changeText(screen.getByTestId('promo-code'), 'save10');
    fireEvent.press(screen.getByTestId('promo-apply'));
    expect(await screen.findByText('SAVE10 applied: $1.93 off')).toBeTruthy();
    expect(screen.getByText('Promo code SAVE10')).toBeTruthy();
    expect(screen.getByText('−$1.93')).toBeTruthy();
    // tax on what's left after the code: 5 % of $17.32 + $2.99
    expect(await screen.findByText('$1.02')).toBeTruthy();
    fireEvent.press(screen.getByTestId('use-points'));
    expect(await screen.findByText('$10.66 paid with points')).toBeTruthy();
    expect(screen.getByText('Use my points (1200 points available)')).toBeTruthy();
    fireEvent.press(screen.getByTestId('tip-amount-400'));
    expect(await screen.findByText('Courier tip')).toBeTruthy();
    expect(screen.getByText('100 % of the tip goes to your courier. Tips aren’t taxed.')).toBeTruthy();
    expect(await screen.findByText('$14.67')).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId('checkout-continue').props.accessibilityState).toMatchObject({ disabled: false }));
    fireEvent.press(screen.getByTestId('checkout-continue'));
    await waitFor(() => expect(view.getPathname()).toBe('/pay'));
    expect(view.getSearchParams()).toMatchObject({ promo: 'SAVE10', points: '1', tip: 'amount:400' });
    fireEvent.press(await screen.findByRole('button', { name: 'Pay $14.67' }));
    fireEvent.press(await screen.findByTestId('bank-approve'));
    await waitFor(() => expect(view.getPathname()).toBe('/orders/ord-48213/confirmed'));
    const started = server.calls.find((c) => c.method === 'POST' && c.path === '/me/checkouts');
    expect(started?.body).toMatchObject({ promoCode: 'SAVE10', usePoints: true, tip: { kind: 'amount', value: 400 } });
    expect(server.shop.tips.get('ord-48213')?.[0]).toMatchObject({ amountCents: 400, source: 'checkout' });
  });

  it('without a code, points or tip the checkout body is as before', async () => {
    const { server } = await signedInWithCart({ url: '/checkout' });
    expect(await screen.findByText('$23.35')).toBeTruthy();
    const quote = server.calls.find((c) => c.path === '/me/checkout/quote');
    expect(Object.keys(quote?.body ?? {}).sort()).toEqual(['address', 'kind', 'substitution', 'windowId']);
  });

  it('delivered: tips the courier afterwards and reviews each shop — stars first, contact details masked, changeable for a day', async () => {
    const { server } = await withOrder('delivered', '/orders/ord-1001/delivered');
    expect(await screen.findByText('Tip Robin')).toBeTruthy();
    fireEvent.press(screen.getByTestId('tip-percent-15'));
    fireEvent.press(screen.getByTestId('tip-send'));
    expect(await screen.findByText('Thanks — $2.89 goes to your courier.')).toBeTruthy();
    expect(server.shop.tips.get('ord-1001')).toEqual([expect.objectContaining({ amountCents: 289, source: 'after_delivery', state: 'allocated' })]);
    expect(server.calls.filter((c) => c.method === 'POST' && c.path === '/me/orders/ord-1001/tips/tip-ord-1001-1/confirm')).toHaveLength(1);

    expect(await screen.findByText('How was Maple Lane Bakery?')).toBeTruthy();
    expect(screen.getByText('How was Leafy Lane Greens?')).toBeTruthy();
    const bakery = within(screen.getByTestId('review-form-m-bakery'));
    fireEvent.press(bakery.getByTestId('submit-review'));
    expect(await bakery.findByText('Choose from 1 to 5 stars.')).toBeTruthy();
    fireEvent.press(bakery.getByTestId('star-4'));
    fireEvent.press(bakery.getByRole('button', { name: 'Well packed' }));
    fireEvent.changeText(bakery.getByTestId('field-review'), 'Great loaf, call me at 403 555 0199 anytime');
    fireEvent.press(bakery.getByTestId('submit-review'));
    expect(await screen.findByText('Your review of Maple Lane Bakery: ★★★★')).toBeTruthy();
    expect(screen.getByText('Some words were hidden: reviews don’t show contact details or swearing.')).toBeTruthy();
    expect(screen.getByText('Great loaf, call me at **** anytime')).toBeTruthy();
    expect(server.calls.find((c) => c.method === 'POST' && c.path === '/me/reviews')?.body).toMatchObject({ kind: 'order', id: 'ord-1001', merchantId: 'm-bakery', rating: 4, tags: ['well_packed'] });
    fireEvent.press(screen.getByTestId('review-edit'));
    fireEvent.press(within(screen.getByTestId('review-form-m-bakery')).getByTestId('star-5'));
    fireEvent.press(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Your review of Maple Lane Bakery: ★★★★★')).toBeTruthy();
    expect(server.calls.some((c) => c.method === 'PATCH' && c.path === '/me/reviews/rev-1')).toBe(true);
  });

  it('nothing to tip or review before the delivery', async () => {
    const started = await withOrder('packing', '/orders/ord-1001/delivered');
    expect(await screen.findByText('This order hasn’t been delivered yet.')).toBeTruthy();
    expect(screen.queryByTestId('courier-tip')).toBeNull();
    expect(screen.queryByTestId('review-panel')).toBeNull();
    expect(started.server.calls.some((c) => c.path === '/me/reviews/order/ord-1001')).toBe(false);
  });

  it('is in French on a French phone', async () => {
    const { getLocales } = jest.requireMock('expo-localization') as { getLocales: jest.Mock };
    getLocales.mockReturnValueOnce([{ languageTag: 'fr-CA' }]);
    await withOrder('delivered', '/orders/ord-1001/delivered');
    expect(await screen.findByText('Laisser un pourboire à Robin')).toBeTruthy();
    expect(await screen.findByText('Comment s’est passé Maple Lane Bakery?')).toBeTruthy();
    expect(screen.getAllByRole('button', { name: 'Publier l’avis' })).toHaveLength(2);
  });
});
