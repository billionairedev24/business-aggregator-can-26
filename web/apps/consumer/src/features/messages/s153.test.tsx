import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useParams } from '@tanstack/react-router';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { BookingStatus } from '../aftercare/BookingStatus';
import { OrdersScreen } from '../account/OrdersScreen';
import { MessagesScreen } from './MessagesScreen';
import { PhotoPicker } from '../photos/PhotoPicker';
import type { Photo } from '../photos/api';

/**
 * S-153 on the consumer web: messages with providers (Account › Messages, a booking's "Message …"), cancelling a
 * booking with its late fee, paying a deposit quote's balance, photos of the job, Account › Quote requests.
 */
const AMARA = { id: 'C1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: null, initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };
const soon = new Date(Date.now() + 6 * 3_600_000).toISOString();
const BOOKING = {
  bookingId: 'B1', ref: 'BK-7801', providerName: 'Sample Wrench', providerSlug: 'sample-wrench', memberFirstName: 'Jas', title: 'Alternator',
  type: 'visit', startsAt: soon, endsAt: soon, addressLine: '1 Sample St', priceCents: 43500, taxCents: 2175,
  heldCents: 11419, freeCancelUntil: new Date(Date.now() - 3_600_000).toISOString(), state: 'confirmed', timeZone: 'America/Regina',
  photos: [{ id: 'P1', fileName: 'dent.png' }], cancellation: { windowHours: 24, feeBps: 5000, feeCents: 0 },
  balance: { state: 'failed', cents: 32625, taxCents: 1631, dueAt: null, nextAt: null, attempts: 3, failure: 'declined' }, cancelled: null,
};
const THREAD = {
  id: 'T1', refType: 'booking', refId: 'B1', refCode: 'BK-7801', subject: 'Alternator', businessName: 'Sample Wrench', businessSlug: 'sample-wrench',
  href: '/bookings/B1', lastMessageAt: '2026-10-09T15:00:00Z', lastMessage: 'See you at 9', unread: true,
};
const MESSAGES = [
  { id: 'M1', from: 'me', body: 'Is parking OK in the lane?', at: '2026-10-09T14:00:00Z', flagged: false },
  { id: 'M2', from: 'business', body: 'See you at 9', at: '2026-10-09T15:00:00Z', flagged: false },
];

type Reply = { status?: number; body?: unknown } | undefined;
let routes: Record<string, (c: Call) => Reply>;
const server = (c: Call): Reply => {
  if (c.url === '/bff/session') return { body: { user: AMARA, guestId: 'g' } };
  return routes[`${c.method} ${c.url.split('?')[0]}`]?.(c);
};

beforeEach(() => {
  routes = {
    'GET /api/v1/me/bookings/B1': () => ({ body: BOOKING }),
    'GET /api/v1/me/bookings/B1/eta': () => ({ body: { state: 'confirmed', sharing: false, method: 'straight_line' } }),
    'GET /api/v1/me/threads': () => ({ body: { items: [THREAD] } }),
    'POST /api/v1/me/threads': () => ({ body: { thread: THREAD, messages: MESSAGES } }),
    'GET /api/v1/me/threads/T1': () => ({ body: { thread: THREAD, messages: MESSAGES } }),
    'POST /api/v1/me/threads/T1/read': () => ({ status: 204 }),
    'GET /api/v1/me/payment-methods': () => ({ body: { provider: 'fake', items: [] } }),
  };
});
afterEach(() => { vi.unstubAllGlobals(); });

function BookingPage() {
  const { bookingId } = useParams({ strict: false }) as { bookingId: string };
  return <BookingStatus bookingId={bookingId} />;
}
const openBooking = (locale: 'en' | 'fr' = 'en') => {
  const calls = mockFetch(server);
  renderApp('/bookings/B1', { locale, routes: { booking: () => <BookingPage /> } });
  return calls;
};

describe('messages with the provider', () => {
  it('opens the booking’s thread, shows both sides and sends a message', async () => {
    const calls = openBooking();
    const u = userEvent.setup({ delay: null });
    routes['POST /api/v1/me/threads/T1/messages'] = c => ({ status: 201, body: { id: 'M3', from: 'me', body: (c.body as { body: string }).body, at: '2026-10-09T15:30:00Z', flagged: false } });
    await u.click(await screen.findByRole('button', { name: 'Message Sample Wrench' }));
    const log = await screen.findByRole('log', { name: 'Messages with Sample Wrench' });
    expect(within(log).getByText('Is parking OK in the lane?')).toBeInTheDocument();
    expect(within(log).getByText('See you at 9')).toBeInTheDocument();
    expect(calls.find(c => c.method === 'POST' && c.url === '/api/v1/me/threads')?.body).toEqual({ refType: 'booking', refId: 'B1' });
    await waitFor(() => expect(calls.some(c => c.url === '/api/v1/me/threads/T1/read')).toBe(true));

    await u.click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByText('Write a message.')).toBeInTheDocument();
    await u.type(screen.getByRole('textbox', { name: /Your message/ }), 'Thanks!');
    await u.click(screen.getByRole('button', { name: 'Send' }));
    expect(await within(log).findByText('Thanks!')).toBeInTheDocument();
    expect(calls.find(c => c.url === '/api/v1/me/threads/T1/messages')?.body).toEqual({ body: 'Thanks!' });
    await expectNoAxeViolations(document.body);
  });

  it('a flagged message shows the nudge; a refused one shows why', async () => {
    openBooking();
    const u = userEvent.setup({ delay: null });
    routes['POST /api/v1/me/threads/T1/messages'] = () => ({ status: 201, body: { id: 'M4', from: 'me', body: 'Can I e-transfer? •••-•••-••••', at: '2026-10-09T15:30:00Z', flagged: true } });
    await u.click(await screen.findByRole('button', { name: 'Message Sample Wrench' }));
    await u.type(await screen.findByRole('textbox', { name: /Your message/ }), 'Can I e-transfer? 403-555-0188');
    await u.click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByText(/Keep payments and contact details on/)).toBeInTheDocument();
    routes['POST /api/v1/me/threads/T1/messages'] = () => ({ status: 422, body: { errors: [{ field: 'body', rule: 'length', message: 'Keep messages under 2,000 characters.' }] } });
    await u.type(screen.getByRole('textbox', { name: /Your message/ }), 'again');
    await u.click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByText('Keep messages under 2,000 characters.')).toBeInTheDocument();
  });

  it('Account › Messages lists the threads and opens one, in French too', async () => {
    mockFetch(server);
    renderApp('/account/messages?thread=T1', { locale: 'fr', routes: { messages: () => <MessagesScreen /> } });
    expect(await screen.findByRole('heading', { level: 1, name: 'Messages' })).toBeInTheDocument();
    const list = screen.getByRole('list', { name: 'Messages' });
    expect(within(list).getByText('Non lu')).toBeInTheDocument();
    expect(within(list).getByText(/Réservation BK-7801/)).toBeInTheDocument();
    expect(await screen.findByRole('log', { name: 'Messages avec Sample Wrench' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body);
  });

  it('no threads: one line and a way to start', async () => {
    routes['GET /api/v1/me/threads'] = () => ({ body: { items: [] } });
    mockFetch(server);
    renderApp('/account/messages', { routes: { messages: () => <MessagesScreen /> } });
    expect(await screen.findByText('No messages yet. Ask a provider a question from a quote or a booking.')).toBeInTheDocument();
  });

  it('a list that can’t load says so and retries', async () => {
    routes['GET /api/v1/me/threads'] = () => ({ status: 500, body: {} });
    mockFetch(server);
    renderApp('/account/messages', { routes: { messages: () => <MessagesScreen /> } });
    expect(await screen.findByText('Your messages couldn’t be loaded.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Retry|Try again/ })).toBeInTheDocument();
  });
});

describe('cancelling and the balance', () => {
  it('shows the terms, asks what cancelling costs, and cancels with an Idempotency-Key', async () => {
    const calls = openBooking();
    const u = userEvent.setup({ delay: null });
    routes['GET /api/v1/me/bookings/B1/cancellation'] = () => ({ body: {
      cancellable: true, late: true, feeCents: 5710, feeTaxCents: 285, feeTotalCents: 5995, releasedCents: 5424, freeUntil: BOOKING.freeCancelUntil,
      windowHours: 24, feeBps: 5000, feeFixedCents: 0,
    } });
    routes['POST /api/v1/me/bookings/B1/cancel'] = () => ({ body: { ...BOOKING, state: 'cancelled', cancelled: { at: '2026-10-09T15:00:00Z', feeCents: 5710, feeTaxCents: 285 }, balance: { ...BOOKING.balance, state: 'cancelled' } } });
    const policy = await screen.findByRole('region', { name: 'Cancellation policy' });
    expect(policy).toHaveTextContent('After that, 50% of the hold is kept as a late-cancellation fee.');
    await u.click(within(policy).getByRole('button', { name: 'Cancel booking' }));
    const dialog = await screen.findByRole('alertdialog', { name: 'Cancel this booking?' });
    expect(await within(dialog).findByText('It’s less than 24 hours before the visit: $59.95 is kept as a late-cancellation fee and $54.24 is released.')).toBeInTheDocument();
    await expectNoAxeViolations(document.body);
    await u.click(within(dialog).getByRole('button', { name: 'Cancel booking' }));
    expect(await screen.findByText(/A late-cancellation fee of \$59\.95 was charged\./)).toBeInTheDocument();
    const cancel = calls.find(c => c.url === '/api/v1/me/bookings/B1/cancel')!;
    expect(cancel.headers['idempotency-key'] ?? cancel.headers['Idempotency-Key']).toBeTruthy();
    expect(screen.getByText('This booking was cancelled.')).toBeInTheDocument();
  });

  it('a balance that couldn’t be held is paid from the booking (the fake gateway authorizes at once)', async () => {
    const calls = openBooking();
    const u = userEvent.setup({ delay: null });
    routes['POST /api/v1/me/bookings/B1/balance'] = () => ({ body: { bookingId: 'B1', amountCents: 32625, taxCents: 1631, totalCents: 34256, status: 'authorized', provider: 'fake' } });
    routes['POST /api/v1/me/bookings/B1/balance/confirm'] = () => ({ status: 201, body: { ...BOOKING, balance: { ...BOOKING.balance, state: 'held' } } });
    const card = await screen.findByRole('region', { name: 'Balance' });
    expect(within(card).getByRole('alert')).toHaveTextContent('We couldn’t hold the balance of $342.56. Pay it before the visit.');
    await u.click(within(card).getByRole('button', { name: 'Pay $342.56 now' }));
    expect(await within(card).findByText('The balance of $342.56 is held in escrow too.')).toBeInTheDocument();
    expect(calls.filter(c => c.url.startsWith('/api/v1/me/bookings/B1/balance')).map(c => c.url)).toEqual(['/api/v1/me/bookings/B1/balance', '/api/v1/me/bookings/B1/balance/confirm']);
  });

  it('in French: the terms, the balance and the photos of the job', async () => {
    openBooking('fr');
    const policy = await screen.findByRole('region', { name: 'Politique d’annulation' });
    expect(policy).toHaveTextContent('Ensuite, 50 % du montant retenu est conservé comme frais d’annulation tardive.');
    expect(screen.getByRole('region', { name: 'Solde' })).toHaveTextContent('Nous n’avons pas pu retenir le solde de 342,56 $. Payez-le avant la visite.');
    expect(screen.getByRole('img', { name: 'Votre photo 1' })).toHaveAttribute('src', '/api/v1/me/photos/P1');
  });
});

describe('photos of the job', () => {
  let latest: Photo[] = [];
  function Harness() {
    const [photos, setPhotos] = useState<Photo[]>([]);
    latest = photos;
    return <PhotoPicker photos={photos} onChange={setPhotos} />;
  }
  const pick = async (file: File) => {
    const input = document.querySelector('input[type=file]') as HTMLInputElement;
    await userEvent.upload(input, file, { applyAccept: false });
  };
  it('checks type and size with the api’s words, uploads, shows and removes', async () => {
    const calls = mockFetch(c => (c.url === '/bff/session' ? { body: { user: AMARA, guestId: 'g' } }
      : c.url === '/api/v1/me/photos' ? { status: 201, body: { id: 'P9', fileName: 'leak.png', contentType: 'image/png', byteSize: 1200 } } : undefined));
    renderApp('/help', { routes: { help: () => <Harness /> } });
    await screen.findByText('Photos (optional)');
    await pick(new File(['%PDF'], 'scan.pdf', { type: 'application/pdf' }));
    expect(await screen.findByText('Add a photo (JPEG, PNG, HEIC or WebP).')).toBeInTheDocument();
    await pick(new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'huge.png', { type: 'image/png' }));
    expect(await screen.findByText('Photos can be up to 10 MB.')).toBeInTheDocument();
    await pick(new File([new Uint8Array(64)], 'leak.png', { type: 'image/png' }));
    expect(await screen.findByRole('img', { name: 'Your photo 1' })).toHaveAttribute('src', '/api/v1/me/photos/P9');
    expect(latest).toHaveLength(1);
    expect(calls.find(c => c.url === '/api/v1/me/photos')?.body).toBeInstanceOf(FormData);
    await userEvent.click(screen.getByRole('button', { name: 'Remove leak.png' }));
    await waitFor(() => expect(latest).toHaveLength(0));
  });
});

describe('Account › Quote requests', () => {
  it('lists every request with what became of it', async () => {
    routes['GET /api/v1/me/activity'] = () => ({ body: { items: [] } });
    routes['GET /api/v1/me/quote-requests'] = () => ({ body: { items: [
      { id: 'R2', ref: 'QT-3311', title: 'Brakes squeal', createdAt: '2026-10-08T15:00:00Z', expiresAt: '2026-10-15T15:00:00Z', providers: 2, quotes: 0, lowestCents: null, status: 'waiting', bookingId: null, photoCount: 2, href: '/quotes/requests/R2' },
      { id: 'R1', ref: 'QT-3310', title: 'Alternator', createdAt: '2026-10-05T15:00:00Z', expiresAt: null, providers: 1, quotes: 1, lowestCents: 45675, status: 'accepted', bookingId: 'B1', photoCount: 0, href: '/bookings/B1' },
    ] } });
    mockFetch(server);
    renderApp('/account/orders?tab=quotes', { routes: { orders: () => <OrdersScreen /> } });
    expect(await screen.findByRole('tab', { name: /^Quote requests\s*2$/ })).toHaveAttribute('aria-selected', 'true');
    const list = await screen.findByRole('list', { name: 'Quote requests · 2' });
    const [waiting, accepted] = within(list).getAllByRole('listitem');
    expect(waiting).toHaveTextContent('Waiting for quotes');
    expect(waiting).toHaveTextContent('0 quotes from 2 providers asked · 2 photos');
    expect(accepted).toHaveTextContent('Accepted');
    expect(within(accepted!).getByRole('link', { name: 'Details' })).toHaveAttribute('href', '/bookings/B1');
    await expectNoAxeViolations(document.body);
  });
});
