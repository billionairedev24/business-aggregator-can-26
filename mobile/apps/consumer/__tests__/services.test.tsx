import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Linking from 'expo-linking';
import { act, fireEvent, screen, waitFor } from 'expo-router/testing-library';

import { FIXTURE_TOTP } from '../src/fixtures/auth';
import { BUSINESS_ZONE, calendarDays, PROVIDERS } from '../src/fixtures/services';
import { translator } from '../src/i18n';
import { checkDetails } from '../src/services/BookService';
import { clearDraft, parseVehicle, updateDraft } from '../src/services/draft';
import { ago } from '../src/services/Notifications';
import { setBookingPayments, type BookingPayments } from '../src/services/payments';
import { applyFilters, nextSlot } from '../src/services/Providers';
import { setServices } from '../src/services';
import { start, type StartOptions } from './support';

afterEach(async () => {
  setServices(null);
  setBookingPayments(null);
  clearDraft();
  jest.clearAllMocks();
  await AsyncStorage.clear();
});

const SAVED = { 'nl.location': JSON.stringify({ label: '1204 Example Ave, Sampleville', street: '1204 Example Ave', city: 'Sampleville', lat: 45.11, lng: -75.21, marketId: 'mkt-sampleville', zone: 'Old Town' }) };
const en = translator('en');
const netinfo = () => jest.requireMock('@react-native-community/netinfo') as { __emit: (s: object) => void };

/** Answers `path` (after /api/v1, without the query) with `mode`; everything else goes to the fixture backend. */
function trouble(path: string, mode: 'hang' | 'fail') {
  return (f: typeof fetch): typeof fetch =>
    (async (input: RequestInfo | URL, init?: RequestInit) => {
      const p = new URL(String(input), 'http://x.invalid').pathname.replace(/^.*\/api\/v1/, '');
      if (p === path && (init?.method ?? 'GET') === 'GET') {
        if (mode === 'hang') return new Promise<Response>(() => undefined);
        return new Response(JSON.stringify({ code: 'not_found', detail: 'That page is gone.' }), { status: 404, headers: { 'content-type': 'application/json' } });
      }
      return f(input, init);
    }) as typeof fetch;
}

/** The first free slot of the fixture calendar (tomorrow, 7:00 in the business's zone). */
const firstSlot = () => calendarDays(Date.now(), 'svc-brakes', new Set())[0]!.slots[0]!.startsAt;

interface Case {
  screen: string;
  url: string;
  path: string;
  testID: string;
  signedIn?: boolean;
  setup?: (s: Awaited<ReturnType<typeof start>>['server']) => void;
  draft?: () => void;
}

const CASES: Case[] = [
  { screen: 'C1 services', url: '/services', path: '/public/services', testID: 'services' },
  { screen: 'C2 providers', url: '/services/mobile-mechanic', path: '/public/services/mobile-mechanic/providers', testID: 'providers' },
  { screen: 'C3 provider', url: '/providers/prairie-wrench', path: '/public/providers/prairie-wrench', testID: 'provider' },
  { screen: 'C4 book_service', url: '/book/prairie-wrench/service', path: '/public/providers/prairie-wrench', testID: 'book-service' },
  { screen: 'C5 book_slot', url: '/book/prairie-wrench/time', path: '/public/providers/prairie-wrench/slots', testID: 'book-time', draft: () => updateDraft('prairie-wrench', { serviceId: 'svc-brakes' }) },
  {
    screen: 'C6 book_review',
    url: '/book/prairie-wrench/review',
    path: '/public/providers/prairie-wrench',
    testID: 'book-review',
    signedIn: true,
    draft: () => updateDraft('prairie-wrench', { serviceId: 'svc-brakes', hold: { holdId: 'hold-x', bookingId: 'b-x', startsAt: firstSlot(), endsAt: firstSlot(), expiresAt: firstSlot() } }),
  },
  { screen: 'C7 booked', url: '/bookings/01J9BOOKING/booked', path: '/me/bookings/01J9BOOKING', testID: 'booked', signedIn: true },
  { screen: 'C8 notifications', url: '/notifications', path: '/me/activity', testID: 'notifications', signedIn: true },
  { screen: 'C9 eta', url: '/bookings/01J9BOOKINGROUTE/eta', path: '/me/bookings/01J9BOOKINGROUTE', testID: 'eta', signedIn: true },
  { screen: 'C10 signoff', url: '/bookings/01J9BOOKINGDONE/sign-off', path: '/me/bookings/01J9BOOKINGDONE', testID: 'sign-off', signedIn: true },
  { screen: 'C11 review', url: '/bookings/01J9BOOKINGPAID/review', path: '/me/bookings/01J9BOOKINGPAID', testID: 'review', signedIn: true },
];

describe.each(CASES)('$screen: every state', (c) => {
  const open = (extra: Partial<StartOptions> = {}) => {
    c.draft?.();
    return start({ welcomed: true, signedIn: c.signedIn, url: c.url, store: SAVED, ...extra });
  };

  it('shows a skeleton while loading', async () => {
    await open({ wrap: trouble(c.path, 'hang') });
    expect(await screen.findByTestId(c.testID)).toBeTruthy();
    expect(await screen.findByTestId('loading')).toBeTruthy();
    expect(screen.getByLabelText('Loading…')).toBeTruthy();
  });

  it('says what went wrong, with Try again', async () => {
    await open({ wrap: trouble(c.path, 'fail') });
    expect(await screen.findByText('That page is gone.')).toBeTruthy();
    expect(screen.getByTestId('error').props.accessibilityRole).toBe('alert');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
  });

  it('keeps its content under the offline banner', async () => {
    await open();
    expect(await screen.findByTestId(c.testID)).toBeTruthy();
    await waitFor(() => expect(screen.queryByTestId('loading')).toBeNull());
    act(() => netinfo().__emit({ isConnected: false, isInternetReachable: false }));
    expect(await screen.findByTestId('offline-banner')).toBeTruthy();
    expect(screen.queryByTestId('error')).toBeNull();
    act(() => netinfo().__emit({ isConnected: true, isInternetReachable: true }));
  });
});

describe('empty states', () => {
  it('C1: no live category yet', async () => {
    const { server } = await start({ welcomed: true, url: '/home' });
    server.services.empty = true;
    fireEvent.press(await screen.findByRole('tab', { name: 'Services' }));
    expect(await screen.findByText('No service categories are live yet.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Back to Home' })).toBeTruthy();
  });

  it('C2: nobody covers the area, and filters that match nobody', async () => {
    await start({ welcomed: true, url: '/services/plumber', store: SAVED });
    expect(await screen.findByText('No verified providers cover Sampleville for this service yet.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'See all services' }));
    expect(await screen.findByTestId('services')).toBeTruthy();
  });

  it('C3: a new provider without reviews', async () => {
    await start({ welcomed: true, url: '/providers/new-garage' });
    expect(await screen.findByText('No reviews yet.')).toBeTruthy();
    expect(screen.getByText('New on Northline')).toBeTruthy();
  });

  it('C5/C6: no service or no hold yet sends the person back a step', async () => {
    const { view } = await start({ welcomed: true, url: '/book/prairie-wrench/time' });
    expect(await screen.findByText('Choose a service first.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Choose a service' }));
    await waitFor(() => expect(view.getPathname()).toBe('/book/prairie-wrench/service'));
    setServices(null);
    await start({ signedIn: true, url: '/book/prairie-wrench/review' });
    expect(await screen.findByText('This time isn’t held for you any more.')).toBeTruthy();
  });

  it('C8: the Offers inbox is empty', async () => {
    await start({ signedIn: true, url: '/notifications' });
    fireEvent.press(await screen.findByTestId('inbox-offers'));
    expect(await screen.findByText('No offers right now.')).toBeTruthy();
  });
});

describe('C1 Browse services', () => {
  it('lists the groups, names the province from the region model, narrows by text and opens a category', async () => {
    const { view } = await start({ welcomed: true, url: '/services' });
    expect(await screen.findByRole('header', { name: 'Services' })).toBeTruthy();
    expect(await screen.findByText('4 categories live in Sample Province · every provider verified')).toBeTruthy();
    expect(screen.getByText('Popular: Mobile mechanic')).toBeTruthy();
    expect(screen.getByText('Auto')).toBeTruthy();
    expect(screen.getByText('Events & hospitality')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('services-search'), 'plumb');
    await waitFor(() => expect(screen.queryByText('Auto')).toBeNull());
    expect(screen.getByRole('button', { name: 'Plumber' })).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('services-search'), 'zzz');
    expect(await screen.findByText('No service matches “zzz”.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('services-search'), '');
    fireEvent.press(await screen.findByTestId('category-mobile-mechanic'));
    await waitFor(() => expect(view.getPathname()).toBe('/services/mobile-mechanic'));
  });

  it('is in French on a French phone', async () => {
    const { getLocales } = jest.requireMock('expo-localization') as { getLocales: jest.Mock };
    getLocales.mockReturnValue([{ languageTag: 'fr-CA' }]);
    try {
      const { server } = await start({ welcomed: true, url: '/services' });
      expect(await screen.findByText('4 catégories actives en Sample Province · chaque prestataire vérifié')).toBeTruthy();
      expect(screen.getByText('Mécanicien mobile')).toBeTruthy();
      expect(screen.getByText('Maison et métiers')).toBeTruthy();
      expect(server.calls.some((c) => c.path === '/public/services' && !c.signed)).toBe(true);
    } finally {
      getLocales.mockReturnValue([{ languageTag: 'en-CA' }]);
    }
  });
});

describe('C2 Providers', () => {
  it('lists who covers the saved address, filters as the design does, shows times in the business’s zone', async () => {
    const { server, view } = await start({ welcomed: true, url: '/services/mobile-mechanic', store: SAVED });
    expect(await screen.findByText('Mobile mechanic')).toBeTruthy();
    expect(await screen.findByText('4 providers come to Old Town · sorted by trust')).toBeTruthy();
    expect(screen.getByText('★ 4.9 (312) · 98% on time')).toBeTruthy();
    expect(screen.getAllByText('from $89').length).toBeGreaterThan(0);
    // 15:00 business time is 20:00 UTC: the card says "3:00 p.m." whatever the phone's zone
    expect(screen.getAllByText(/3:00 p\.m\./).length).toBeGreaterThan(0);
    const call = server.calls.find((c) => c.path === '/public/services/mobile-mechanic/providers');
    expect(call).toBeTruthy();
    fireEvent.press(screen.getByTestId('filter-master'));
    expect(await screen.findByText('2 providers come to Old Town · sorted by trust')).toBeTruthy();
    expect(screen.getByTestId('filter-master').props.accessibilityState).toMatchObject({ selected: true });
    fireEvent.press(screen.getByTestId('filter-under80'));
    expect(await screen.findByText('No providers match these filters.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Clear filters' }));
    expect(await screen.findByText('4 providers come to Old Town · sorted by trust')).toBeTruthy();
    fireEvent.press(screen.getByTestId('provider-prairie-wrench'));
    await waitFor(() => expect(view.getPathname()).toBe('/providers/prairie-wrench'));
  });
});

describe('C3 Provider profile', () => {
  it('shows the verified figures, credentials, prices and reviews; more reviews on request', async () => {
    const { server } = await start({ welcomed: true, url: '/providers/prairie-wrench' });
    expect(await screen.findByText('Master tier · verified')).toBeTruthy();
    expect(screen.getAllByText('Prairie Wrench').length).toBeGreaterThan(0);
    expect(screen.getByText('Mobile mechanic · Sampleville · since 2019')).toBeTruthy();
    expect(screen.getByText('4.9')).toBeTruthy();
    expect(screen.getByText('312 verified')).toBeTruthy();
    expect(screen.getByText('98%')).toBeTruthy();
    expect(screen.getByText('Red Seal journeyman')).toBeTruthy();
    expect(screen.getByText('Instant book')).toBeTruthy();
    expect(screen.getByText('Services & fixed prices')).toBeTruthy();
    expect(screen.getByText('$89')).toBeTruthy();
    expect(screen.getByText('Reviews · 312')).toBeTruthy();
    expect(screen.getByText(/Showed up at 7 am/)).toBeTruthy();
    fireEvent.press(screen.getByTestId('more-reviews'));
    expect(await screen.findByText(/Lee M\./)).toBeTruthy();
    expect(server.calls.some((c) => c.path === '/public/providers/prairie-wrench/reviews')).toBe(true);
  });

  it('asks a guest to sign in before saving a favourite; saves it when signed in', async () => {
    const { view } = await start({ welcomed: true, url: '/providers/prairie-wrench' });
    fireEvent.press(await screen.findByTestId('favourite'));
    await waitFor(() => expect(view.getPathname()).toBe('/sign-in'));
    setServices(null);
    const signed = await start({ signedIn: true, url: '/providers/prairie-wrench' });
    fireEvent.press(await screen.findByRole('button', { name: 'Add to favourites' }));
    expect(await screen.findByRole('button', { name: 'In favourites' })).toBeTruthy();
    expect(signed.server.services.favourites.has('m-prairie')).toBe(true);
  });

  it('starts the booking with the service tapped', async () => {
    const { view } = await start({ welcomed: true, url: '/providers/prairie-wrench' });
    fireEvent.press(await screen.findByTestId('menu-svc-oil'));
    await waitFor(() => expect(view.getPathname()).toBe('/book/prairie-wrench/service'));
    expect(await screen.findByTestId('service-svc-oil')).toBeTruthy();
    expect(screen.getByTestId('service-svc-oil').props.accessibilityState).toMatchObject({ selected: true });
  });
});

describe('C4–C7 booking a visit', () => {
  async function toTime() {
    fireEvent.press(await screen.findByTestId('book-next'));
    expect(await screen.findByText('Describe the problem in at least 10 characters.')).toBeTruthy();
    expect(screen.getByText('Tell us the vehicle year, make and model.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('field-vehicle'), '2018 Honda Civic · ABC 1234');
    fireEvent.changeText(screen.getByTestId('field-note'), 'Grinding on braking, worse when cold.');
    fireEvent.press(screen.getByTestId('book-next'));
  }

  it('books a fixed price: service, time in the business’s zone, review, escrow, confirmation', async () => {
    const { server, view } = await start({ signedIn: true, url: '/book/prairie-wrench/service', store: SAVED });
    expect(await screen.findByText('What does the car need?')).toBeTruthy();
    expect(screen.getByTestId('service-svc-brakes').props.accessibilityState).toMatchObject({ selected: true });
    await toTime();
    await waitFor(() => expect(view.getPathname()).toBe('/book/prairie-wrench/time'));
    expect(await screen.findByText('When suits you?')).toBeTruthy();
    const slot = firstSlot();
    // 7:00 in Etc/GMT+5 is 12:00 UTC: the chip reads the business's time
    expect(screen.getByTestId(`slot-${slot}`).props.accessibilityLabel).toBe('7:00 a.m.');
    expect(screen.getByTestId('field-address').props.value).toBe('1204 Example Ave');
    fireEvent.press(screen.getByTestId('book-next'));
    expect(await screen.findByText('Pick a time.')).toBeTruthy();
    expect(screen.getByText('Choose one of the options.')).toBeTruthy();
    expect(screen.getByText('Add access instructions (3+ characters).')).toBeTruthy();
    fireEvent.press(screen.getByTestId(`slot-${slot}`));
    fireEvent.press(screen.getByTestId('spot-2'));
    fireEvent.changeText(screen.getByTestId('field-access'), 'Stall P2-118');
    fireEvent.press(screen.getByTestId('book-next'));
    await waitFor(() => expect(view.getPathname()).toBe('/book/prairie-wrench/review'));
    expect(await screen.findByText('Review and hold payment')).toBeTruthy();
    expect(screen.getByText('Brake inspection')).toBeTruthy();
    expect(screen.getByTestId('review-when')).toBeTruthy();
    expect(screen.getByText('Tax 5%')).toBeTruthy();
    expect(screen.getByText('$4.45')).toBeTruthy();
    expect(screen.getByText('$93.45')).toBeTruthy();
    expect(screen.getByText(/Test payments · nothing is charged/)).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Hold $93.45 in escrow' }));
    expect(await screen.findByText('Accept the cancellation policy to continue.')).toBeTruthy();
    expect(screen.getByText('Agree to the Northline terms to continue.')).toBeTruthy();
    fireEvent.press(screen.getByTestId('agree-policies'));
    fireEvent.press(screen.getByTestId('agree-terms'));
    fireEvent.press(screen.getByRole('button', { name: 'Hold $93.45 in escrow' }));
    expect(await screen.findByText(/^Booked\. Ravi is coming /)).toBeTruthy();
    expect(view.getPathname()).toMatch(/^\/bookings\/01J9BKNEW\d+\/booked$/);
    expect(screen.getByText(/held in escrow\.$/)).toBeTruthy();
    expect(screen.getByText('What happens next')).toBeTruthy();

    const hold = server.calls.find((c) => c.method === 'POST' && c.path === '/me/bookings/holds');
    const checkout = server.calls.filter((c) => c.method === 'POST' && c.path === '/me/bookings/checkout');
    const confirm = server.calls.filter((c) => c.method === 'POST' && /\/me\/bookings\/holds\/.+\/confirm$/.test(c.path));
    expect(hold?.signed).toBe(true);
    expect(checkout).toHaveLength(1);
    expect(confirm).toHaveLength(1);
    const booked = [...server.services.bookings.values()].find((b) => b.bookingId.startsWith('01J9BKNEW'))!;
    expect(booked.addressLine).toBe('1204 Example Ave');
    expect(booked.startsAt).toBe(slot);

    fireEvent.press(screen.getByRole('button', { name: 'See notifications' }));
    await waitFor(() => expect(view.getPathname()).toBe('/notifications'));
  });

  it('asks a guest to sign in to hold the time, keeping the answers', async () => {
    updateDraft('prairie-wrench', { serviceId: 'svc-brakes', note: 'Grinding on braking.' });
    await start({ welcomed: true, url: '/book/prairie-wrench/time' });
    expect(await screen.findByText('Sign in to hold this time. Your answers stay here.')).toBeTruthy();
    expect(screen.queryByTestId('book-next')).toBeNull();
  });

  it('says when someone else just took the time, and offers the calendar again', async () => {
    updateDraft('prairie-wrench', { serviceId: 'svc-brakes', address: '1 Example Ave', spot: 0, access: 'Driveway' });
    const { server } = await start({ signedIn: true, url: '/book/prairie-wrench/time' });
    const slot = firstSlot();
    fireEvent.press(await screen.findByTestId(`slot-${slot}`));
    server.services.taken.add(slot);
    fireEvent.press(screen.getByTestId('book-next'));
    expect(await screen.findByText('That time was just taken. Pick another slot.')).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId(`slot-${slot}`).props.accessibilityState).toMatchObject({ disabled: true }));
  });

  function holdFor(server: Awaited<ReturnType<typeof start>>['server']) {
    const slot = firstSlot();
    server.services.holds.set('hold-t', { holdId: 'hold-t', bookingId: '01J9BKTEST', slug: 'prairie-wrench', serviceId: 'svc-brakes', startsAt: slot, endsAt: slot });
    updateDraft('prairie-wrench', {
      serviceId: 'svc-brakes', vehicle: '2018 Honda Civic', note: 'Grinding on braking, worse when cold.', address: '1 Example Ave', spot: 0, access: 'Driveway',
      hold: { holdId: 'hold-t', bookingId: '01J9BKTEST', startsAt: slot, endsAt: slot, expiresAt: slot },
    });
  }
  async function agreeAndPay(label = 'Hold $93.45 in escrow') {
    fireEvent.press(await screen.findByTestId('agree-policies'));
    fireEvent.press(screen.getByTestId('agree-terms'));
    fireEvent.press(screen.getByRole('button', { name: label }));
  }

  it('pays with Stripe through the port (saved card), never touching card numbers', async () => {
    const pay = jest.fn<ReturnType<BookingPayments['pay']>, Parameters<BookingPayments['pay']>>(async () => ({ status: 'paid' }));
    setBookingPayments({ pay });
    const { server, view } = await start({ signedIn: true, url: '/book/prairie-wrench/review' });
    server.services.payment = 'stripe';
    server.shop.provider = 'stripe';
    holdFor(server);
    expect(await screen.findByText('Pay with')).toBeTruthy();
    expect(screen.getByText('Visa ··4471')).toBeTruthy();
    await agreeAndPay();
    await waitFor(() => expect(view.getPathname()).toBe('/bookings/01J9BKTEST/booked'));
    expect(pay).toHaveBeenCalledWith(expect.objectContaining({ clientSecret: 'pi_01J9BKTEST_secret_fixture', publishableKey: 'pk_test_fixture' }), { kind: 'saved', paymentMethodId: 'pm_fixture_visa' });
  });

  it('stays on the review when the card sheet is cancelled, and says a declined card plainly', async () => {
    const pay = jest.fn<ReturnType<BookingPayments['pay']>, Parameters<BookingPayments['pay']>>(async () => ({ status: 'cancelled' }));
    setBookingPayments({ pay });
    const { server, view } = await start({ signedIn: true, url: '/book/prairie-wrench/review' });
    server.services.payment = 'stripe';
    server.shop.provider = 'stripe';
    holdFor(server);
    fireEvent.press(await screen.findByTestId('card-new'));
    await agreeAndPay();
    await waitFor(() => expect(pay).toHaveBeenCalledWith(expect.anything(), { kind: 'new' }));
    expect(view.getPathname()).toBe('/book/prairie-wrench/review');
    pay.mockResolvedValueOnce({ status: 'failed' });
    fireEvent.press(screen.getByRole('button', { name: 'Hold $93.45 in escrow' }));
    expect(await screen.findByText('The card was declined. Try another card.')).toBeTruthy();
    // one checkout key for the same answers: the retry replayed the same PaymentIntent
    const keys = server.calls.filter((c) => c.path === '/me/bookings/checkout');
    expect(keys.length).toBe(2);
    expect(server.calls.some((c) => /confirm$/.test(c.path))).toBe(false);
  });

  it('asks for the authenticator code when the sign-in had no second factor (S-51), then pays', async () => {
    const { server, view } = await start({ signedIn: true, url: '/book/prairie-wrench/review' });
    server.services.stepUp = 'required';
    holdFor(server);
    await agreeAndPay();
    expect(await screen.findByText('Confirm it’s you')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('field-step-up'), '12');
    fireEvent.press(screen.getByTestId('step-up-confirm'));
    expect(await screen.findByText('Enter the 6-digit code.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('field-step-up'), '000000');
    fireEvent.press(screen.getByTestId('step-up-confirm'));
    expect(await screen.findByText("That code doesn't match. Check it and try again.")).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('field-step-up'), FIXTURE_TOTP);
    fireEvent.press(screen.getByTestId('step-up-confirm'));
    await waitFor(() => expect(view.getPathname()).toBe('/bookings/01J9BKTEST/booked'));
  });

  it('sends an account without a second factor to add a passkey on the website', async () => {
    const { server } = await start({ signedIn: true, url: '/book/prairie-wrench/review' });
    server.services.stepUp = 'enrol';
    holdFor(server);
    await agreeAndPay();
    expect(await screen.findByText('Add a passkey to pay')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Open security settings' }));
    expect(Linking.openURL).toHaveBeenCalledWith('http://localhost:3000/account?tab=security');
  });

  it('says the hold ended and offers the calendar again', async () => {
    const { server, view } = await start({ signedIn: true, url: '/book/prairie-wrench/review' });
    holdFor(server);
    server.services.holds.clear();
    await agreeAndPay();
    expect(await screen.findByText('Your 10-minute hold ended. Pick the time again.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Pick the time again' }));
    await waitFor(() => expect(view.getPathname()).toBe('/book/prairie-wrench/time'));
  });

  it('asks for a quote instead: the rules, sign-in, the request and what happens next', async () => {
    const { server, view } = await start({ signedIn: true, url: '/book/prairie-wrench/service' });
    fireEvent.press(await screen.findByTestId('ask-quote'));
    expect(await screen.findByText('Ask for a quote')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Send quote request' }));
    expect(await screen.findByText('Describe the job in at least 10 characters.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('field-vehicle'), '2012 Ford Focus');
    fireEvent.changeText(screen.getByTestId('field-note'), 'Battery light comes on after a minute.');
    fireEvent.press(screen.getByRole('button', { name: 'Send quote request' }));
    expect(await screen.findByText('Quote requested')).toBeTruthy();
    expect(screen.getByText(/Request QR-3104 is with Prairie Wrench/)).toBeTruthy();
    expect(server.services.quoteRequests[0]).toMatchObject({
      category: 'mobile-mechanic',
      providers: ['prairie-wrench'],
      vehicle: { year: '2012', make: 'Ford', model: 'Focus' },
    });
    fireEvent.press(screen.getByRole('button', { name: 'See orders & bookings' }));
    await waitFor(() => expect(view.getPathname()).toBe('/orders'));
  });

  it('opens quote-only services in quote mode and asks guests to sign in to send', async () => {
    await start({ welcomed: true, url: '/book/prairie-wrench/service' });
    fireEvent.press(await screen.findByTestId('service-svc-alternator'));
    expect(await screen.findByText('Ask for a quote')).toBeTruthy();
    expect(screen.getByText('Sign in to send the request.')).toBeTruthy();
  });
});

describe('C8 Notifications', () => {
  it('lists bookings, orders and quotes, filters them, words quiet hours and switches them', async () => {
    const { server, view } = await start({ signedIn: true, url: '/notifications' });
    expect(await screen.findByText('Prairie Wrench is on the way')).toBeTruthy();
    expect(screen.getByText('Job done · Prairie Wrench — please sign off')).toBeTruthy();
    expect(screen.getByText('Delivered from Old Town Bakery')).toBeTruthy();
    expect(screen.getByText('Quote ready · Sable & Soda')).toBeTruthy();
    expect(await screen.findByText('Quiet hours 10:00 p.m. – 7:00 a.m. · manage in Settings → Notifications.')).toBeTruthy();
    fireEvent.press(screen.getByTestId('inbox-orders'));
    await waitFor(() => expect(screen.queryByText('Prairie Wrench is on the way')).toBeNull());
    expect(screen.getByText('Delivered from Old Town Bakery')).toBeTruthy();
    fireEvent.press(screen.getByTestId('inbox-bookings'));
    expect(await screen.findByText('Prairie Wrench is on the way')).toBeTruthy();
    fireEvent.press(screen.getByTestId('quiet-toggle'));
    expect(await screen.findByText('Quiet hours are off.')).toBeTruthy();
    expect(server.services.quiet.quietOn).toBe(false);
    fireEvent.press(screen.getByTestId('notification-01J9BOOKINGROUTE'));
    await waitFor(() => expect(view.getPathname()).toBe('/bookings/01J9BOOKINGROUTE/eta'));
  });

  it('asks a guest to sign in', async () => {
    await start({ welcomed: true, url: '/notifications' });
    expect(await screen.findByText('Sign in to see your notifications.')).toBeTruthy();
  });
});

describe('the booking deep link (S-102 → /bookings/<id>)', () => {
  it.each([
    ['01J9BOOKING', '/bookings/01J9BOOKING/eta'],
    ['01J9BOOKINGROUTE', '/bookings/01J9BOOKINGROUTE/eta'],
    ['01J9BOOKINGDONE', '/bookings/01J9BOOKINGDONE/sign-off'],
    ['01J9BOOKINGPAID', '/bookings/01J9BOOKINGPAID/sign-off'],
  ])('%s opens %s', async (id, path) => {
    const { view } = await start({ signedIn: true, url: `/bookings/${id}` });
    await waitFor(() => expect(view.getPathname()).toBe(path));
  });

  it('asks a guest to sign in', async () => {
    await start({ welcomed: true, url: '/bookings/01J9BOOKING' });
    expect(await screen.findByText('Sign in to see your booking.')).toBeTruthy();
  });
});

describe('C9 Day-of ETA', () => {
  it('says who is on the way, with the steps in the business’s zone', async () => {
    const { server } = await start({ signedIn: true, url: '/bookings/01J9BOOKINGROUTE/eta' });
    expect(await screen.findByText('Ravi is on the way')).toBeTruthy();
    expect(screen.getByText('On the way')).toBeTruthy();
    expect(screen.getByText('Booking BK-7712')).toBeTruthy();
    expect(screen.getByText('Ravi · Prairie Wrench')).toBeTruthy();
    expect(screen.getByText('Ravi left for your place')).toBeTruthy();
    const left = server.services.bookings.get('01J9BOOKINGROUTE')!.steps[0]!.at;
    const expected = new Intl.DateTimeFormat('en-CA', { hour: 'numeric', minute: '2-digit', timeZone: BUSINESS_ZONE }).format(new Date(left));
    expect(screen.getByText(expected)).toBeTruthy();
    expect(screen.queryByTestId('go-sign-off')).toBeNull();
  });

  it('says when a confirmed job is coming', async () => {
    await start({ signedIn: true, url: '/bookings/01J9BOOKING/eta' });
    expect(await screen.findByText(/^Ravi comes /)).toBeTruthy();
    expect(screen.getByText('Booked')).toBeTruthy();
  });
});

describe('C10 Completion & sign-off', () => {
  it('shows the report and releases the payment, then offers to rate', async () => {
    const { server, view } = await start({ signedIn: true, url: '/bookings/01J9BOOKINGDONE/sign-off' });
    expect(await screen.findByText('Job complete · awaiting your sign-off')).toBeTruthy();
    expect(screen.getByText('Ravi says the Brake inspection is done.')).toBeTruthy();
    expect(screen.getByText(/Grinding was a stone in the caliper/)).toBeTruthy();
    expect(screen.getByText('2 completion photos on file')).toBeTruthy();
    expect(screen.getByText('$93.45')).toBeTruthy();
    expect(screen.getByText(/^Releases automatically in 47 h if you do nothing/)).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Release payment' }));
    expect(await screen.findByText('Released.')).toBeTruthy();
    expect(screen.getByText(/\$93\.45 paid to Prairie Wrench\./)).toBeTruthy();
    expect(server.services.bookings.get('01J9BOOKINGDONE')!.state).toBe('signed_off');
    expect(server.calls.some((c) => c.method === 'POST' && c.path === '/me/bookings/01J9BOOKINGDONE/sign-off' && c.signed)).toBe(true);
    fireEvent.press(screen.getByRole('button', { name: 'Rate Ravi' }));
    await waitFor(() => expect(view.getPathname()).toBe('/bookings/01J9BOOKINGDONE/review'));
  });

  it('raises an issue on Journey B’s problem screen, and says when sign-off can’t happen yet', async () => {
    const { view } = await start({ signedIn: true, url: '/bookings/01J9BOOKINGDONE/sign-off' });
    fireEvent.press(await screen.findByRole('button', { name: 'Raise an issue' }));
    await waitFor(() => expect(view.getPathname()).toBe('/problem/booking/01J9BOOKINGDONE'));
    setServices(null);
    await start({ signedIn: true, url: '/bookings/01J9BOOKINGROUTE/sign-off' });
    expect(await screen.findByText('Ravi hasn’t finished this job yet. You can sign off once it’s marked complete.')).toBeTruthy();
  });

  it('shows the api’s refusal', async () => {
    const { server } = await start({ signedIn: true, url: '/bookings/01J9BOOKINGDONE/sign-off' });
    await screen.findByRole('button', { name: 'Release payment' });
    server.services.bookings.get('01J9BOOKINGDONE')!.state = 'on_site';
    fireEvent.press(screen.getByRole('button', { name: 'Release payment' }));
    expect(await screen.findByText("This job is on site and can't move to signed off.")).toBeTruthy();
  });
});

describe('C11 Two-way review', () => {
  it('says plainly the review isn’t sent yet, and saves the favourite', async () => {
    const { server, view } = await start({ signedIn: true, url: '/bookings/01J9BOOKINGPAID/review' });
    expect(await screen.findByText('How was Ravi?')).toBeTruthy();
    expect(screen.getByTestId('review-gap')).toBeTruthy();
    fireEvent.press(screen.getByTestId('star-5'));
    expect(screen.getByTestId('star-5').props.accessibilityState).toMatchObject({ checked: true });
    fireEvent.press(screen.getByRole('button', { name: 'On time' }));
    fireEvent.press(screen.getByRole('button', { name: 'Submit review' }));
    await waitFor(() => expect(view.getPathname()).toBe('/orders'));
    expect(server.services.favourites.has('m-prairie')).toBe(true);
  });

  it('only for finished jobs', async () => {
    await start({ signedIn: true, url: '/bookings/01J9BOOKING/review' });
    expect(await screen.findByText('Only paid bookings can be reviewed, once the job is done.')).toBeTruthy();
  });
});

describe('Journey C’s rules', () => {
  it('splits the design’s one vehicle field', () => {
    expect(parseVehicle('2018 Honda Civic · BKT 4471')).toEqual({ year: '2018', make: 'Honda', model: 'Civic', plate: 'BKT 4471' });
    expect(parseVehicle(' 2012 Ford Focus Wagon ')).toEqual({ year: '2012', make: 'Ford', model: 'Focus Wagon' });
    expect(parseVehicle('Honda Civic')).toBeNull();
  });

  it('checks the first step with the api’s messages, in both languages', () => {
    const p = PROVIDERS[0]!;
    const s = p.services[0];
    expect(checkDetails(p, s, { vehicle: '', note: 'short', quote: false }, en.t)).toEqual({
      note: 'Describe the problem in at least 10 characters.',
      vehicle: 'Tell us the vehicle year, make and model.',
    });
    expect(checkDetails(p, s, { vehicle: 'x', note: '', quote: true }, translator('fr-CA').t)).toEqual({
      note: 'Décrivez le travail en au moins 10 caractères.',
      vehicle: 'Indiquez l’année, la marque et le modèle du véhicule.',
    });
  });

  it('filters providers on the business’s own day', () => {
    const now = new Date('2026-10-02T03:00:00Z'); // still Oct 1 in Etc/GMT+5
    const base = { merchantId: 'm', slug: 's', name: 'N', brandColor: '', rating: 5, reviewCount: 1, pricingMode: 'fixed' as const, zones: [], timeZone: BUSINESS_ZONE };
    const items = [
      { ...base, slug: 'a', tier: 'master', instantBook: true, fromCents: 7900, nextAvailable: '2026-10-02T02:00:00Z' },
      { ...base, slug: 'b', tier: 'trusted', instantBook: false, fromCents: 9900, nextAvailable: '2026-10-02T06:00:00Z' },
    ];
    expect(applyFilters(items, new Set(['today'])).map((p) => p.slug)).toEqual(['a']);
    expect(applyFilters(items, new Set(['under80', 'master'])).map((p) => p.slug)).toEqual(['a']);
    expect(applyFilters(items, new Set(['instant', 'master'])).map((p) => p.slug)).toEqual(['a']);
    expect(nextSlot(en.t, en.time, 'en', '2026-10-02T02:00:00Z', BUSINESS_ZONE, now)).toBe('Today 9:00 p.m.');
    expect(nextSlot(en.t, en.time, 'en', '2026-10-02T15:00:00Z', BUSINESS_ZONE, now)).toBe('Tomorrow 10:00 a.m.');
    expect(nextSlot(en.t, en.time, 'en', null, BUSINESS_ZONE, now)).toBe('No openings soon');
  });

  it('words how long ago', () => {
    const now = Date.parse('2026-10-02T12:00:00Z');
    expect(ago('2026-10-02T11:59:30Z', en.t, now)).toBe('now');
    expect(ago('2026-10-02T11:48:00Z', en.t, now)).toBe('12 min');
    expect(ago('2026-10-01T10:00:00Z', en.t, now)).toBe('Yesterday');
    expect(ago('2026-09-29T12:00:00Z', translator('fr-CA').t, now)).toBe('3 j');
  });
});
