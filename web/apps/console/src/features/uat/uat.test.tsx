import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { renderConsole, staffApi, type Call, screenReady } from '../../test/render';

type Fake = { [k: string]: unknown; id: string; reference: string; state: string; blocking: boolean | null; summary: string; duplicates: number };
const ITEM: Fake = {
  id: 'F1', reference: 'UAT-1001', state: 'new', blocking: null, category: 'bug', severity: 'blocker', persona: 'customer', participant: 'Pilot customer 1',
  app: 'consumer', summary: 'The Pay button stays grey.', route: '/checkout', ownerId: null, ownerName: null, trackerUrl: null, duplicateOf: null,
  duplicates: 0, screenshot: true, createdAt: '2026-10-02T15:00:00Z', updatedAt: '2026-10-02T15:00:00Z',
};
const DUP: Fake = { ...ITEM, id: 'F2', reference: 'UAT-1002', participant: 'Pilot customer 2', summary: 'Le bouton Payer reste gris.', screenshot: false };
const detail = (item: Fake, next: string[], extra: object = {}) => ({
  item, body: `${item.summary}\nAfter choosing the delivery time.`, appVersion: '2026.10.1', locale: 'en-CA', platform: 'Firefox 131 · macOS',
  merchantId: null, next, duplicates: [], duplicateOfItem: null, history: [], ...extra,
});
const NEXT: Record<string, string[]> = { new: ['triaged', 'wont_fix', 'duplicate'], triaged: ['accepted', 'wont_fix', 'duplicate'], accepted: ['accepted', 'fixed', 'wont_fix', 'duplicate'], fixed: ['verified', 'accepted'], verified: ['closed', 'accepted'], closed: ['triaged'] };
const REPORT = {
  generatedAt: '2026-10-03T12:00:00Z', verdict: 'no_go',
  reasons: [{ code: 'blocking_open', count: 1, persona: null, text: '1 blocking item(s) not fixed yet.' }, { code: 'no_participants', count: 0, persona: 'courier', text: 'Courier: no pilot participant yet.' }],
  blockingOpen: 1, blockingUnverified: 0, untriagedBlockers: 0,
  blockingItems: [{ id: 'F1', reference: 'UAT-1001', state: 'accepted', persona: 'customer', app: 'consumer', summary: 'The Pay button stays grey.', ownerName: 'Priya Natarajan', trackerUrl: 'https://tracker.example.com/NL-1', reportedAt: '2026-10-02T15:00:00Z', reports: 2 }],
  coverage: [
    { persona: 'customer', script: 'customer', scriptTitle: 'Customer', scriptVersion: '1.0', participants: 2, signedOff: 1, withComments: 0, blocked: 1, pending: 0, complete: false },
    { persona: 'courier', script: 'courier', scriptTitle: 'Courier', scriptVersion: '1.0', participants: 0, signedOff: 0, withComments: 0, blocked: 0, pending: 0, complete: false },
  ],
  trend: [{ date: '2026-10-02', reported: 2, openBlocking: 0, resolved: 0 }, { date: '2026-10-03', reported: 0, openBlocking: 1, resolved: 0 }],
};
const PARTICIPANT = {
  id: 'P1', persona: 'customer', label: 'Pilot customer 1', who: 'user', merchantId: null, active: true, since: '2026-09-20T12:00:00Z', feedbackCount: 1,
  signoffs: [{ script: 'customer', scriptTitle: 'Customer', scriptVersion: '1.0', outcome: 'pending', comments: null, blockingRefs: [], recordedByName: null, recordedAt: null, history: 0 }],
};

/** A small fake of the api's triage flow, so the test walks the same states the server enforces. */
function api(roles: Parameters<typeof staffApi>[0]) {
  const state = { item: { ...ITEM }, dupOf: '' };
  return staffApi(roles, (c: Call) => {
    const view = () => ({ body: detail(state.item, NEXT[state.item.state] ?? [], state.item.duplicates ? { duplicates: [{ ...DUP, state: 'duplicate', duplicateOf: 'F1' }] } : {}) });
    if (c.method === 'GET' && c.url.includes('/api/v1/console/uat/feedback?')) return { body: { items: [state.item, ...(state.dupOf ? [] : [DUP])] } };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/uat/feedback/F1')) return view();
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/uat/owners')) return { body: { items: [{ id: 'S1', name: 'Priya Natarajan' }] } };
    if (c.method === 'POST' && c.url.endsWith('/F1/moves')) {
      const b = c.body as { to: string; blocking?: boolean };
      if (b.to === 'accepted' && b.blocking === undefined) return { status: 422, body: { errors: [{ field: 'blocking', rule: 'required', message: 'Say whether it blocks the launch.' }] } };
      state.item = { ...state.item, state: b.to, blocking: b.blocking ?? state.item.blocking };
      return view();
    }
    if (c.method === 'POST' && c.url.endsWith('/F2/moves')) { state.dupOf = 'F1'; state.item = { ...state.item, duplicates: 1 }; return { body: detail({ ...DUP, state: 'duplicate', duplicateOf: 'F1' }, ['triaged']) }; }
    if (c.method === 'POST' && c.url.endsWith('/F1/owner')) { state.item = { ...state.item, ownerId: 'S1', ownerName: 'Priya Natarajan' }; return view(); }
    if (c.method === 'POST' && c.url.endsWith('/F1/tracker')) { state.item = { ...state.item, trackerUrl: (c.body as { url: string }).url }; return view(); }
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/uat/go-no-go')) return { body: REPORT };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/uat/participants')) return { body: { items: [PARTICIPANT] } };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/uat/scripts')) return { body: { items: [{ code: 'customer', persona: 'customer', title: 'Customer', version: '1.0', docPath: 'docs/uat/customer.md', formPath: 'docs/uat/customer-signoff.md' }] } };
    if (c.method === 'POST' && c.url.endsWith('/participants/P1/signoffs')) return { status: 201, body: { ...PARTICIPANT, signoffs: [{ ...PARTICIPANT.signoffs[0], outcome: (c.body as { outcome: string }).outcome, recordedByName: 'Priya Natarajan', recordedAt: '2026-10-03T12:00:00Z', history: 1 }] } };
    return undefined;
  });
}
const user = () => userEvent.setup({ delay: null });

describe('pilot UAT (S-121)', () => {
  it('the dry run: triage a reported blocker from new to closed, with owner and tracker link', async () => {
    const calls = api(['support']);
    renderConsole('/uat');
    expect(await screen.findByRole('heading', { level: 1, name: '2 items · 0 blocking' })).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByRole('link', { name: 'Export CSV' }).getAttribute('href')).toBe('/api/v1/console/uat/feedback/export?state=open');

    renderConsole('/uat?item=F1');
    const drawer = await screen.findByRole('dialog', { name: 'UAT-1001 · Bug' });
    expect(within(drawer).getByText(/After choosing the delivery time/)).toBeTruthy();
    expect(within(drawer).getByText(/consumer · \/checkout · version 2026.10.1 · en-CA · Firefox 131 · macOS/)).toBeTruthy();
    expect(within(drawer).getByRole('link', { name: 'Open the screenshot' }).getAttribute('href')).toBe('/api/v1/console/uat/feedback/F1/screenshot');
    await expectNoAxeViolations(document.body);

    const moveTo = async (state: string, blocking?: string) => {
      await user().selectOptions(within(drawer).getByLabelText('Move to'), state);
      if (blocking) await user().selectOptions(within(drawer).getByLabelText('Does it block the launch?'), blocking);
      await user().click(within(drawer).getByRole('button', { name: 'Move' }));
      await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/F1/moves') && (c.body as { to: string }).to === state)).toBe(true));
    };
    await moveTo('triaged');
    expect(await within(drawer).findByText(/Triaged/)).toBeTruthy();
    // accepting needs the blocking decision (the server's 422, shown as it says)
    await user().selectOptions(within(drawer).getByLabelText('Move to'), 'accepted');
    await user().click(within(drawer).getByRole('button', { name: 'Move' }));
    expect((await within(drawer).findByRole('alert')).textContent).toContain('Say whether it blocks the launch.');
    await moveTo('accepted', 'yes');
    expect(calls.filter(c => c.url.endsWith('/F1/moves')).at(-1)!.body).toEqual({ to: 'accepted', blocking: true });
    expect(calls.filter(c => c.url.endsWith('/F1/moves')).at(-1)!.headers['X-Console-Role']).toBe('support');

    await user().selectOptions(within(drawer).getByLabelText('Owner'), 'S1');
    await user().click(within(drawer).getByRole('button', { name: 'Save owner' }));
    await user().type(within(drawer).getByLabelText('Tracker issue (web address)'), 'https://tracker.example.com/NL-1');
    await user().click(within(drawer).getByRole('button', { name: 'Save link' }));
    expect(await within(drawer).findByRole('link', { name: 'Open the tracker issue' })).toBeTruthy();
    expect(calls.find(c => c.url.endsWith('/F1/owner'))!.body).toEqual({ ownerId: 'S1' });

    await moveTo('fixed');
    await moveTo('verified');
    await moveTo('closed');
    expect(calls.filter(c => c.url.endsWith('/F1/moves')).map(c => (c.body as { to: string }).to)).toEqual(['triaged', 'accepted', 'accepted', 'fixed', 'verified', 'closed']);
  });

  it('merges a duplicate into the item it repeats', async () => {
    const calls = api(['support_lead']);
    renderConsole('/uat?item=F1');
    const drawer = await screen.findByRole('dialog', { name: 'UAT-1001 · Bug' });
    await user().selectOptions(within(drawer).getByLabelText('Move to'), 'duplicate');
    await user().selectOptions(within(drawer).getByLabelText('Duplicate of'), 'F2');
    await user().type(within(drawer).getByLabelText('Note · optional'), 'Same report in French.');
    await user().click(within(drawer).getByRole('button', { name: 'Move' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/F1/moves'))?.body).toEqual({ to: 'duplicate', duplicateOf: 'F2', note: 'Same report in French.' }));
  });

  it('records a sign-off and shows the go/no-go with its reasons, coverage and trend', async () => {
    const calls = api(['support_lead']);
    renderConsole('/uat?view=participants');
    expect(await screen.findByRole('heading', { level: 1, name: '1 participant · 0 signed off' })).toBeTruthy();
    await expectNoAxeViolations(document.body);
    await user().click(screen.getByRole('button', { name: 'Record sign-off' }));
    const dialog = await screen.findByRole('dialog', { name: 'Record sign-off · Pilot customer 1' });
    expect(await within(dialog).findByText(/Printable form: docs\/uat\/customer-signoff.md/)).toBeTruthy();
    await user().selectOptions(within(dialog).getByLabelText('Outcome'), 'with_comments');
    await user().type(within(dialog).getByLabelText('Comments · optional'), 'Works on my phone.');
    await user().click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/participants/P1/signoffs'))?.body)
      .toEqual({ script: 'customer', outcome: 'with_comments', comments: 'Works on my phone.', blockingIds: [] }));

    renderConsole('/uat?view=report');
    expect((await screen.findAllByRole('heading', { level: 1, name: 'No go yet.' })).length).toBeGreaterThan(0);
    expect(screen.getAllByText('1 blocking item(s) not fixed yet.').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/2 reports/).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('link', { name: 'Export the report (CSV)' })[0]!.getAttribute('href')).toBe('/api/v1/console/uat/go-no-go/export');
    expect(screen.getAllByRole('table').length).toBeGreaterThan(0);
  });

  it('is in French', async () => {
    api(['admin']);
    renderConsole('/uat?view=report', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Pas encore de feu vert.' })).toBeTruthy();
    expect(screen.getByText('Couverture des approbations')).toBeTruthy();
    await expectNoAxeViolations(document.body);
  });
});

describe('the console\'s "Send feedback" control (S-121)', () => {
  it('shows only for staff in the pilot, and sends the screen without its query string', async () => {
    const calls = staffApi(['support'], c => {
      if (c.url.endsWith('/api/v1/me/pilot')) return { body: { participant: true, persona: 'staff', screenshotMaxBytes: 5242880, screenshotTypes: ['image/jpeg', 'image/png'] } };
      if (c.method === 'POST' && c.url.endsWith('/api/v1/me/pilot/feedback')) return { status: 201, body: { id: 'F9', reference: 'UAT-1009' } };
      if (c.url.includes('/api/v1/console/uat/')) return { body: { items: [] } };
      return undefined;
    });
    renderConsole('/uat?state=all');
    await user().click(await screen.findByRole('button', { name: 'Send feedback' }));
    const dialog = await screen.findByRole('dialog', { name: 'Send feedback to the Northline team' });
    await user().type(within(dialog).getByLabelText('What happened?'), 'The CSV opens with odd accents.');
    await user().click(within(dialog).getByRole('button', { name: 'Send' }));
    expect(await within(dialog).findByText(/UAT-1009/)).toBeTruthy();
    const sent = calls.find(c => c.url.endsWith('/api/v1/me/pilot/feedback'))!.body as Record<string, string>;
    expect(sent).toMatchObject({ app: 'console', category: 'bug', severity: 'minor', body: 'The CSV opens with odd accents.', locale: 'en-CA', appVersion: 'dev' });
    expect(sent.route).not.toContain('?');
  });

  it('is not there for staff outside the pilot', async () => {
    staffApi(['support'], c => (c.url.endsWith('/api/v1/me/pilot') ? { body: { participant: false, persona: null, screenshotMaxBytes: 5242880, screenshotTypes: [] } } : c.url.includes('/api/v1/console/uat/') ? { body: { items: [] } } : undefined));
    renderConsole('/uat');
    await screenReady();
    expect(screen.queryByRole('button', { name: 'Send feedback' })).toBeNull();
  });
});
