import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import type { ProviderFacts, Storefront } from '../provider/api';
import { BookingWizard, complete, nextSteps, stepsFor, weekSpan, type Step } from './BookingWizard';
import { EMPTY, toRequest, type Draft } from './draft';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const user = () => userEvent.setup({ delay: null });
const Book = () => {
  const { slug } = useParams({ strict: false }) as { slug: string };
  const search = useSearch({ strict: false }) as { step?: Step; booking?: string };
  const navigate = useNavigate();
  const onStep = (step: Step, extra?: { booking?: string }) =>
    void navigate({ to: '/providers/$slug/book', params: { slug }, search: { step, booking: extra?.booking } } as never);
  return <BookingWizard slug={slug} step={search.step ?? 'details'} bookingId={search.booking} onStep={onStep} />;
};
const routes = { book: Book };

const AMARA = { id: '01J9ZD3V00000000000000AMA1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', initials: 'AO', locale: 'en-CA' };
const page: Storefront = {
  slug: 'prairie-wrench', url: 'northline.ca/prairie-wrench', pageKind: 'business_page', brandColor: '#2f5d3a', logoUrl: null,
  tagline: null, ctaLabel: 'book_visit', announcement: null, customDomain: null, publishedAt: '2026-01-08T16:00:00Z', sections: [],
  business: { displayName: 'Prairie Wrench', type: 'provider', tier: 'master', city: 'Calgary', about: null, serviceArea: null, verifiedFacts: [] },
};
const facts: ProviderFacts = {
  merchantId: 'm1', slug: 'prairie-wrench', name: 'Prairie Wrench', tier: 'master', city: 'Calgary', since: '2019-05-01T00:00:00Z',
  verifiedFacts: ['kyc'], rating: 4.9, reviewCount: 312, onTimePct: 98, disputePct: 0.3, rebookPct: 71,
  kind: 'visit', category: { id: 'service.automotive.mobile-mechanic', slug: 'mobile-mechanic', names: { en: 'Mobile mechanic' } }, vehicle: true, quoteable: true,
  services: [
    { id: 's1', name: 'Brake inspection', included: null, pricingMode: 'fixed', priceCents: 8900, durationMin: 60, instantBook: true, categorySlug: 'brakes-and-suspension', kind: 'visit' },
    { id: 's2', name: 'Alternator', included: null, pricingMode: 'quote', priceCents: null, durationMin: 90, instantBook: false, categorySlug: 'mobile-mechanic', kind: 'visit' },
  ],
  zones: ['Beltline'], nextAvailable: null, taxBps: 500, reviews: { items: [], nextOffset: null },
};
const addDays = (iso: string, n: number) => { const d = new Date(`${iso}T12:00:00Z`); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };
const calendar = (from: string) => ({
  serviceId: 's1', durationMin: 60,
  days: Array.from({ length: 7 }, (_, i) => {
    const date = addDays(from, i);
    return { date, closed: null, free: 1, slots: [{ startsAt: `${date}T16:00:00Z`, free: true }, { startsAt: `${date}T17:00:00Z`, free: false }] };
  }),
});
const confirmation = {
  bookingId: 'b1', ref: 'BK-1042', providerName: 'Prairie Wrench', providerSlug: 'prairie-wrench', memberFirstName: 'Kai', title: 'Brake inspection',
  type: 'visit', startsAt: '2026-10-06T16:00:00Z', endsAt: '2026-10-06T17:00:00Z', addressLine: '1204 17 Ave SW', priceCents: 8900, taxCents: 445, heldCents: 9345, freeCancelUntil: '2026-10-06T04:00:00Z',
};
type Reply = { status?: number; body?: unknown } | undefined;

function api(signedIn: boolean, extra: (c: Call) => Reply = () => undefined) {
  return (c: Call): Reply => {
    if (c.url === '/bff/session') return { body: { user: signedIn ? AMARA : null, guestId: 'g_x', sid: signedIn ? 's1' : undefined } };
    const own = extra(c);
    if (own) return own;
    if (c.url === '/api/v1/storefronts/prairie-wrench') return { body: page };
    if (c.url.startsWith('/api/v1/public/providers/prairie-wrench/slots')) return { body: calendar(new URL(c.url, 'http://x').searchParams.get('from')!) };
    if (c.url.startsWith('/api/v1/public/providers/prairie-wrench')) return { body: facts };
    if (c.url === '/api/v1/me/bookings/holds' && c.method === 'POST') {
      const at = (c.body as { startsAt: string }).startsAt;
      return { status: 201, body: { holdId: 'h1', bookingId: 'b1', startsAt: at, endsAt: at, expiresAt: new Date(Date.now() + 600_000).toISOString() } };
    }
    if (c.url === '/api/v1/me/bookings/b1') return { body: confirmation };
    return undefined;
  };
}

/** A draft through the location step, as the wizard keeps it in sessionStorage. */
const filled: Draft = {
  ...EMPTY, serviceId: 's1', description: 'Grinding noise when braking.', vehicle: { year: '2018', make: 'Honda', model: 'Civic', plate: '' },
  addressLine: '1204 17 Ave SW', spot: 2, accessNote: 'Stall P2-114, buzz 1204', contactPhone: '+1 403 555 0123',
};
const prefill = (d: Draft) => sessionStorage.setItem('nl.book.prairie-wrench', JSON.stringify(d));

/** The pay button, once the draft (and its hold) has loaded from sessionStorage. */
async function payButton() {
  const button = await screen.findByRole('button', { name: 'Hold $93.45 in escrow' });
  await waitFor(() => expect(button).toBeEnabled());
  return button;
}

async function pickSlot(u: ReturnType<typeof user>) {
  const days = await screen.findByRole('group', { name: 'Schedule' });
  await u.click(within(days).getAllByRole('button')[0]!);
  const slots = await screen.findByRole('group', { name: /^Times on/ });
  const [free, taken] = within(slots).getAllByRole('button');
  expect(taken).toBeDisabled();
  await u.click(free!);
}

afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear(); });

describe('booking wizard (design 06 book)', () => {
  it('walks a guest through job details and location, shows the live calendar, and asks them to sign in to hold a time', async () => {
    const calls = mockFetch(api(false));
    renderApp('/providers/prairie-wrench/book', { routes });
    const u = user();
    expect(await screen.findByRole('heading', { level: 1, name: 'What do you need done?' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body); // S-109
    const next = screen.getByRole('button', { name: 'Continue to location' });
    expect(next).toBeDisabled();
    await u.type(screen.getByLabelText(/Describe the problem/), 'Grinding noise when braking.');
    await u.selectOptions(screen.getByLabelText('Year'), '2018');
    await u.selectOptions(screen.getByLabelText('Make'), 'Honda');
    await u.type(screen.getByLabelText('Model'), 'Civic');
    expect(next).toBeEnabled();
    await u.click(next);

    expect(await screen.findByRole('heading', { level: 1, name: 'Where and how do we get in?' })).toBeInTheDocument();
    await u.type(screen.getByLabelText('Street address'), '1204 17 Ave SW');
    await u.click(screen.getByRole('button', { name: 'Underground parkade' }));
    await u.type(screen.getByLabelText('Access instructions'), 'Stall P2-114');
    await u.click(screen.getByRole('button', { name: 'Continue to schedule' }));

    await pickSlot(u);
    const prompt = screen.getByText('Sign in to hold this time and pay — what you entered is kept.').parentElement!;
    expect(within(prompt).getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/sign-in?next=%2Fproviders%2Fprairie-wrench%2Fbook%3Fstep%3Dschedule');
    expect(within(prompt).getByRole('link', { name: 'Create account' })).toHaveAttribute('href', '/register?next=%2Fproviders%2Fprairie-wrench%2Fbook%3Fstep%3Dschedule');
    expect(screen.getByRole('button', { name: 'Continue to payment' })).toBeDisabled();
    expect(calls.some(c => c.url.includes('/slots?serviceId=s1&from='))).toBe(true);
    expect(JSON.parse(sessionStorage.getItem('nl.book.prairie-wrench')!).accessNote).toBe('Stall P2-114');
  });

  it('holds the slot, pays the escrow hold with an Idempotency-Key and shows the confirmation', async () => {
    prefill(filled);
    const calls = mockFetch(api(true, c => (c.url === '/api/v1/me/bookings/checkout'
      ? { body: { holdId: 'h1', bookingId: 'b1', priceCents: 8900, taxCents: 445, totalCents: 9345, status: 'authorized', provider: 'fake', booking: confirmation } }
      : undefined)));
    renderApp('/providers/prairie-wrench/book?step=schedule', { routes });
    const u = user();
    await pickSlot(u);
    await u.click(screen.getByRole('button', { name: 'Continue to payment' }));

    const hold = calls.find(c => c.url === '/api/v1/me/bookings/holds')!;
    expect(hold.body).toMatchObject({ slug: 'prairie-wrench', serviceId: 's1' });
    const pay = await screen.findByRole('button', { name: 'Hold $93.45 in escrow' });
    expect(pay).toBeDisabled();
    await u.click(screen.getByRole('checkbox', { name: /cancellation is free/ }));
    await u.click(screen.getByRole('checkbox', { name: /Northline terms/ }));
    await u.click(pay);

    expect(await screen.findByRole('heading', { level: 1, name: /^Booked\. Kai is coming/ })).toBeInTheDocument();
    expect(screen.getByText(/BK-1042/)).toBeInTheDocument();
    const checkout = calls.find(c => c.url === '/api/v1/me/bookings/checkout')!;
    expect(checkout.headers['idempotency-key']).toBeTruthy();
    expect(checkout.body).toMatchObject({ holdId: 'h1', serviceId: 's1', accessNote: 'Stall P2-114, buzz 1204', spot: 'Underground parkade', agreePolicies: true, agreeTerms: true });
    expect(sessionStorage.getItem('nl.book.prairie-wrench')).toBeNull();
  });

  it('tells the customer when the slot was just taken and reloads the calendar', async () => {
    prefill(filled);
    const calls = mockFetch(api(true, c => (c.url === '/api/v1/me/bookings/holds' ? { status: 409, body: { code: 'slot_taken', detail: 'taken' } } : undefined)));
    renderApp('/providers/prairie-wrench/book?step=schedule', { routes });
    const u = user();
    await pickSlot(u);
    await u.click(screen.getByRole('button', { name: 'Continue to payment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('That time was just taken. Pick another slot.');
    await waitFor(() => expect(calls.filter(c => c.url.includes('/slots?')).length).toBeGreaterThan(1));
    expect(screen.getByRole('button', { name: 'Continue to payment' })).toBeDisabled();
  });

  it('asks an account without a second factor to add a passkey (S-51 rule), and resends the same Idempotency-Key', async () => {
    prefill({ ...filled, startsAt: '2026-10-06T16:00:00Z', agreePolicies: true, agreeTerms: true, hold: { holdId: 'h1', bookingId: 'b1', startsAt: '2026-10-06T16:00:00Z', expiresAt: '2026-10-06T15:00:00Z' } });
    const calls = mockFetch(api(true, c => (c.url === '/api/v1/me/bookings/checkout' ? { status: 403, body: { code: 'second_factor_required', detail: 'Add a passkey to pay' } } : undefined)));
    renderApp('/providers/prairie-wrench/book?step=pay', { routes });
    const u = user();
    await u.click(await payButton());
    expect(await screen.findByRole('dialog', { name: 'Add a passkey to pay' })).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Cancel' }));
    await u.click(screen.getByRole('button', { name: 'Hold $93.45 in escrow' }));
    const keys = calls.filter(c => c.url === '/api/v1/me/bookings/checkout').map(c => c.headers['idempotency-key']);
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it('sends the customer back to the calendar when the 10-minute hold ended', async () => {
    prefill({ ...filled, startsAt: '2026-10-06T16:00:00Z', agreePolicies: true, agreeTerms: true, hold: { holdId: 'h1', bookingId: 'b1', startsAt: '2026-10-06T16:00:00Z', expiresAt: '2026-10-06T15:00:00Z' } });
    mockFetch(api(true, c => (c.url === '/api/v1/me/bookings/checkout' ? { status: 409, body: { code: 'hold_expired', detail: 'expired' } } : undefined)));
    renderApp('/providers/prairie-wrench/book?step=pay', { routes });
    await user().click(await payButton());
    expect(await screen.findByRole('heading', { level: 1, name: 'When suits you?' })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Your 10-minute hold ended. Pick the time again.');
  });

  it('is in French for fr-CA', async () => {
    mockFetch(api(false));
    renderApp('/providers/prairie-wrench/book', { routes, locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'De quoi avez-vous besoin ?' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continuer vers le lieu' })).toBeDisabled();
  });
});

describe('demo rehearsal fixes (2026-10-19)', () => {
  it('shows the confirmation in the business\'s time zone, not the browser\'s, with one full stop after the time', async () => {
    // 19:00Z is 4:00 p.m. in Halifax and 1:00 p.m. in Calgary: the booking's zone wins over the platform's (UTC here)
    mockFetch(api(true, c => (c.url === '/api/v1/me/bookings/b1' ? { body: { ...confirmation, startsAt: '2026-10-20T19:00:00Z', endsAt: '2026-10-20T20:00:00Z', timeZone: 'America/Halifax' } } : undefined)));
    renderApp('/providers/prairie-wrench/book?step=done&booking=b1', { routes });
    const title = await screen.findByRole('heading', { level: 1, name: /^Booked\./ });
    expect(title).toHaveTextContent('Booked. Kai is coming Oct 20, 4:00 p.m.');
    expect(title.textContent).not.toMatch(/\.\.$/);
  });

  it('holds and shows times in the zone of the business\'s calendar', async () => {
    prefill(filled);
    mockFetch(api(true, c => {
      if (c.url.startsWith('/api/v1/public/providers/prairie-wrench/slots')) {
        const body = calendar(new URL(c.url, 'http://x').searchParams.get('from')!);
        return { body: { ...body, timeZone: 'America/Halifax' } };
      }
      if (c.url === '/api/v1/me/bookings/holds' && c.method === 'POST') {
        const at = (c.body as { startsAt: string }).startsAt;
        return { status: 201, body: { holdId: 'h1', bookingId: 'b1', startsAt: at, endsAt: at, expiresAt: '2026-10-20T02:27:00Z' } };
      }
      return undefined;
    }));
    renderApp('/providers/prairie-wrench/book?step=schedule', { routes });
    const u = user();
    const days = await screen.findByRole('group', { name: 'Schedule' });
    await u.click(within(days).getAllByRole('button')[0]!);
    const slots = await screen.findByRole('group', { name: /^Times on/ });
    // 16:00Z in Halifax (UTC−3) is 1 pm
    expect(within(slots).getAllByRole('button')[0]).toHaveTextContent('1 pm');
    await u.click(within(slots).getAllByRole('button')[0]!);
    await u.click(screen.getByRole('button', { name: 'Continue to payment' }));
    // 02:27Z is 11:27 p.m. the evening before in Halifax; one full stop after "p.m."
    expect(await screen.findByText('Held for you until 11:27 p.m.')).toBeInTheDocument();
    expect(JSON.parse(sessionStorage.getItem('nl.book.prairie-wrench')!).timeZone).toBe('America/Halifax');
  });

  it('titles the week with the days it shows: the current week starts today', async () => {
    prefill(filled);
    const calls = mockFetch(api(false));
    renderApp('/providers/prairie-wrench/book?step=schedule', { routes });
    const days = await screen.findByRole('group', { name: 'Schedule' });
    const shown = within(days).getAllByRole('button').map(b => b.querySelector('.nl-bk-dnum')!.textContent);
    const label = screen.getByText(/^This week · /).textContent!;
    const [first, last] = label.replace('This week · ', '').split('–');
    expect(first).toMatch(new RegExp(` ${shown[0]}$`));
    expect(last).toMatch(new RegExp(` ${shown.at(-1)}$`));
    const slots = calls.find(c => c.url.includes('/slots?'))!;
    const q = new URL(slots.url, 'http://x').searchParams;
    // The days come from the mock's fixed calendar; the request asks from today to the week's end (weekSpan, below),
    // which depends on the weekday the suite runs: between 1 and 7 days.
    const asked = Number(q.get('days'));
    expect(asked).toBeGreaterThanOrEqual(1);
    expect(asked).toBeLessThanOrEqual(7);
  });

  it('asks for the rest of this week from today, then whole weeks', () => {
    expect(weekSpan('2026-10-18', '2026-10-18')).toEqual({ from: '2026-10-18', days: 7 }); // Sunday, the week's first day
    expect(weekSpan('2026-10-18', '2026-10-21')).toEqual({ from: '2026-10-21', days: 4 }); // Wednesday → Saturday
    expect(weekSpan('2026-10-25', '2026-10-21')).toEqual({ from: '2026-10-25', days: 7 });
  });

  it('says what happens next by who travels: the provider comes to you, or you go to them', () => {
    expect(nextSteps('visit')).toContain('next_1');
    expect(nextSteps('appointment')).toEqual(['next_0', 'next_1_visit', 'next_2_visit', 'next_3', 'next_4_visit']);
  });

  it('never tells a barber\'s customer about a technician\'s plate (en and fr)', async () => {
    mockFetch(api(true, c => (c.url === '/api/v1/me/bookings/b1' ? { body: { ...confirmation, type: 'appointment', memberFirstName: null, providerName: 'Fade Lab', addressLine: null } } : undefined)));
    const { unmount } = renderApp('/providers/prairie-wrench/book?step=done&booking=b1', { routes });
    const list = await screen.findByRole('list');
    expect(list).toHaveTextContent("Reminder the evening before with Fade Lab's address and directions");
    expect(list).not.toHaveTextContent(/technician|plate|access code/i);
    unmount();
    mockFetch(api(true, c => (c.url === '/api/v1/me/bookings/b1' ? { body: { ...confirmation, type: 'appointment', providerName: 'Fade Lab' } } : undefined)));
    renderApp('/providers/prairie-wrench/book?step=done&booking=b1', { routes, locale: 'fr' });
    expect(await screen.findByText('Rappel la veille avec l’adresse de Fade Lab et l’itinéraire')).toBeInTheDocument();
  });

  it('keeps the vehicle details for a mobile mechanic', async () => {
    mockFetch(api(true));
    renderApp('/providers/prairie-wrench/book?step=done&booking=b1', { routes });
    expect(await screen.findByText("Reminder the evening before with the photo of who's coming and their vehicle")).toBeInTheDocument();
  });
});

describe('booking rules', () => {
  it('skips the location step for appointments', () => {
    expect(stepsFor('appointment')).toEqual(['details', 'schedule', 'pay', 'done']);
    expect(stepsFor('home')).toEqual(['details', 'location', 'schedule', 'pay', 'done']);
  });

  it('needs a 10-character description and the vehicle for a mechanic, and an access note to come in', () => {
    expect(complete('details', { ...filled, description: 'Noise' }, 'visit', true, false)).toBe(false);
    expect(complete('details', { ...filled, vehicle: { ...filled.vehicle, model: ' ' } }, 'visit', true, false)).toBe(false);
    expect(complete('details', filled, 'visit', true, false)).toBe(true);
    expect(complete('location', { ...filled, accessNote: 'ok' }, 'visit', true, false)).toBe(false);
    expect(complete('location', { ...filled, spot: undefined }, 'visit', true, false)).toBe(false);
    expect(complete('location', { ...EMPTY, addressLine: '1 Main St' }, 'event', false, false)).toBe(true);
  });

  it('sends the chips as their stored English values and no vehicle outside a vehicle job', () => {
    const body = toRequest({ ...filled, urgency: 1, present: 0 }, 'h1', 's1', { vehicle: true, kind: 'visit', cleaning: false });
    expect(body).toMatchObject({ urgency: 'This week', present: "Yes, I'll be there", spot: 'Underground parkade', contactPreference: 'In-app message', vehicle: { year: '2018', make: 'Honda', model: 'Civic' } });
    expect(toRequest(filled, 'h1', 's1', { vehicle: false, kind: 'consult', cleaning: false }).vehicle).toBeUndefined();
  });
});
