import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useParams } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { BookingStatus } from './BookingStatus';
import { CourierTip, ReviewPanel } from './Aftercare';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

/** Mobile gaps part 2 on the web: posting and changing a review, tipping the courier after delivery, the visit's ETA. */
const AMARA = { id: 'C1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: null, initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };
const BOOKING = {
  bookingId: 'B1', ref: 'BK-7801', providerName: 'Sample Wrench', providerSlug: 'sample-wrench', memberFirstName: 'Jas', title: 'Brake inspection',
  type: 'visit', startsAt: '2026-10-09T16:00:00Z', endsAt: '2026-10-09T17:00:00Z', addressLine: '1 Sample St', priceCents: 8900, taxCents: 445,
  heldCents: 9345, freeCancelUntil: null, state: 'en_route', timeZone: 'America/Regina',
};
const future = new Date(Date.now() + 3_600_000).toISOString();
const target = (status: string, review: unknown = null) => ({ merchantId: 'M1', merchantName: 'Sample Wrench', slug: 'sample-wrench', jobLabel: 'Brake inspection', status, review });
const posted = { id: 'R1', merchantId: 'M1', rating: 4, tags: ['on_time'], text: 'Explained everything.', screened: false, createdAt: '2026-10-09T18:00:00Z', editUntil: future, editedAt: null, reply: null, hidden: false };

type Reply = { status?: number; body?: unknown } | undefined;
let routes: Record<string, (c: Call) => Reply>;
const server = (c: Call): Reply => {
  if (c.url === '/bff/session') return { body: { user: AMARA, guestId: 'g' } };
  return routes[`${c.method} ${c.url.split('?')[0]}`]?.(c);
};

beforeEach(() => {
  routes = {
    'GET /api/v1/me/bookings/B1': () => ({ body: BOOKING }),
    'GET /api/v1/me/bookings/B1/eta': () => ({ body: { state: 'en_route', sharing: true, minutesAway: 12, kmAway: 5.6, updatedAt: '2026-10-09T15:50:00Z', method: 'straight_line' } }),
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

describe('The visit’s live ETA (design 01 C9 on the web)', () => {
  it('shows minutes away from the straight-line estimate, never a position', async () => {
    openBooking();
    expect(await screen.findByText('Jas is about 12 minutes away · 5.6 km')).toBeInTheDocument();
    expect(screen.getByText('Estimated from the straight-line distance, not the road.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Brake inspection' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body);
  });

  it('says so while the provider isn’t sharing, in French', async () => {
    routes['GET /api/v1/me/bookings/B1/eta'] = () => ({ body: { state: 'en_route', sharing: false, minutesAway: null, kmAway: null, updatedAt: null, method: 'straight_line' } });
    openBooking('fr');
    expect(await screen.findByText('Jas est en route. Les minutes s’afficheront quand sa position sera partagée.')).toBeInTheDocument();
  });
});

describe('Reviews (design 01 C11 · B9)', () => {
  it('posts stars, tags and words for a completed booking, then offers the change for 24 hours', async () => {
    routes['GET /api/v1/me/bookings/B1'] = () => ({ body: { ...BOOKING, state: 'completed' } });
    let context = { kind: 'booking', id: 'B1', ref: 'BK-7801', reviewBy: future, targets: [target('open')] };
    routes['GET /api/v1/me/reviews/booking/B1'] = () => ({ body: context });
    routes['POST /api/v1/me/reviews'] = () => { context = { ...context, targets: [target('reviewed', posted)] }; return { status: 201, body: posted }; };
    const calls = openBooking();
    const u = userEvent.setup();
    expect(await screen.findByRole('heading', { name: 'How was Sample Wrench?' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body);
    const post = screen.getByRole('button', { name: 'Post review' });
    expect(post).toBeDisabled();
    await u.click(screen.getByRole('radio', { name: '4 stars' }));
    await u.click(screen.getByRole('button', { name: 'On time' }));
    await u.type(screen.getByLabelText('Your review (optional)'), 'Explained everything.');
    await u.click(post);
    expect(calls.find(c => c.method === 'POST' && c.url === '/api/v1/me/reviews')!.body)
      .toEqual({ kind: 'booking', id: 'B1', merchantId: 'M1', rating: 4, tags: ['on_time'], text: 'Explained everything.' });
    expect(await screen.findByText('Explained everything.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Change my review' })).toBeInTheDocument();
  });

  it('shows the server’s messages on the fields', async () => {
    routes['GET /api/v1/me/reviews/order/O1'] = () => ({ body: { kind: 'order', id: 'O1', ref: 'NL-1', reviewBy: future, targets: [target('open')] } });
    routes['POST /api/v1/me/reviews'] = () => ({ status: 422, body: { errors: [{ field: 'text', rule: 'length', message: 'Écrivez au moins 10 caractères, ou laissez l’avis vide.' }] } });
    mockFetch(server);
    renderApp('/orders/O1', { locale: 'fr', routes: { confirmed: () => <ReviewPanel kind="order" id="O1" /> } });
    const u = userEvent.setup();
    await u.click(await screen.findByRole('radio', { name: '5 étoiles' }));
    await u.type(screen.getByLabelText('Votre avis (facultatif)'), 'court');
    await u.click(screen.getByRole('button', { name: 'Publier l’avis' }));
    expect(await screen.findByText('Écrivez au moins 10 caractères, ou laissez l’avis vide.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Bien emballé' })).toBeInTheDocument(); // the order's tags
  });

  it('a reply and a hidden review are shown for what they are; a locked review can’t change', async () => {
    routes['GET /api/v1/me/reviews/food/F1'] = () => ({ body: { kind: 'food', id: 'F1', ref: 'FD-1', reviewBy: future, targets: [target('reviewed', { ...posted, editUntil: null, reply: 'Thank you!', hidden: true })] } });
    mockFetch(server);
    renderApp('/food/orders/F1', { routes: { foodTrack: () => <ReviewPanel kind="food" id="F1" /> } });
    expect(await screen.findByText('Reply from Sample Wrench: Thank you!')).toBeInTheDocument();
    expect(screen.getByText('Northline hid this review.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Change my review' })).toBeNull();
  });
});

describe('Tipping the courier after the delivery (design 01 B9)', () => {
  it('sends the tip and confirms it with the local stand-in', async () => {
    let tips = { items: [{ id: 'T0', orderId: 'O1', amountCents: 300, source: 'checkout', state: 'allocated', courierUserId: 'K1', createdAt: '2026-10-09T18:00:00Z' }], canTip: true, courierFirstName: 'Kai' };
    routes['GET /api/v1/me/orders/O1/tips'] = () => ({ body: tips });
    routes['POST /api/v1/me/orders/O1/tips'] = () => ({ status: 201, body: { tip: { id: 'T1', orderId: 'O1', amountCents: 500, source: 'after_delivery', state: 'pending', courierUserId: 'K1', createdAt: '2026-10-09T19:00:00Z', clientSecret: null }, provider: 'fake', publishableKey: null } });
    routes['POST /api/v1/me/orders/O1/tips/T1/confirm'] = () => {
      tips = { ...tips, canTip: false, items: [...tips.items, { id: 'T1', orderId: 'O1', amountCents: 500, source: 'after_delivery', state: 'allocated', courierUserId: 'K1', createdAt: '2026-10-09T19:00:00Z' }] };
      return { body: tips.items[1] };
    };
    const calls = mockFetch(server);
    renderApp('/orders/O1', { routes: { confirmed: () => <CourierTip orderId="O1" /> } });
    const u = userEvent.setup();
    expect(await screen.findByRole('heading', { name: 'Tip Kai' })).toBeInTheDocument();
    expect(screen.getByText('You tipped $3.00 at checkout.')).toBeInTheDocument();
    await expectNoAxeViolations(document.body);
    await u.click(screen.getByRole('radio', { name: '$5' }));
    await u.click(screen.getByRole('button', { name: 'Tip $5.00' }));
    expect(await screen.findByText('Thanks — $5.00 goes to your courier.')).toBeInTheDocument();
    const start = calls.find(c => c.method === 'POST' && c.url === '/api/v1/me/orders/O1/tips')!;
    expect(start.body).toEqual({ kind: 'amount', value: 500 });
    expect(start.headers['idempotency-key']).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('button', { name: /^Tip \$/ })).toBeNull());
  });
});
