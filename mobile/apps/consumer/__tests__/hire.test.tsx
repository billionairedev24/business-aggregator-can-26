import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Linking from 'expo-linking';
import { act, fireEvent, screen, waitFor } from 'expo-router/testing-library';

import { clearDraft } from '../src/services/draft';
import { setServices } from '../src/services';
import { start } from './support';

jest.mock('@stripe/stripe-react-native', () => ({}));

const picker = () => jest.requireMock('expo-image-picker') as { __queue: unknown[] };
const photo = (n: number, extra: object = {}) => ({ uri: `file:///photos/job${n}.jpg`, mimeType: 'image/jpeg', fileSize: 220_000, width: 1200, height: 900, ...extra });

afterEach(async () => {
  setServices(null);
  clearDraft();
  picker().__queue.length = 0;
  jest.clearAllMocks();
  await AsyncStorage.clear();
});

/** S-153 on the phone: messages with providers, cancelling under the booking's terms, photos and the quote requests. */
describe('messages with providers (S-153)', () => {
  it('lists the threads, opens one (read), and sends a message — masked by the api, marked here', async () => {
    const { server, view } = await start({ signedIn: true, url: '/messages' });
    fireEvent.press(await screen.findByTestId('thread-th-1'));
    await waitFor(() => expect(view.getPathname()).toBe('/messages/th-1'));
    expect(await screen.findByText('Is the venue indoors?')).toBeTruthy();
    await waitFor(() => expect(server.calls.some((c) => c.method === 'POST' && c.path === '/me/threads/th-1/read')).toBe(true));

    fireEvent.press(screen.getByTestId('message-send'));
    expect(await screen.findByText('Write a message.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('message-body'), 'Yes, indoors. Call me at 555 201 3344.');
    fireEvent.press(screen.getByTestId('message-send'));
    expect(await screen.findByText('Yes, indoors. Call me at •••.')).toBeTruthy();
    expect(screen.getByText('Contact details and outside payment requests are hidden.')).toBeTruthy();
    expect(server.hire.threads.get('th-1')!.messages.at(-1)).toMatchObject({ from: 'me', flagged: true });
  });

  it('lands on the thread from the push’s deep link, and asks guests to sign in', async () => {
    await start({ welcomed: true, url: '/messages/th-1' });
    expect(await screen.findByText('Sign in to see your messages with providers.')).toBeTruthy();
  });

  it('asks a question from a quote: the request’s thread with that provider; shows the terms before accepting', async () => {
    const { server, view } = await start({ signedIn: true, url: '/quotes/q-2988' });
    expect(await screen.findByText('Free cancellation until 48 h before. After that, $50.00 of the hold is kept as a late-cancellation fee.')).toBeTruthy();
    fireEvent.press(screen.getByTestId('quote-ask'));
    await waitFor(() => expect(view.getPathname()).toBe('/messages/th-1'));
    expect(server.calls.find((c) => c.method === 'POST' && c.path === '/me/threads')?.body).toEqual({ refType: 'quote', refId: 'qr-9', merchantId: 'm-sable' });
  });
});

describe('a booking: terms, message, cancel (S-153)', () => {
  it('cancels for free before the window, after showing what it costs', async () => {
    const { server } = await start({ signedIn: true, url: '/bookings/01J9BOOKING/eta' });
    expect(await screen.findByText('Free cancellation until 24 h before. After that, 50% of the hold is kept as a late-cancellation fee.')).toBeTruthy();
    fireEvent.press(screen.getByTestId('booking-cancel'));
    expect(await screen.findByText('It’s free: the $93.45 held on your card is released.')).toBeTruthy();
    fireEvent.press(screen.getByTestId('cancel-confirm'));
    expect(await screen.findByTestId('booking-cancelled')).toBeTruthy();
    expect(server.services.bookings.get('01J9BOOKING')!.state).toBe('cancelled');
    expect(server.calls.find((c) => c.path === '/me/bookings/01J9BOOKING/cancel')).toBeTruthy();
  });

  it('says the late fee inside the window, keeps the booking on "Keep", and messages the provider', async () => {
    const { server, view } = await start({ signedIn: true, url: '/bookings/01J9BOOKINGROUTE/eta' });
    fireEvent.press(await screen.findByTestId('booking-cancel'));
    expect(await screen.findByText('It’s less than 24 hours before the visit: $46.73 is kept as a late-cancellation fee and $46.72 is released.')).toBeTruthy();
    fireEvent.press(screen.getByTestId('cancel-keep'));
    expect(screen.queryByTestId('cancel-terms')).toBeNull();
    expect(server.services.bookings.get('01J9BOOKINGROUTE')!.state).toBe('en_route');

    fireEvent.press(screen.getByTestId('booking-message'));
    await waitFor(() => expect(view.getPathname()).toMatch(/^\/messages\/th-\d+$/));
    expect(await screen.findByText('Write to Prairie Wrench — they’ll get a notification.')).toBeTruthy();
  });

  it('shows a balance that couldn’t be held and sends the person to the website to pay it', async () => {
    const { server, services } = await start({ signedIn: true, url: '/bookings/01J9BOOKING/eta' });
    await screen.findByTestId('booking-actions');
    Object.assign(server.services.bookings.get('01J9BOOKING')!, { balance: { state: 'failed', cents: 30000, taxCents: 1500, dueAt: null, nextAt: null, attempts: 3, failure: 'declined' } });
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText('We couldn’t hold the balance of $315.00. Pay it before the visit.')).toBeTruthy();
    fireEvent.press(screen.getByTestId('pay-balance'));
    expect(Linking.openURL).toHaveBeenCalledWith('http://localhost:3000/bookings/01J9BOOKING');
  });

  it('is in French', async () => {
    await AsyncStorage.setItem('nl.app.language', 'fr-CA');
    await start({ signedIn: true, url: '/bookings/01J9BOOKING/eta', store: { 'nl.app.language': 'fr-CA' } });
    expect(await screen.findByText(/^Annulation gratuite jusqu’à 24 h avant\. Ensuite, 50\s%\sdu montant retenu sont conservés comme frais d’annulation tardive\.$/)).toBeTruthy();
    expect(screen.getByText('Annuler la réservation')).toBeTruthy();
  });
});

describe('photos on a quote request and the quote requests list (S-153)', () => {
  it('uploads photos of the job and sends them with the request', async () => {
    const { server } = await start({ signedIn: true, url: '/book/prairie-wrench/service' });
    fireEvent.press(await screen.findByTestId('ask-quote'));
    picker().__queue.push(photo(1));
    fireEvent.press(await screen.findByTestId('job-photo-library'));
    expect(await screen.findByLabelText('Photo 1')).toBeTruthy();
    picker().__queue.push(photo(2, { fileSize: 11 * 1024 * 1024 }));
    fireEvent.press(screen.getByTestId('job-photo-library'));
    expect(await screen.findByText('That photo is over 10 MB. Choose a smaller one.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('field-vehicle'), '2012 Ford Focus');
    fireEvent.changeText(screen.getByTestId('field-note'), 'Battery light comes on after a minute.');
    fireEvent.press(screen.getByRole('button', { name: 'Send quote request' }));
    expect(await screen.findByText('Quote requested')).toBeTruthy();
    expect(server.hire.photos).toEqual(['ph-1']);
    expect(server.services.quoteRequests[0]).toMatchObject({ photoIds: ['ph-1'] });
  });

  it('lists the quote requests under Orders: one quote opens in the app, the rest on the website', async () => {
    const { view } = await start({ signedIn: true, url: '/orders' });
    fireEvent.press(await screen.findByTestId('orders-quotes'));
    expect(await screen.findByText('1 quote to compare')).toBeTruthy();
    expect(screen.getByText(/QT-2988 · .* · 2 providers · 2 photos/)).toBeTruthy();
    fireEvent.press(screen.getByTestId('request-qr-7'));
    expect(Linking.openURL).toHaveBeenCalledWith('http://localhost:3000/quotes/requests/qr-7');
    fireEvent.press(screen.getByTestId('request-qr-9'));
    await waitFor(() => expect(view.getPathname()).toBe('/quotes/q-2988'));
  });
});
