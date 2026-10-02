import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Linking from 'expo-linking';
import { act, fireEvent, screen, waitFor, within } from 'expo-router/testing-library';
import { Share } from 'react-native';

import type { PushPermission } from '@northline/mobile-kit';

import { setCardSetup, type CardSetupCollector } from '../src/account/cardSetup';
import { setPhonePush } from '../src/account/phonePush';
import { targetOf } from '../src/account/routes';
import type { ActivityItem } from '../src/api/account';
import { FIXTURE_TOTP } from '../src/fixtures/auth';
import { FIXTURE_PROOF } from '../src/fixtures/shop';
import { setServices } from '../src/services';
import { setCardPayments, type CardPayments } from '../src/shop/payments';
import { start, type StartOptions } from './support';

jest.mock('@stripe/stripe-react-native', () => ({}));

afterEach(async () => {
  setServices(null);
  setCardPayments(null);
  setCardSetup(null);
  setPhonePush(null);
  jest.restoreAllMocks();
  await AsyncStorage.clear();
});

const netinfo = () => jest.requireMock('@react-native-community/netinfo') as { __emit: (s: object) => void };
const french = () => (jest.requireMock('expo-localization') as { getLocales: jest.Mock }).getLocales.mockReturnValueOnce([{ languageTag: 'fr-CA' }]);

type Wrap = (f: typeof fetch) => typeof fetch;
interface Sent { method: string; url: string; body: Record<string, unknown>; headers: Record<string, string> }
/** Records every request (method, url, JSON body, headers) on its way to the fixture backend. */
function recorder(): { sent: Sent[]; wrap: Wrap } {
  const sent: Sent[] = [];
  return {
    sent,
    wrap: (f) =>
      (async (input: RequestInfo | URL, init: RequestInit = {}) => {
        const headers = Object.fromEntries(Object.entries((init.headers ?? {}) as Record<string, string>).map(([k, v]) => [k.toLowerCase(), v]));
        sent.push({ method: (init.method ?? 'GET').toUpperCase(), url: String(input), body: typeof init.body === 'string' && init.body.startsWith('{') ? JSON.parse(init.body) : {}, headers });
        return f(input, init);
      }) as typeof fetch,
  };
}
/** Answers `match`ing requests with `answer` (status, body) while `when()` holds; everything else goes on. */
const intercept =
  (match: (url: string, init: RequestInit) => boolean, answer: () => [number, unknown], when: () => boolean = () => true): Wrap =>
  (f) =>
    (async (input: RequestInfo | URL, init: RequestInit = {}) => {
      if (when() && match(String(input), init)) {
        const [status, body] = answer();
        return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
      }
      return f(input, init);
    }) as typeof fetch;
const signedIn = (url: string, more: Partial<StartOptions> = {}) => start({ signedIn: true, url, ...more });

/** The loading → error → Try again → loaded cycle every screen has. */
async function failsThenRecovers(url: string, path: RegExp, loaded: string | RegExp) {
  let fail = true;
  await signedIn(url, { wrap: intercept((u) => path.test(u), () => [503, { detail: 'down' }], () => fail) });
  expect(screen.getAllByTestId('loading').length).toBeGreaterThan(0);
  expect(await screen.findByText('Northline is having trouble right now. Try again in a moment.', {}, { timeout: 8000 })).toBeTruthy();
  fail = false;
  fireEvent.press(screen.getAllByRole('button', { name: 'Try again' })[0]!);
  expect(await screen.findByText(loaded)).toBeTruthy();
}

describe('D1 Orders & bookings', () => {
  it('asks a guest to sign in', async () => {
    await start({ welcomed: true, url: '/orders' });
    expect(await screen.findByText('Sign in to see your orders, bookings and quotes.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeTruthy();
  });

  it('lists active orders, bookings and quotes, then past ones and refunds', async () => {
    const { server } = await signedIn('/orders');
    expect(await screen.findByText('Grocery run · 2 shops')).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'Active · 3' }).props.accessibilityState).toMatchObject({ checked: true });
    expect(screen.getByText('Deep clean')).toBeTruthy();
    expect(screen.getByText('Mocktail bar · 40 guests')).toBeTruthy();
    expect(screen.getByText('On the way')).toBeTruthy();
    expect(screen.getByText('Quote ready')).toBeTruthy();
    expect(screen.getByText('$23.49')).toBeTruthy();
    expect(screen.queryByText('Window cleaning')).toBeNull();
    fireEvent.press(screen.getByRole('radio', { name: 'Past' }));
    expect(await screen.findByText('Window cleaning')).toBeTruthy();
    expect(screen.getByText('Done')).toBeTruthy();
    fireEvent.press(screen.getByRole('radio', { name: 'Refunds' }));
    expect(await screen.findByText('Case RF-2201')).toBeTruthy();
    expect(screen.getByText('Delivery · 1 shop')).toBeTruthy();
    expect(server.calls.find((c) => c.path === '/me/activity')).toMatchObject({ signed: true });
  });

  it('opens each row on its journey’s screen', async () => {
    const { view } = await signedIn('/orders');
    fireEvent.press(await screen.findByTestId('activity-req-2988'));
    await waitFor(() => expect(view.getPathname()).toBe('/quotes/q-2988'));
    const at = (id: string) => targetOf({ id, kind: 'order', status: 'on_the_way', action: 'track', active: true, with: [], shops: 1, items: 1, when: '', amountCents: 0, title: '', tone: 'accent' } as ActivityItem);
    expect(at('ord-1')).toEqual({ route: '/orders/ord-1/track' });
    const booking = (status: string, action: ActivityItem['action'], href: string | null = null) =>
      targetOf({ id: 'bk-1', kind: 'booking', status, action, href, active: true, with: [], shops: 1, items: 1, when: '', amountCents: 0, title: '', tone: 'neutral' });
    expect(booking('escrow', 'details')).toEqual({ route: '/bookings/bk-1/eta' });
    expect(booking('completed', 'details')).toEqual({ route: '/bookings/bk-1/sign-off' });
    expect(booking('done', 'rebook', '/providers/tidy-nook')).toEqual({ route: '/providers/tidy-nook' });
    expect(booking('completed', 'report')).toEqual({ route: '/problem/booking/bk-1' });
    expect(targetOf({ id: 'f1', kind: 'food', status: 'cooking', action: 'track', active: true, with: [], shops: 1, items: 1, when: '', amountCents: 0, title: '', tone: 'accent' })).toEqual({
      site: expect.stringMatching(/\/food\/orders\/f1$/),
    });
    expect(targetOf({ id: 'r1', kind: 'quote', status: 'quote_ready', action: 'view_quote', href: '/quotes/requests/r1', active: true, with: [], shops: 2, items: 2, when: '', amountCents: 0, title: '', tone: 'accent-2' })).toEqual({
      site: expect.stringMatching(/\/quotes\/requests\/r1$/),
    });
  });

  it('opens an order’s tracking and a case', async () => {
    const { view } = await signedIn('/orders');
    fireEvent.press(await screen.findByTestId('activity-ord-1001'));
    await waitFor(() => expect(view.getPathname()).toBe('/orders/ord-1001/track'));
    view.unmount();
    setServices(null);
    const again = await signedIn('/orders');
    fireEvent.press(await screen.findByRole('radio', { name: 'Refunds' }));
    fireEvent.press(await screen.findByTestId('activity-ord-0990'));
    await waitFor(() => expect(again.view.getPathname()).toBe('/cases/RF-2201'));
  });

  it('empty: one line and a way to start shopping', async () => {
    const { server, services } = await signedIn('/orders');
    server.account.activity = [];
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText('Nothing on the go right now.')).toBeTruthy();
    fireEvent.press(screen.getByRole('radio', { name: 'Refunds' }));
    expect(await screen.findByText(/^No refunds or cases\./)).toBeTruthy();
  });

  it('loading, error and Try again', async () => {
    await failsThenRecovers('/orders', /\/me\/activity$/, 'Grocery run · 2 shops');
  }, 15000);

  it('offline: the banner, and the list stays', async () => {
    await signedIn('/orders');
    await screen.findByText('Deep clean');
    act(() => netinfo().__emit({ isConnected: false, isInternetReachable: false }));
    expect(await screen.findByText("You're offline. Northline will catch up when you're back online.")).toBeTruthy();
    expect(screen.getByText('Deep clean')).toBeTruthy();
    act(() => netinfo().__emit({ isConnected: true, isInternetReachable: true }));
  });

  it('is in French', async () => {
    french();
    await signedIn('/orders');
    expect(await screen.findByText('Commandes et réservations')).toBeTruthy();
    expect(await screen.findByText('Tournée d’épicerie · 2 commerces')).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'En cours · 3' })).toBeTruthy();
    expect(screen.getByText('23,49 $')).toBeTruthy();
  });
});

describe('D2 Quote received', () => {
  it('shows the itemized quote: scope, every line, tax, total, what could change, terms, the escrow promise', async () => {
    await signedIn('/quotes/q-2988');
    expect(await screen.findByText('Mocktail bar · 40 guests')).toBeTruthy();
    expect(screen.getByText('Quote QT-2988')).toBeTruthy();
    expect(screen.getByText('Valid 70 h')).toBeTruthy();
    expect(screen.getByText(/^Quote QT-2988 from Copper & Soda · Master · ★ 4\.9 · Licensed server/)).toBeTruthy();
    expect(screen.getByText('Scope of work')).toBeTruthy();
    expect(screen.getByText('Bartender × 2 · 4 h')).toBeTruthy();
    expect(screen.getByText('Labour · Certified · $45/h each')).toBeTruthy();
    expect(screen.getByText(/^[-−]\$5\.48$/)).toBeTruthy();
    expect(screen.getByText('Tax 5%')).toBeTruthy();
    expect(screen.getByText('$30.48')).toBeTruthy();
    expect(screen.getByText('$640.00')).toBeTruthy();
    expect(screen.getByText('What could change the price')).toBeTruthy();
    expect(screen.getByText('Protected.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Accept · pay $640.00 to escrow' })).toBeTruthy();
  });

  it('accepts: where the job is, then the hold with one Idempotency-Key, then the booking', async () => {
    const rec = recorder();
    const { server } = await signedIn('/quotes/q-2988', { wrap: rec.wrap });
    fireEvent.press(await screen.findByTestId('quote-accept'));
    expect(await screen.findByText('Where is the job?')).toBeTruthy();
    fireEvent.press(screen.getByTestId('quote-accept-confirm'));
    expect(await screen.findByText('Enter the street address.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('visit-address'), '12 Sample Street, Sampleville');
    fireEvent.changeText(screen.getByTestId('visit-access'), 'Side door');
    fireEvent.press(screen.getByTestId('quote-accept-confirm'));
    expect(await screen.findByText('Accepted. $640.00 held · booking BK-8001 created · Copper & Soda notified.')).toBeTruthy();
    const accept = rec.sent.find((s) => s.url.endsWith('/me/quotes/q-2988/accept'))!;
    expect(accept.body).toEqual({ addressLine: '12 Sample Street, Sampleville', accessNote: 'Side door' });
    expect(accept.headers['idempotency-key']).toMatch(/.{16,}/);
    expect(rec.sent.find((s) => s.url.endsWith('/accept/confirm'))!.headers['idempotency-key']).toMatch(/.{16,}/);
    expect(server.account.quotes.get('q-2988')!.state).toBe('accepted');
  });

  it('asks for the authenticator code when the api wants a step-up, and sends the proof', async () => {
    const rec = recorder();
    const { server } = await signedIn('/quotes/q-2988', { wrap: rec.wrap });
    server.account.stepUp = 'required';
    fireEvent.press(await screen.findByTestId('quote-accept'));
    fireEvent.changeText(await screen.findByTestId('visit-address'), '12 Sample Street');
    fireEvent.press(screen.getByTestId('quote-accept-confirm'));
    expect(await screen.findByText('Confirm it’s you')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('step-up-code'), FIXTURE_TOTP);
    fireEvent.press(screen.getByTestId('step-up-confirm'));
    expect(await screen.findByText(/^Accepted\. \$640\.00 held/)).toBeTruthy();
    const accepts = rec.sent.filter((s) => s.url.endsWith('/accept'));
    expect(accepts.map((a) => a.headers['x-step-up'] ?? '')).toEqual(['', FIXTURE_PROOF]);
    expect(accepts[0]!.headers['idempotency-key']).toBe(accepts[1]!.headers['idempotency-key']);
  });

  it('pays with Stripe when the api runs it: the saved card authorizes the hold', async () => {
    const pay = jest.fn<ReturnType<CardPayments['pay']>, Parameters<CardPayments['pay']>>(async () => ({ status: 'paid' }));
    setCardPayments({ pay });
    const { server } = await signedIn('/quotes/q-2988');
    server.shop.provider = 'stripe';
    fireEvent.press(await screen.findByTestId('quote-accept'));
    fireEvent.changeText(await screen.findByTestId('visit-address'), '12 Sample Street');
    fireEvent.press(screen.getByTestId('quote-accept-confirm'));
    expect(await screen.findByText(/^Accepted\./)).toBeTruthy();
    expect(pay).toHaveBeenCalledWith(expect.objectContaining({ intents: [expect.objectContaining({ clientSecret: 'pi_fixture_quote_secret_x' })] }), { kind: 'saved', paymentMethodId: 'pm_fixture_visa' });
  });

  it('a declined card keeps the quote open with the reason', async () => {
    setCardPayments({ pay: async () => ({ status: 'failed', message: 'Your card was declined.' }) });
    const { server } = await signedIn('/quotes/q-2988');
    server.shop.provider = 'stripe';
    fireEvent.press(await screen.findByTestId('quote-accept'));
    fireEvent.changeText(await screen.findByTestId('visit-address'), '12 Sample Street');
    fireEvent.press(screen.getByTestId('quote-accept-confirm'));
    expect(await screen.findByText("Your card wasn't accepted: Your card was declined.")).toBeTruthy();
    expect(server.account.quotes.get('q-2988')!.state).toBe('viewed');
  });

  it('declines, and says an expired quote is closed', async () => {
    const { server } = await signedIn('/quotes/q-2988');
    fireEvent.press(await screen.findByTestId('quote-decline'));
    expect(await screen.findByText('Declined. Copper & Soda has been told. Your other quotes stay open.')).toBeTruthy();
    server.account.quotes.set('q-2988', { state: 'sent', validUntil: Date.now() - 60_000 });
    setServices(null);
    const again = await signedIn('/quotes/q-2988');
    again.server.account.quotes.set('q-2988', { state: 'sent', validUntil: Date.now() - 60_000 });
    await act(async () => {
      await again.services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText('This quote expired. Ask Copper & Soda for an updated quote.')).toBeTruthy();
    expect(screen.queryByTestId('quote-accept')).toBeNull();
  });

  it('error: someone else’s or an unknown quote, and a guest', async () => {
    await signedIn('/quotes/nope');
    expect(await screen.findByText('We couldn’t find this quote.')).toBeTruthy();
    setServices(null);
    await start({ welcomed: true, url: '/quotes/q-2988' });
    expect(await screen.findByText('Sign in to see this quote.')).toBeTruthy();
  });

  it('loading, error and Try again', async () => {
    await failsThenRecovers('/quotes/q-2988', /\/me\/quotes\/q-2988\?/, 'Mocktail bar · 40 guests');
  }, 15000);

  it('is in French', async () => {
    french();
    await signedIn('/quotes/q-2988');
    expect(await screen.findByText('Portée des travaux')).toBeTruthy();
    expect(screen.getByText('640,00 $')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Accepter · verser 640,00 $ en fiducie' })).toBeTruthy();
  });
});

describe('D3 Profile (You)', () => {
  it('shows who you are, the wallet and every row with its value; rows open their screens', async () => {
    const { view } = await signedIn('/account');
    expect(await screen.findByText('Ada Example')).toBeTruthy();
    expect(screen.getByText(/^Reliability 4\.9 · member since May 2026/)).toBeTruthy();
    expect(await screen.findByText('1,240 pts · $12.40')).toBeTruthy();
    expect(screen.getByText('Standard')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Payment methods, Visa ··4471' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Security & sign-in, Authenticator + SMS' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Notifications, Quiet 10 p.m.–7 a.m.' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Favourite providers, 1' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Refunds & help, 1 case' })).toBeTruthy();
    fireEvent.press(screen.getByTestId('you-wallet'));
    await waitFor(() => expect(view.getPathname()).toBe('/wallet'));
  });

  it('opens Become a seller on the website', async () => {
    await signedIn('/account');
    fireEvent.press(await screen.findByTestId('row-sell'));
    expect(Linking.openURL).toHaveBeenCalledWith(expect.stringMatching(/\/sell$/));
  });

  it('switches the language here and on the account', async () => {
    const rec = recorder();
    await signedIn('/account', { wrap: rec.wrap });
    fireEvent.press(await screen.findByRole('radio', { name: 'Français' }));
    expect(await screen.findByText('Renseignements personnels')).toBeTruthy();
    await waitFor(() => expect(rec.sent.find((s) => s.method === 'PATCH' && s.url.endsWith('/me/preferences'))?.body).toEqual({ language: 'fr' }));
  });

  it('error: the values fail to load — Try again', async () => {
    let fail = true;
    await signedIn('/account', { wrap: intercept((u) => u.endsWith('/me/account-summary'), () => [503, {}], () => fail) });
    expect(await screen.findByText('Northline is having trouble right now. Try again in a moment.', {}, { timeout: 8000 })).toBeTruthy();
    fail = false;
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('1,240 pts · $12.40')).toBeTruthy();
  }, 15000);

  it('loading: skeletons until the profile arrives', async () => {
    await signedIn('/account');
    expect(screen.queryByText('Ada Example')).toBeNull();
    expect(await screen.findByText('Ada Example')).toBeTruthy();
  });
});

describe('D4 Security centre', () => {
  it('asks to confirm it’s you, then shows methods, devices and alerts; signs a device out', async () => {
    const rec = recorder();
    const { server } = await signedIn('/security', { wrap: rec.wrap });
    expect(await screen.findByText('Confirm it’s you')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('security-code'), '000000');
    fireEvent.press(screen.getByTestId('security-confirm-button'));
    expect(await screen.findByText('That code didn’t work. Check it and try again.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('security-code'), FIXTURE_TOTP);
    fireEvent.press(screen.getByTestId('security-confirm-button'));
    expect(await screen.findByText('Your account is well protected.')).toBeTruthy();
    expect(screen.getByText(/Authenticator app \+ SMS backup · 2 trusted devices\./)).toBeTruthy();
    expect(screen.getByText('SMS backup · ··0100')).toBeTruthy();
    expect(screen.getByText('This device · now')).toBeTruthy();
    expect(screen.getByText('2 days ago')).toBeTruthy();
    fireEvent.press(screen.getByTestId('revoke-ses-laptop'));
    await waitFor(() => expect(screen.queryByTestId('session-ses-laptop')).toBeNull());
    expect(server.account.security.sessions.map((s) => s.id)).toEqual(['ses-phone']);
    expect(rec.sent.filter((s) => s.url.includes('/api/auth/security')).every((s) => s.url.startsWith('http'))).toBe(true);
   }, 15000);

  it('sends people without an auth session here to the website', async () => {
    await signedIn('/security', { wrap: intercept((u) => u.endsWith('/api/auth/step-up/totp'), () => [401, { code: 'unauthenticated' }]) });
    fireEvent.changeText(await screen.findByTestId('security-code'), FIXTURE_TOTP);
    fireEvent.press(screen.getByTestId('security-confirm-button'));
    fireEvent.press(await screen.findByTestId('security-site'));
    expect(Linking.openURL).toHaveBeenCalledWith(expect.stringMatching(/\/account\?tab=security$/));
  });

  it('downloads my data through the share sheet', async () => {
    const share = jest.spyOn(Share, 'share').mockResolvedValue({ action: Share.sharedAction });
    await signedIn('/security');
    fireEvent.press(await screen.findByTestId('export'));
    expect(await screen.findByText('Your data is ready — save or send it from the share sheet.')).toBeTruthy();
    const message = JSON.parse(share.mock.calls[0]![0].message!) as { profile: { email: string } };
    expect(message.profile.email).toBe('ada@example.com');
  });

  it('signs out of all devices', async () => {
    const { server, view, services } = await signedIn('/security');
    server.account.security.confirmed = true;
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    fireEvent.press(await screen.findByTestId('sign-out-all'));
    await waitFor(() => expect(view.getPathname()).toBe('/home'));
    expect(server.account.security.sessions.map((s) => s.id)).toEqual(['ses-phone']);
    expect(server.auth.revoked).toEqual(['fixture-refresh-1']);
  });

  it('error and Try again; guests sign in', async () => {
    await failsThenRecovers('/security', /\/api\/auth\/security$/, 'Confirm it’s you');
    setServices(null);
    await start({ welcomed: true, url: '/security' });
    expect(await screen.findByText('Sign in to see your security settings.')).toBeTruthy();
  }, 20000);
});

describe('D5 Wallet & points', () => {
  it('shows the balance, the 8-week chart and payment methods; starts and cancels Plus', async () => {
    const rec = recorder();
    const { server } = await signedIn('/wallet', { wrap: rec.wrap });
    expect(await screen.findByText('Northline points')).toBeTruthy();
    expect(screen.getByTestId('points-balance')).toHaveTextContent('1,240');
    expect(screen.getByText('= $12.40 off anything')).toBeTruthy();
    expect(screen.getByTestId('points-chart').props.accessibilityLabel).toMatch(/^Points earned, last 8 weeks: 0 pts, 7 weeks ago, .* 180 pts, this week$/);
    expect(await screen.findByRole('button', { name: 'Payment methods, Visa ··4471' })).toBeTruthy();
    fireEvent.press(screen.getByTestId('plus-cta'));
    fireEvent.press(screen.getByTestId('plan-annual'));
    fireEvent.press(screen.getByTestId('plus-start'));
    expect(await screen.findByText('Northline Plus · active')).toBeTruthy();
    expect(rec.sent.find((s) => s.method === 'POST' && s.url.endsWith('/me/plus'))!.body).toEqual({ plan: 'annual' });
    expect(server.account.plus?.plan).toBe('annual');
    fireEvent.press(screen.getByTestId('plus-cta'));
    expect(await screen.findByText(/^Annual · renews .* · 2 members$/)).toBeTruthy();
    fireEvent.press(screen.getByTestId('plus-cancel'));
    expect(await screen.findByText('Try free')).toBeTruthy();
    expect(server.account.plus).toBeNull();
  });

  it('empty: no points yet', async () => {
    const { server, services } = await signedIn('/wallet');
    server.account.points = { balance: 0, weekly: [0, 0, 0, 0, 0, 0, 0, 0] };
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText('No points yet — they’re added after each order and job.')).toBeTruthy();
  });

  it('loading, error and Try again', async () => {
    await failsThenRecovers('/wallet', /\/me\/wallet$/, 'Northline points');
  }, 15000);

  it('is in French', async () => {
    french();
    await signedIn('/wallet');
    expect(await screen.findByText('Points Northline')).toBeTruthy();
    expect(screen.getByTestId('points-balance')).toHaveTextContent('1 240');
  });
});

describe('Payment methods', () => {
  it('adds a card with the api’s stand-in, makes another the default, removes one', async () => {
    const { server } = await signedIn('/account/payments');
    expect(await screen.findByText('Visa ··4471')).toBeTruthy();
    fireEvent.press(screen.getByTestId('card-add'));
    expect(await screen.findByText('Mastercard ··5454')).toBeTruthy();
    expect(screen.getByText('Card saved.')).toBeTruthy();
    fireEvent.press(screen.getByTestId('card-default-pm_fixture_visa'));
    await waitFor(() => expect(server.shop.cards.find((c) => c.isDefault)?.id).toBe('pm_fixture_visa'));
    await waitFor(() => expect(screen.getByTestId('card-remove-pm_fixture_1').props.accessibilityState).toMatchObject({ disabled: false }));
    fireEvent.press(screen.getByTestId('card-remove-pm_fixture_1'));
    await waitFor(() => expect(screen.queryByText('Mastercard ··5454')).toBeNull());
  }, 15000);

  it('with Stripe: PaymentSheet in setup mode, then the api keeps the card; a cancelled sheet saves nothing', async () => {
    const collect = jest.fn<ReturnType<CardSetupCollector['collect']>, Parameters<CardSetupCollector['collect']>>(async () => ({ status: 'cancelled' }));
    setCardSetup({ collect });
    const { server } = await signedIn('/account/payments');
    server.shop.provider = 'stripe';
    fireEvent.press(await screen.findByTestId('card-add'));
    await waitFor(() => expect(collect).toHaveBeenCalledWith(expect.objectContaining({ clientSecret: 'seti_fixture_1_secret_x', publishableKey: 'pk_test_fixture' })));
    expect(server.shop.cards).toHaveLength(1);
    collect.mockResolvedValueOnce({ status: 'confirmed' });
    fireEvent.press(screen.getByTestId('card-add'));
    expect(await screen.findByText('Mastercard ··5454')).toBeTruthy();
  });

  it('empty and error', async () => {
    const { server, services } = await signedIn('/account/payments');
    server.shop.cards.splice(0);
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText('No saved cards. Add one now, or when you pay.')).toBeTruthy();
    setServices(null);
    await failsThenRecovers('/account/payments', /\/me\/payment-methods$/, 'Visa ··4471');
  }, 15000);
});

describe('Personal details', () => {
  it('checks the rules with the api’s words, saves, and words the api’s refusals', async () => {
    const rec = recorder();
    const { server } = await signedIn('/account/profile', { wrap: rec.wrap });
    fireEvent.changeText(await screen.findByTestId('profile-first'), '');
    fireEvent.changeText(screen.getByTestId('profile-birthday'), '13/40');
    fireEvent.press(screen.getByTestId('profile-save'));
    expect(await screen.findByText('First name is required.')).toBeTruthy();
    expect(screen.getByText('Enter a birthday like 03/14 (month / day).')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('profile-first'), 'Ada');
    fireEvent.changeText(screen.getByTestId('profile-birthday'), '03/14');
    fireEvent.changeText(screen.getByTestId('profile-email'), 'taken@example.com');
    fireEvent.press(screen.getByTestId('profile-save'));
    expect(await screen.findByText('That email is already used by another account.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('profile-email'), 'ada.new@example.com');
    fireEvent.press(screen.getByTestId('pronouns-they'));
    fireEvent.press(screen.getByTestId('profile-save'));
    expect(await screen.findByText('Saved.')).toBeTruthy();
    expect(rec.sent.filter((s) => s.method === 'PATCH').at(-1)!.body).toEqual({ firstName: 'Ada', lastName: 'Example', email: 'ada.new@example.com', pronouns: 'they', birthday: '03-14' });
    expect(server.account.profile.email).toBe('ada.new@example.com');
  });

  it('asks to delete the account', async () => {
    const { server } = await signedIn('/account/profile');
    fireEvent.press(await screen.findByTestId('erasure-start'));
    expect(screen.getByText(/^We’ll close your account and erase your personal data within 30 days/)).toBeTruthy();
    fireEvent.press(screen.getByTestId('erasure-confirm'));
    expect(await screen.findByText(/^Deletion requested on .*\. Our team will confirm by email\.$/)).toBeTruthy();
    expect(server.account.profile.erasureRequestedAt).not.toBeNull();
  });

  it('is in French, and loads with Try again', async () => {
    french();
    await signedIn('/account/profile');
    expect(await screen.findByText('Prénom')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('profile-first'), '');
    fireEvent.press(screen.getByTestId('profile-save'));
    expect(await screen.findByText('Le prénom est obligatoire.')).toBeTruthy();
    setServices(null);
    await failsThenRecovers('/account/profile', /\/me\/profile$/, 'First name');
  }, 15000);
});

describe('Addresses & household', () => {
  it('empty, then adds one (the rules first), makes it the default, removes it; shows the household', async () => {
    const rec = recorder();
    const { server } = await signedIn('/account/addresses', { wrap: rec.wrap });
    expect(await screen.findByText('No saved addresses yet.')).toBeTruthy();
    expect(await screen.findByText('Ada Example (you)')).toBeTruthy();
    expect(screen.getByText('Kofi Example')).toBeTruthy();
    fireEvent.press(screen.getByTestId('address-add'));
    fireEvent.changeText(await screen.findByTestId('address-postal'), '12345');
    fireEvent.press(screen.getByTestId('address-save'));
    expect(await screen.findByText('Enter a Canadian postal code.')).toBeTruthy();
    expect(screen.getByText('Enter the street address.')).toBeTruthy();
    expect(screen.getByText('Choose a Canadian province or territory.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('address-label'), 'Home');
    fireEvent.changeText(screen.getByTestId('address-street'), '12 Sample Street');
    fireEvent.changeText(screen.getByTestId('address-city'), 'Sampleville');
    fireEvent.press(await screen.findByTestId('province-XA'));
    fireEvent.changeText(screen.getByTestId('address-postal'), 'a1a 1a1');
    fireEvent.press(screen.getByTestId('address-save'));
    expect(await screen.findByText('Home')).toBeTruthy();
    expect(rec.sent.find((s) => s.method === 'POST' && s.url.endsWith('/me/addresses'))!.body).toEqual({ label: 'Home', street: '12 Sample Street', city: 'Sampleville', province: 'XA', postal: 'A1A 1A1' });
    expect(screen.getByText('Default')).toBeTruthy();
    fireEvent.press(screen.getByTestId('address-remove-addr-1'));
    expect(await screen.findByText('No saved addresses yet.')).toBeTruthy();
    expect(server.shop.addresses).toEqual([]);
  });

  it('loading, error and Try again', async () => {
    await failsThenRecovers('/account/addresses', /\/me\/addresses$/, 'No saved addresses yet.');
  }, 15000);
});

describe('Notifications', () => {
  it('shows the matrix with security locked on, and saves only the changed cells', async () => {
    const rec = recorder();
    const { server } = await signedIn('/account/notifications', { wrap: rec.wrap });
    const email = await screen.findByTestId('cell-order_updates-email');
    expect(email.props.value).toBe(true);
    expect(screen.getByTestId('cell-security-push').props.disabled).toBe(true);
    fireEvent(email, 'valueChange', false);
    fireEvent(screen.getByTestId('cell-offers-push'), 'valueChange', false);
    fireEvent.press(screen.getByTestId('quiet-from-23:00'));
    fireEvent.press(screen.getByTestId('notif-lang-fr'));
    fireEvent.press(screen.getByTestId('marketing-none'));
    fireEvent.press(screen.getByTestId('notif-save'));
    expect(await screen.findByText('Saved · applies to all your devices')).toBeTruthy();
    expect(rec.sent.find((s) => s.method === 'PUT')!.body).toEqual({
      matrix: { order_updates: { email: false }, offers: { push: false } },
      quietOn: true,
      quietFrom: '23:00',
      quietTo: '07:00',
      language: 'fr',
      marketing: 'none',
    });
    expect(server.account.notifications.matrix.order_updates!.email).toBe(false);
  });

  it('a failed save keeps the changes on screen', async () => {
    await signedIn('/account/notifications', { wrap: intercept((u, i) => u.endsWith('/me/notifications') && i.method === 'PUT', () => [503, {}]) });
    fireEvent(await screen.findByTestId('quiet-on'), 'valueChange', false);
    fireEvent.press(screen.getByTestId('notif-save'));
    expect(await screen.findByText('Northline is having trouble right now. Try again in a moment.')).toBeTruthy();
    expect(screen.getByTestId('quiet-on').props.value).toBe(false);
  });

  it('turns push on for this phone (S-102), or sends a refusal to the settings', async () => {
    let permission: PushPermission = 'undetermined';
    const enable = jest.fn(async (): Promise<PushPermission> => (permission = 'granted'));
    const openSettings = jest.fn(async () => undefined);
    setPhonePush({ registration: { enable }, permission: async () => permission, openSettings });
    await signedIn('/account/notifications');
    fireEvent.press(await screen.findByTestId('push-enable'));
    expect(await screen.findByText('Push notifications come to this phone.')).toBeTruthy();
    expect(enable).toHaveBeenCalledTimes(1);
    setServices(null);
    permission = 'denied';
    await signedIn('/account/notifications');
    fireEvent.press(await screen.findByTestId('push-settings'));
    expect(openSettings).toHaveBeenCalled();
  });

  it('has no phone row until the app installs push; loading, error and Try again', async () => {
    await failsThenRecovers('/account/notifications', /\/me\/notifications$/, 'Booking reminders & ETA');
    expect(screen.queryByTestId('this-phone')).toBeNull();
  }, 15000);

  it('is in French', async () => {
    french();
    await signedIn('/account/notifications');
    expect(await screen.findByText('Rappels de réservation et heure d’arrivée')).toBeTruthy();
    expect(screen.getByTestId('quiet-from-22:00')).toHaveTextContent('22 h');
  });
});

describe('Dietary, accessibility & region', () => {
  it('saves only what changed', async () => {
    const rec = recorder();
    await signedIn('/account/preferences', { wrap: rec.wrap });
    fireEvent.press(await screen.findByTestId('province-XA'));
    expect(screen.queryByTestId('province-XC')).toBeNull(); // waitlist: not served
    fireEvent.press(screen.getByTestId('diet-halal'));
    fireEvent.press(screen.getByTestId('access-step_free'));
    fireEvent.changeText(screen.getByTestId('allergies'), 'Peanuts');
    fireEvent.press(screen.getByTestId('prefs-save'));
    expect(await screen.findByTestId('prefs-saved')).toBeTruthy();
    expect(rec.sent.find((s) => s.method === 'PATCH')!.body).toEqual({ province: 'XA', dietary: ['halal'], allergies: 'Peanuts', accessibility: ['step_free'] });
  });

  it('loading, error and Try again', async () => {
    await failsThenRecovers('/account/preferences', /\/me\/preferences$/, 'Dietary needs');
  }, 15000);
});

describe('Favourites', () => {
  it('opens a provider, removes one, and says when there are none', async () => {
    const { view } = await signedIn('/account/favourites');
    expect(await screen.findByText('Tidy Nook Cleaners')).toBeTruthy();
    expect(screen.getByText(/^Master · 2 visits · last /)).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Tidy Nook Cleaners' }));
    await waitFor(() => expect(view.getPathname()).toBe('/providers/tidy-nook-cleaners'));
    setServices(null);
    await signedIn('/account/favourites');
    fireEvent.press(await screen.findByTestId('fav-remove-m-cleaners'));
    expect(await screen.findByText('No favourites yet. Tap the heart on a provider to keep them here.')).toBeTruthy();
  });

  it('loading, error and Try again', async () => {
    await failsThenRecovers('/account/favourites', /\/me\/favourites$/, 'Tidy Nook Cleaners');
  }, 15000);
});

describe('Refunds & help, and one case', () => {
  it('lists the cases, opens one with its timeline and messages, and adds a message', async () => {
    const { view, server } = await signedIn('/account/help');
    expect(await screen.findByText('Case RF-2201')).toBeTruthy();
    expect(screen.getByText(/^Seller reviewing · 1[34] h left$/)).toBeTruthy();
    fireEvent.press(screen.getByTestId('case-RF-2201'));
    await waitFor(() => expect(view.getPathname()).toBe('/cases/RF-2201'));
    expect(await screen.findByText('Ribeye, AAA × 1')).toBeTruthy();
    expect(screen.getByText(/^Submitted/)).toBeTruthy();
    expect(screen.getByText(/Riverside Butcher has until/)).toBeTruthy();
    expect(screen.getByText('The steak arrived warm.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('case-note'), 'Photo is on the website.');
    fireEvent.press(screen.getByTestId('case-send'));
    expect(await screen.findByText('Photo is on the website.')).toBeTruthy();
    expect(server.account.cases[0]!.notes).toHaveLength(3);
  });

  it('opens from the deep link’s path; an unknown case and no cases', async () => {
    await signedIn('/cases/RF-9999');
    expect(await screen.findByText('We couldn’t find this case.')).toBeTruthy();
    setServices(null);
    const { server, services } = await signedIn('/account/help');
    server.account.cases = [];
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText(/^No cases\./)).toBeTruthy();
  });

  it('loading, error and Try again; French', async () => {
    await failsThenRecovers('/account/help', /\/me\/cases$/, 'Case RF-2201');
    setServices(null);
    french();
    await signedIn('/cases/RF-2201');
    expect(await screen.findByText('Dossier RF-2201')).toBeTruthy();
    expect(await within(screen.getByTestId('case')).findByText(/^Envoyée/)).toBeTruthy();
  }, 15000);
});
