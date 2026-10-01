import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { CombosScreen } from './CombosScreen';
import { HoursScreen } from './HoursScreen';
import { LiveOrdersScreen } from './LiveOrdersScreen';
import { MenuBuilderScreen } from './MenuBuilderScreen';
import { parseDollars, rangeErrors, whereText } from './model';
import type { Ticket } from './api';

let role = 'owner';
vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => role, useMerchant: () => ({ id: 'm1', displayName: 'Pho Dau Bo', type: 'kitchen', role }) }));
vi.mock('@tanstack/react-router', async orig => ({ ...(await orig<typeof import('@tanstack/react-router')>()), Link: ({ children }: { children: ReactNode }) => <a href="#x">{children}</a> }));

const B = '/api/v1/merchants/m1';
const inMin = (n: number) => new Date(Date.now() + n * 60_000).toISOString();
const ticket = (id: string, ref: string, stage: Ticket['stage'], extra: Partial<Ticket> = {}): Ticket => ({
  orderId: id, ref, customerName: 'A. Osei', groupSize: 0, placedAt: inMin(-6), scheduledFor: null, stage,
  lines: [{ qty: 1, title: 'Pho dac biet', modifiers: ['Large', 'extra beef'] }, { qty: 2, title: 'Spring rolls', modifiers: [] }],
  fulfilmentMode: 'delivery', handoff: { party: 'courier', state: 'assigned', name: null, eta: null }, readyBy: stage === 'cooking' ? inMin(14) : null, ...extra,
});
const board = (items: Ticket[], extra: object = {}) => ({ items, counts: { open: items.length, fresh: items.filter(i => i.stage === 'new').length, cooking: 0, ready: 0 }, prep: { defaultPrepMin: 25, bumpMin: 0, shownMin: 25 }, pausedUntil: null, ...extra });

afterEach(() => { vi.unstubAllGlobals(); role = 'owner'; });

describe('model', () => {
  it('parses dollars, checks ranges and describes the handoff', () => {
    expect(parseDollars('$17.50')).toBe(1750);
    expect(parseDollars('17,5')).toBe(1750);
    expect(parseDollars('abc')).toBeUndefined();
    const t = ((k: string, p?: Record<string, unknown>) => `${k}${p ? JSON.stringify(p) : ''}`) as never;
    expect(rangeErrors([['11:00', '14:00'], ['13:00', '20:00'], ['21:00', '20:00'], ['9am', '5pm']], t)).toEqual({ 1: 'v_overlap', 2: 'v_endAfter', 3: 'v_timeFormat' });
    expect(whereText(ticket('o', 'FD', 'new', { handoff: { party: 'courier', state: 'waiting', name: 'Sam', eta: null } }), t, 'en')).toBe('w_waiting{"name":"Sam"}');
    expect(whereText(ticket('o', 'FD', 'new', { fulfilmentMode: 'pickup', handoff: { party: 'customer', state: 'arriving', name: null, eta: inMin(5) } }), t, 'en')).toMatch(/^w_pickup_eta\{"n":[45]\}$/);
  });
});

describe('Live orders', () => {
  it('shows tickets and moves one from New to Cooking', async () => {
    let accepted = false;
    const calls = mockFetch({
      [`GET ${B}/kitchen/live`]: () => board([ticket('o1', 'FD-9931', accepted ? 'cooking' : 'new'), ticket('o2', 'FD-9925', 'ready', { handoff: { party: 'courier', state: 'waiting', name: 'Sam', eta: null } })]),
      [`POST ${B}/kitchen/live/o1/accept`]: () => { accepted = true; return board([ticket('o1', 'FD-9931', 'cooking'), ticket('o2', 'FD-9925', 'ready')]); },
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<LiveOrdersScreen />);
    expect(await screen.findByText('FD-9931')).toBeTruthy();
    expect(screen.getByRole('heading', { level: 1 }).textContent).toMatch(/· 2 open$/);
    expect(screen.getAllByText('1× Pho dac biet · Large, extra beef')).toHaveLength(2);
    expect(screen.getByText('Deliver · Sam waiting')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Handed to courier' })).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Accept · start cooking' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/o1/accept'))).toBe(true));
    expect(await screen.findByRole('button', { name: 'Mark ready' })).toBeTruthy();
    expect(screen.getByText(/min left/)).toBeTruthy();
  });

  it('bumps prep and pauses', async () => {
    const calls = mockFetch({
      [`GET ${B}/kitchen/live`]: () => board([]),
      [`POST ${B}/kitchen/prep-bump`]: () => board([], { prep: { defaultPrepMin: 25, bumpMin: 5, shownMin: 30 } }),
      [`POST ${B}/kitchen/pause`]: () => board([], { pausedUntil: inMin(30) }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<LiveOrdersScreen />);
    expect(await screen.findByText(/No open orders right now/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Busy · +5 min' }));
    expect(await screen.findByText('30 min (+5 busy)')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Pause new orders' }));
    expect(await screen.findByRole('button', { name: 'Paused · resume' })).toBeTruthy();
    expect(screen.getByText('Paused.')).toBeTruthy();
    expect(calls.filter(c => c.method === 'POST')).toHaveLength(2);
  });

  it('says when late orders auto-paused the kitchen (S-67)', async () => {
    mockFetch({ [`GET ${B}/kitchen/live`]: () => board([ticket('o1', 'FD-1', 'cooking', { readyBy: inMin(-8) })], { autoPause: { lateOrders: 3, threshold: 3, active: true } }) });
    renderWithProviders(<LiveOrdersScreen />);
    expect(await screen.findByText('Auto-paused.')).toBeTruthy();
    expect(screen.getByText(/3 orders are past the ready-by time \(your limit is 3\)/)).toBeTruthy();
  });

  it('bookkeepers see the board without actions; errors offer retry', async () => {
    role = 'bookkeeper';
    mockFetch({ [`GET ${B}/kitchen/live`]: () => board([ticket('o1', 'FD-1', 'new')]) });
    const { unmount } = renderWithProviders(<LiveOrdersScreen />);
    expect(await screen.findByText('FD-1')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Accept/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Pause/ })).toBeNull();
    unmount();
    mockFetch({});
    renderWithProviders(<LiveOrdersScreen />);
    expect(await screen.findByRole('button', { name: /Retry/i })).toBeTruthy();
  });
});

const item = (id: string, name: string, extra: object = {}) => ({
  id, menuId: 'mn1', sectionId: 's1', name, description: 'Rare beef', priceCents: 1700, allergens: [], dietary: ['gluten_free'], prepAddMin: 0, dailyLimit: null, soldToday: 0,
  soldOut: false, availability: 'always', comboEligible: true, modifierGroups: [{ id: 'g1', name: 'Size' }], status: 'published', visibility: 'live', hasPhoto: false, updatedAt: null, ...extra,
});
const menuDetail = () => ({ id: 'mn1', name: 'Dinner menu', status: 'live', schedule: { mode: 'open_hours', days: [] }, publishedAt: null, kitchenApproved: true,
  sections: [{ id: 's1', name: 'Mains', sort: 0, items: [item('i1', 'Pho dac biet'), item('i2', 'Fresh lime soda', { soldOut: true, allergens: ['milk'] })] }, { id: 's2', name: 'Drinks', sort: 1, items: [] }] });
const menus = () => ({ items: [{ id: 'mn1', name: 'Dinner menu', status: 'live', schedule: { mode: 'open_hours', days: [] }, sort: 0, sections: [{ id: 's1', name: 'Mains', sort: 0, itemCount: 2 }] }] });
const group = { id: 'g1', name: 'Size', pickRule: 'exactly', pickCount: 1, required: true, minSelect: 1, maxSelect: 1, showForOptionIds: [], options: [{ id: 'o1', name: 'Regular', priceDeltaCents: 0, isDefault: true, soldOut: false }, { id: 'o2', name: 'Large', priceDeltaCents: 300, isDefault: false, soldOut: false }], usedBy: 3 };

describe('Menu builder', () => {
  it('lists sections and items, and toggles sold out', async () => {
    const calls = mockFetch({
      [`GET ${B}/menus/mn1`]: () => menuDetail(),
      [`GET ${B}/menus`]: () => menus(),
      [`GET ${B}/modifier-groups`]: () => ({ items: [group] }),
      [`POST ${B}/menu-items/i1/sold-out`]: () => item('i1', 'Pho dac biet', { soldOut: true }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<MenuBuilderScreen />);
    expect(await screen.findByText('Pho dac biet')).toBeTruthy();
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Dinner menu · live');
    expect(screen.getByText('Sold out today')).toBeTruthy();
    expect(screen.getAllByText('Contains milk').length).toBe(1);
    expect(screen.getAllByText(/Modifiers: Size/).length).toBe(2);
    const avail = screen.getByRole('button', { name: 'Available' });
    await user.click(avail);
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/i1/sold-out'))?.body).toEqual({ soldOut: true }));
  });

  it('validates the item editor, maps server errors and saves', async () => {
    let fail = true;
    const calls = mockFetch({
      [`GET ${B}/menus/mn1`]: () => menuDetail(),
      [`GET ${B}/menus`]: () => menus(),
      [`GET ${B}/modifier-groups`]: () => ({ items: [group] }),
      [`POST ${B}/menu-items`]: () => {
        if (fail) { fail = false; throw { status: 422, body: { errors: [{ field: 'priceCents', rule: 'range', message: 'Enter a price.' }] } }; }
        return item('i9', 'Com tam');
      },
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<MenuBuilderScreen />);
    await user.click(await screen.findByRole('button', { name: 'Add item to Drinks' }));
    expect(screen.getByRole('heading', { name: 'New item' })).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Save & publish' }));
    expect(screen.getByText('3 things need attention.')).toBeTruthy();
    expect(screen.getByText('Enter a name.')).toBeTruthy();
    expect(screen.getByText('Enter a price.')).toBeTruthy();
    expect(screen.getByText('Declare allergens, or choose None.')).toBeTruthy();
    await user.type(screen.getByLabelText('Name'), 'Com tam');
    await user.type(screen.getByLabelText('Base price'), '16.50');
    await user.click(screen.getByRole('button', { name: 'Fish' }));
    await user.click(screen.getByRole('button', { name: '+ Size' }));
    await user.click(screen.getByRole('button', { name: 'Save & publish' }));
    expect(await screen.findByText('Enter a price.')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Save as draft' }));
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'New item' })).toBeNull());
    const posts = calls.filter(c => c.method === 'POST' && c.url.endsWith('/menu-items'));
    expect(posts.at(-1)?.body).toMatchObject({ menuId: 'mn1', sectionId: 's2', name: 'Com tam', priceCents: 1650, allergens: ['fish'], modifierGroupIds: ['g1'], publish: false });
  });

  it('holds an outlier price until the owner keeps it (S-67)', async () => {
    let confirmed = false;
    const flagged = () => item('i1', 'Pho dac biet', { priceCents: 2300, visibility: confirmed ? 'live' : 'price_check', priceCheck: { medianCents: 1500, deviationPct: 53, confirmed } });
    const detail = () => ({ ...menuDetail(), sections: [{ id: 's1', name: 'Mains', sort: 0, items: [flagged()] }] });
    const calls = mockFetch({
      [`GET ${B}/menus/mn1`]: () => detail(),
      [`GET ${B}/menus`]: () => menus(),
      [`GET ${B}/modifier-groups`]: () => ({ items: [group] }),
      [`POST ${B}/menu-items/i1/confirm-price`]: () => { confirmed = true; return flagged(); },
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<MenuBuilderScreen />);
    expect(await screen.findByText('Price check')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Edit Pho dac biet' }));
    expect(screen.getByText(/\$23\.00 is 53 % above similar dishes nearby \(median \$15\.00\)/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Keep this price' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/i1/confirm-price'))).toBe(true));
  });

  it('bookkeepers get a read-only builder', async () => {
    role = 'bookkeeper';
    mockFetch({ [`GET ${B}/menus/mn1`]: () => menuDetail(), [`GET ${B}/menus`]: () => menus(), [`GET ${B}/modifier-groups`]: () => ({ items: [] }) });
    renderWithProviders(<MenuBuilderScreen />);
    expect(await screen.findByText('Pho dac biet')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Add section' })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Edit/ })).toBeNull();
  });
});

describe('Modifiers & combos', () => {
  const combo = { id: 'c1', name: 'Pho for two', rule: 'Any 2 mains + 2 drinks', slots: [], pricing: 'fixed', priceCents: 5200, discountPct: null, referenceCents: 5800, savingCents: 600, schedule: null, status: 'live', swapsAllowed: true };
  it('shows groups with rules and combos with savings; owners toggle promos', async () => {
    const calls = mockFetch({
      [`GET ${B}/modifier-groups`]: () => ({ items: [group] }),
      [`GET ${B}/combos`]: () => ({ items: [combo] }),
      [`GET ${B}/kitchen/promos`]: () => ({ items: [{ promo: 'points_3x', enabled: true }, { promo: 'first_order_5', enabled: false }] }),
      [`PUT ${B}/kitchen/promos/first_order_5`]: () => ({ promo: 'first_order_5', enabled: true }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<CombosScreen />);
    expect(await screen.findByText('Pick exactly 1 · required')).toBeTruthy();
    expect(screen.getByText('Used by 3 items')).toBeTruthy();
    expect(screen.getByText('Large · +$3')).toBeTruthy();
    expect((await screen.findAllByText('Pho for two')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('Save $6').length).toBeGreaterThan(0);
    expect(screen.getByText('On · $0.02/pt')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Turn on' }));
    await waitFor(() => expect(calls.find(c => c.method === 'PUT')?.body).toEqual({ enabled: true }));
  });

  it('validates a new modifier group', async () => {
    mockFetch({ [`GET ${B}/modifier-groups`]: () => ({ items: [] }), [`GET ${B}/combos`]: () => ({ items: [] }), [`GET ${B}/kitchen/promos`]: () => ({ items: [] }) });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<CombosScreen />);
    await user.click((await screen.findAllByRole('button', { name: 'New group' }))[0]!);
    const dialog = screen.getByRole('dialog');
    await user.clear(within(dialog).getByLabelText('How many'));
    await user.type(within(dialog).getByLabelText('How many'), '3');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(within(dialog).getByText('Enter a group name.')).toBeTruthy();
    expect(within(dialog).getByText('Enter an option name.')).toBeTruthy();
    expect(within(dialog).getByText("There aren't enough options to pick that many.")).toBeTruthy();
  });

  it('cooks cannot change promos', async () => {
    role = 'cook';
    mockFetch({ [`GET ${B}/modifier-groups`]: () => ({ items: [] }), [`GET ${B}/combos`]: () => ({ items: [] }), [`GET ${B}/kitchen/promos`]: () => ({ items: [{ promo: 'first_order_5', enabled: false }] }) });
    renderWithProviders(<CombosScreen />);
    expect(await screen.findByText('Only the owner can change promos.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Turn on' })).toBeNull();
  });
});

describe('Hours, prep & capacity', () => {
  const setup = () => ({
    prep: { defaultPrepMin: 25, bumpMin: 0, shownMin: 25, maxOrdersPer15: 6, largeOrderCents: 12000, largeOrderAddMin: 15, autoPauseLate: 3 }, pausedUntil: null,
    fulfilment: { courier: true, pickup: true, pickupFromMin: 15, pickupToMin: 20, mealKits: false, scheduled: true, scheduledDays: 7, groupOrders: true, groupMax: 12, radiusKm: 6, areas: ['Beltline', 'Kensington'] },
    hours: [1, 2, 3, 4, 5, 6, 7].map(w => ({ weekday: w, ranges: w === 1 ? [] : [['11:00', '21:00']], note: w === 2 ? 'lunch special 11–2' : null })),
    holidays: [], menus: [{ menuId: 'mn2', name: 'Lunch menu', status: 'live', schedule: { mode: 'window', days: [2, 3, 4, 5], from: '11:00', to: '14:00' } }],
    foodSafety: { permit: { reference: 'FS-2024-88120', status: 'verified', expiresAt: '2027-03-31T06:00:00Z' }, handlers: { reference: '3 staff', status: 'verified', expiresAt: null }, inspection: { reference: null, status: null, expiresAt: null } },
  });
  it('shows hours, schedules, fulfilment and food safety; saves prep on change', async () => {
    const calls = mockFetch({ [`GET ${B}/kitchen/setup`]: () => setup(), [`PUT ${B}/kitchen/prep`]: () => setup() });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<HoursScreen />);
    expect(await screen.findByText('Closed')).toBeTruthy();
    expect(screen.getAllByText('11:00 am – 9:00 pm')).toHaveLength(6);
    expect(screen.getByText('lunch special 11–2')).toBeTruthy();
    expect(screen.getByText('Tue–Fri 11:00–2:00')).toBeTruthy();
    expect(screen.getByText('On · 15–20 min')).toBeTruthy();
    expect(screen.getByText('6 km · Beltline, Kensington')).toBeTruthy();
    expect(screen.getByText('AHS food permit · #FS-2024-88120')).toBeTruthy();
    expect(screen.getByText('Verified · renews Mar 2027')).toBeTruthy();
    await user.selectOptions(screen.getByLabelText('Default prep time'), '30');
    await waitFor(() => expect(calls.find(c => c.method === 'PUT')?.body).toEqual({ defaultPrepMin: 30, maxOrdersPer15: 6, largeOrderCents: 12000, autoPauseLate: 3 }));
  });

  it('validates opening hours before saving', async () => {
    mockFetch({ [`GET ${B}/kitchen/setup`]: () => setup() });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<HoursScreen />);
    await user.click(await screen.findByRole('button', { name: 'Edit hours' }));
    const dialog = screen.getByRole('dialog');
    const opens = within(dialog).getAllByLabelText('Opens');
    await user.clear(opens[0]!);
    await user.type(opens[0]!, '22:00');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(within(dialog).getByText('End time must be after start time.')).toBeTruthy();
    expect(within(dialog).getByText('1 thing needs attention.')).toBeTruthy();
  });
});
