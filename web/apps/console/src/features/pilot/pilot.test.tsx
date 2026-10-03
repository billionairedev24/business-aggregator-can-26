import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';

const step = (key: string, state: string, action?: string, owner?: string, params: Record<string, string> = {}) => ({ key, state, action: action ?? null, owner: owner ?? null, params });
const CHECKLIST = [
  step('invited', 'done'), step('account_created', 'done'), step('details_complete', 'done'), step('identity_verified', 'done'), step('stripe_ready', 'done'),
  step('kitchen_visit', 'todo', 'schedule_visit', 'northline'), step('catalogue_ready', 'done'), step('approved', 'waiting', 'approve', 'northline'), step('live', 'todo'),
];
const KITCHEN = {
  id: 'P1', label: 'Glenmore Dumplings', businessName: 'Glenmore Dumplings', businessType: 'kitchen', marketId: 'mkt-pilotville', merchantId: 'M1', city: 'Pilotville',
  stage: 'stripe_ready', next: CHECKLIST[5], checklist: CHECKLIST, blocked: false, blocker: null, blockerOwner: null, blockerSince: null, ownerId: null, ownerName: null,
  inviteState: 'accepted', listings: 4, listingsLive: 0, createdAt: '2026-10-01T12:00:00Z',
};
const INVITED = {
  ...KITCHEN, id: 'P2', label: 'Bow River Bakery', businessName: 'Bow River Bakery', businessType: 'seller', merchantId: null, city: null, stage: 'invited',
  next: step('account_created', 'todo', 'accept_invite', 'business', { expiresAt: '2026-10-15T12:00:00Z' }), blocked: true, blocker: 'Owner travelling until Oct 12', blockerOwner: 'business',
  checklist: [step('invited', 'done'), step('account_created', 'todo', 'accept_invite', 'business')], inviteState: 'pending', listings: 0,
};
const BOARD = {
  markets: [{ id: 'mkt-pilotville', city: 'Pilotville', province: 'AB', stage: 'pilot' }], marketId: null,
  stages: { invited: 1, account_created: 0, details_complete: 0, identity_verified: 0, stripe_ready: 1, kitchen_visit: 0, catalogue_ready: 0, approved: 0, live: 0 },
  live: 0, blocked: 1, items: [KITCHEN, INVITED],
};
const ITEMS = ['handwashing', 'temperatures', 'separation', 'sanitation', 'pests', 'allergens', 'permit_displayed', 'food_handler', 'storage', 'waste', 'packaging'];
const VISIT = { id: 'V1', merchantId: 'M1', scheduledAt: '2026-10-05T16:00:00Z', inspectorId: null, inspectorName: 'R. Okafor', status: 'scheduled', checklist: {}, note: null, photoIds: [], recordedBy: null, recordedAt: null };
const DETAIL = { row: KITCHEN, notes: [], invites: [{ id: 'I1', email: 'owner@example.test', createdAt: '2026-10-01T12:00:00Z', expiresAt: '2026-10-15T12:00:00Z', acceptedAt: '2026-10-02T12:00:00Z', state: 'accepted' }],
  visits: [VISIT], visitItems: ITEMS, kitchenVisitRequired: true };

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && /\/api\/v1\/console\/pilot(\?|$)/.test(c.url)) return { body: BOARD };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/pilot/P1')) return { body: DETAIL };
    if (c.method === 'POST' && c.url.endsWith('/api/v1/console/pilot/invites')) {
      return (c.body as { email: string }).email.includes('@')
        ? { status: 201, body: { detail: { ...DETAIL, row: INVITED }, link: 'http://localhost:3100/pilot/tok', expiresAt: '2026-10-15T12:00:00Z' } }
        : { status: 422, body: { errors: [{ field: 'email', rule: 'format', message: 'That doesn’t look like an email address.' }] } };
    }
    if (c.method === 'POST' && c.url.endsWith('/P1/kitchen-visits/V1/outcome')) return { body: { ...DETAIL, visits: [{ ...VISIT, status: 'passed' }] } };
    if (c.method === 'POST' && c.url.endsWith('/P1/notes')) return { body: { ...DETAIL, notes: [{ id: 'N1', authorId: 'U', authorName: 'Priya Natarajan', body: (c.body as { body: string }).body, createdAt: '2026-10-03T12:00:00Z' }] } };
    return undefined;
  });
}
const user = () => userEvent.setup({ delay: null });

describe('pilot onboarding (S-120)', () => {
  it('shows the market pipeline: stage counts, next actions with who acts, blockers, the CSV', async () => {
    api(['merchant_success']);
    renderConsole('/pilot');
    expect(await screen.findByRole('heading', { level: 1, name: '2 pilot businesses · 0 live · 1 blocked' })).toBeTruthy();
    await expectNoAxeViolations(document.body);
    expect(screen.getAllByText('Glenmore Dumplings').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Schedule the kitchen visit · Northline').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Owner travelling until Oct 12').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Business accepts the invite/).length).toBeGreaterThan(0);
    expect(screen.getByText('Invited · 1')).toBeTruthy();
    expect((screen.getByRole('link', { name: 'Export CSV' }) as HTMLAnchorElement).getAttribute('href')).toBe('/api/v1/console/pilot/export');
    expect(screen.getByRole('button', { name: 'Invite a business' })).toBeTruthy();
  });

  it('trust & safety read the board but cannot invite', async () => {
    api(['trust_safety']);
    renderConsole('/pilot');
    expect(await screen.findByRole('heading', { level: 1, name: /2 pilot businesses/ })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Invite a business' })).toBeNull();
  });

  it('invites a business: the server’s message on the field, then the link', async () => {
    const calls = api(['merchant_success']);
    renderConsole('/pilot');
    await user().click(await screen.findByRole('button', { name: 'Invite a business' }));
    const dialog = await screen.findByRole('dialog', { name: 'Invite a business to the pilot' });
    await user().type(within(dialog).getByLabelText('Working name'), 'Bow River Bakery');
    await user().type(within(dialog).getByLabelText('Business email'), 'bad');
    await user().click(within(dialog).getByRole('button', { name: 'Send invite' }));
    expect(await within(dialog).findByText('That doesn’t look like an email address.')).toBeTruthy();
    await user().clear(within(dialog).getByLabelText('Business email'));
    await user().type(within(dialog).getByLabelText('Business email'), 'bakery@example.test');
    await user().click(within(dialog).getByRole('button', { name: 'Send invite' }));
    expect(await screen.findByText(/Invite sent\. .*http:\/\/localhost:3100\/pilot\/tok/)).toBeTruthy();
    const sent = calls.filter(c => c.method === 'POST' && c.url.endsWith('/invites')).at(-1)!;
    expect(sent.body).toMatchObject({ marketId: 'mkt-pilotville', businessType: 'provider', label: 'Bow River Bakery', email: 'bakery@example.test', language: 'en' });
    expect(sent.headers['X-Console-Role']).toBe('merchant_success');
  });

  it('opens a kitchen: checklist, the visit rule, records a visit against every checklist item, adds a note', async () => {
    const calls = api(['merchant_success']);
    renderConsole('/pilot?pilot=P1');
    const drawer = await screen.findByRole('dialog', { name: 'Glenmore Dumplings' });
    await expectNoAxeViolations(document.body);
    expect(within(drawer).getByText('A passed visit is required before approval (region or category rule).')).toBeTruthy();
    expect(within(drawer).getByText(/R\. Okafor · scheduled/)).toBeTruthy();
    await user().click(within(drawer).getByRole('button', { name: 'Record outcome' }));
    const outcome = await screen.findByRole('dialog', { name: 'Kitchen visit outcome' });
    expect(within(outcome).getByLabelText('Hand-wash sink stocked (soap, paper towel, hot water)')).toBeTruthy();
    await user().click(within(outcome).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/P1/kitchen-visits/V1/outcome'))).toBe(true));
    const sent = calls.find(c => c.url.endsWith('/P1/kitchen-visits/V1/outcome'))!;
    expect(Object.keys((sent.body as { checklist: Record<string, string> }).checklist)).toEqual(ITEMS);
    expect(sent.body).toMatchObject({ outcome: 'passed' });

    await user().type(within(drawer).getByLabelText('New note'), 'Called the owner.');
    await user().click(within(drawer).getByRole('button', { name: 'Add note' }));
    expect(await within(drawer).findByText('Called the owner.')).toBeTruthy();
  });

  it('speaks French', async () => {
    api(['merchant_success']);
    renderConsole('/pilot', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: '2 entreprises pilotes · 0 en ligne · 1 bloquées' })).toBeTruthy();
    expect(screen.getAllByText('Planifier la visite de cuisine · Northline').length).toBeGreaterThan(0);
  });
});
