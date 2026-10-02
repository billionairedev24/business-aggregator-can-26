import { useState } from 'react';
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ago, D, H, M, renderScreen, stubFetch } from '../messages/testing';

const shell = vi.hoisted(() => ({ role: 'owner', tier: 'master', type: 'provider', navigate: vi.fn() }));
vi.mock('../shell/api', () => ({
  useMerchantId: () => '01J9ZD3V00000000000000PWM1',
  useMerchant: () => ({ id: '01J9ZD3V00000000000000PWM1', displayName: 'Prairie Wrench', type: shell.type, tier: shell.tier, status: 'active', role: shell.role }),
  useRole: () => shell.role,
}));
vi.mock('@tanstack/react-router', async orig => ({ ...(await orig<typeof import('@tanstack/react-router')>()), useNavigate: () => shell.navigate }));

import { HelpScreen, type HelpSearch } from './HelpScreen';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

/** user-event with no timer between keystrokes: typing costs one pass, not a macrotask per character. */
const user = () => userEvent.setup({ delay: null });

// Pin the clock (noon in Edmonton) before the fixtures below are built: "today"/"tomorrow" are Edmonton days, so a
// real clock made the SLA wording depend on whether UTC and Edmonton were on the same calendar day.
vi.useFakeTimers({ toFake: ['Date'] });
vi.setSystemTime(new Date('2026-09-08T18:00:00Z'));
afterAll(() => vi.useRealTimers());

const base = `/api/v1/merchants/${M}/help`;
const TOPICS = { items: [
  { key: 'verification', name: 'Getting verified · licences & insurance', articleCount: 3, caseTopic: 'verification' },
  { key: 'appointments', name: 'Appointments, availability & no-shows', articleCount: 2, caseTopic: 'appointments' },
  { key: 'escrow', name: 'Escrow, payouts & fees', articleCount: 4, caseTopic: 'payouts' },
] };
const SUGGESTED = { items: [
  { slug: 'completion-photo', title: 'What the completion photo must show', section: 'Escrow', readMin: 2 },
  { slug: 'quality-score-tiers', title: 'How the quality score and tiers are calculated', section: 'Reputation', readMin: 5 },
] };
const SEARCH = { items: [
  { slug: 'payout-on-hold', title: 'Why is my payout on hold?', section: 'Escrow', readMin: 2 },
  { slug: 'auto-release-48h', title: 'How the 48-hour auto-release works', section: 'Escrow', readMin: 3 },
] };
const tomorrow10 = () => { const d = new Date(Date.now() + D); d.setUTCHours(16, 0, 0, 0); return d.toISOString(); }; // 10 am MDT
const kase = (id: string, over: Record<string, unknown>) => ({
  id, code: 'HD-1', subject: 'x', topic: 'other', state: 'resolved', priority: 'priority', urgent: false, channel: 'chat', agentName: null, lastAgentReplyAt: null,
  slaDueAt: null, resolvedAt: null, resolutionNote: null, refType: null, refId: null, refLabel: null, createdAt: ago(3 * H), ...over,
});
const OPEN = kase('c1', { code: 'HD-4471', subject: 'WCB clearance letter uploaded — awaiting re-verification', state: 'in_progress', agentName: 'Dev K.', lastAgentReplyAt: ago(2 * H + 60_000), slaDueAt: tomorrow10() });
const LATE = kase('c2', { code: 'HD-4402', subject: 'Payout of $1,912.40 arrived a day late', resolvedAt: '2026-09-05T17:00:00Z', resolutionNote: 'bank holiday' });
const CASES = { items: [OPEN, LATE] };
const msg = (id: string, role: string, body: string, name: string | null = null) => ({ id, senderRole: role, senderName: name, body, attachments: [], at: ago(H), flagged: false, templateKey: null });
const DETAIL = { summary: OPEN, messages: [
  msg('m1', 'merchant', 'Uploaded the renewed letter — can instant book be turned back on today?'),
  msg('m2', 'agent', 'Thanks Ravi — document received and readable. Compliance re-verifies within 4 business hours; I’ve flagged it as blocking so it’s next in queue. — Dev K.', 'Dev K.'),
] };
const RELATED = { items: [{ type: 'booking', id: 'BK-7712', label: 'Booking BK-7712 · A. Osei' }, { type: 'order', id: 'NL-48213', label: 'Order NL-48213' }] };
const routes = (extra: Record<string, unknown> = {}) => ({
  [`GET ${base}/topics`]: TOPICS,
  [`GET ${base}/articles`]: (_b: unknown, _i: RequestInit, url: URL) => ({ json: url.searchParams.get('q') ? SEARCH : SUGGESTED }),
  [`GET ${base}/cases`]: CASES, [`GET ${base}/cases/c1`]: DETAIL, [`GET ${base}/related`]: RELATED,
  [`GET ${base}/status`]: { items: [
    { key: 'ordering', name: 'Ordering & checkout', state: 'operational', note: null, since: null },
    { key: 'notifications', name: 'Notifications (SMS)', state: 'degraded', note: 'carrier delays in AB', since: '2026-09-29T18:40:00Z' },
  ] },
  ...extra,
});

/** Stands in for the route: keeps the search params in state. */
function Harness({ initial = {} }: { initial?: HelpSearch }) {
  const [search, setSearch] = useState<HelpSearch>(initial);
  return <HelpScreen search={search} onNavigate={setSearch} />;
}

beforeEach(() => { shell.role = 'owner'; shell.tier = 'master'; shell.type = 'provider'; shell.navigate.mockReset(); });
afterEach(() => vi.unstubAllGlobals());

describe('Help centre', () => {
  it('shows the design home: tabs, topics, suggestions, SLA by tier, open case, guided fixes', async () => {
    stubFetch(routes());
    renderScreen(<Harness />);
    expect(screen.getByRole('heading', { name: 'How can we help, Prairie Wrench?' })).toBeTruthy();
    expect(await screen.findByRole('tab', { name: 'My cases · 1 open' })).toBeTruthy();
    expect(await screen.findByText('What the completion photo must show')).toBeTruthy();
    expect(screen.getByText('Escrow · 2 min')).toBeTruthy();
    expect(screen.getByText('3 articles')).toBeTruthy();
    expect(screen.getByText('Master tier: priority queue · first reply within 1 h (business hours).')).toBeTruthy();
    expect(await screen.findByText('HD-4471')).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText(/Open · agent Dev K\. · replied 2 h ago · Reply by tomorrow 10/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Stripe & compliance' })).toBeTruthy();
    await user().click(screen.getByRole('link', { name: 'Earnings' }));
    expect(shell.navigate).toHaveBeenCalledWith({ to: `/b/${M}/earnings` });
  });

  it('search shows the query as the heading and "min read" results', async () => {
    const calls = stubFetch(routes());
    renderScreen(<Harness />);
    await user().type(await screen.findByRole('searchbox', { name: 'Search help' }), 'payout on hold');
    expect(await screen.findByRole('heading', { name: 'payout on hold' })).toBeTruthy();
    expect(await screen.findByText('Why is my payout on hold?')).toBeTruthy();
    expect(screen.getByText('Escrow · 2 min read')).toBeTruthy();
    expect(calls.some(c => c.url.searchParams.get('q') === 'payout on hold')).toBe(true);
  });

  it('non-Master tiers get the 4-hour promise', async () => {
    shell.tier = 'trusted';
    stubFetch(routes());
    renderScreen(<Harness />);
    expect(await screen.findByText('First reply within 4 business hours · chat is fastest.')).toBeTruthy();
  });

  it('Request a callback opens Contact support with Phone call chosen', async () => {
    stubFetch(routes());
    renderScreen(<Harness />);
    await user().click(await screen.findByRole('button', { name: 'Request a callback' }));
    expect(screen.getByRole('button', { name: 'Phone call', pressed: true })).toBeTruthy();
  });
});

describe('Contact support', () => {
  it('?topic= preselects the topic (onboarding "Need help with a document?")', async () => {
    stubFetch(routes());
    renderScreen(<Harness initial={{ topic: 'verification' }} />);
    expect(await screen.findByRole('button', { name: 'Verification & documents', pressed: true })).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'Contact support', selected: true })).toBeTruthy();
  });

  it('validates after submit, urgent changes the SLA, sends and lands on the case', async () => {
    const created = kase('c9', { code: 'HD-4480', subject: 'Payout missing', state: 'new', slaDueAt: ago(-15 * 60_000), urgent: true, priority: 'urgent' });
    let list = CASES.items;
    const calls = stubFetch(routes({
      [`GET ${base}/cases`]: () => ({ json: { items: list } }),
      [`POST ${base}/cases`]: () => { list = [created, ...list]; return { status: 201, json: created }; },
      [`GET ${base}/cases/c9`]: { summary: kase('c9', { code: 'HD-4480', state: 'new' }), messages: [msg('x', 'merchant', 'Payout missing since Friday.')] },
    }));
    renderScreen(<Harness initial={{ tab: 'new' }} />);
    await user().click(await screen.findByRole('button', { name: 'Send' }));
    expect(screen.getByText('2 things need attention.')).toBeTruthy();
    expect(screen.getByText('Choose a topic.')).toBeTruthy();
    expect(screen.getByText("Tell us what's happening.")).toBeTruthy();
    await user().click(screen.getByRole('button', { name: 'Payouts & fees' }));
    await user().type(screen.getByRole('textbox', { name: "What's happening?" }), 'Payout missing since Friday.');
    await user().selectOptions(screen.getByRole('combobox', { name: 'Related to' }), 'Booking BK-7712 · A. Osei');
    await user().click(screen.getByRole('checkbox', { name: /This is urgent/ }));
    expect(screen.getByText(/Urgent \(safety, payment stuck, live order failing\): a human within 15 min/)).toBeTruthy();
    await user().click(screen.getByRole('button', { name: 'Send' }));
    await waitFor(() => expect(calls.find(c => c.key === `POST ${base}/cases`)?.body).toEqual({
      topic: 'payouts', refType: 'booking', refId: 'BK-7712', refLabel: 'Booking BK-7712 · A. Osei', body: 'Payout missing since Friday.', attachmentIds: [], channel: 'chat', urgent: true,
    }));
    const banner = (await screen.findByText('Case HD-4480 opened.')).parentElement!;
    expect(banner.textContent).toContain('Case HD-4480 opened.');
    expect(banner.textContent).toContain('Topic: Payouts & fees.');
    expect(await screen.findByRole('heading', { name: 'HD-4480 · conversation' })).toBeTruthy();
  });

  it('maps a server 422 onto the field', async () => {
    stubFetch(routes({ [`POST ${base}/cases`]: () => ({ status: 422, json: { errors: [{ field: 'refId', rule: 'required', message: 'Pick a record from the list.' }] } }) }));
    renderScreen(<Harness initial={{ tab: 'new', topic: 'refunds' }} />);
    await user().type(await screen.findByRole('textbox', { name: "What's happening?" }), 'Dispute');
    await user().click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByText('Pick a record from the list.')).toBeTruthy();
    expect(screen.getByText('1 thing needs attention.')).toBeTruthy();
  });
});

describe('My cases and status', () => {
  it('lists cases in the table and shows the open case conversation with a reply box', async () => {
    const calls = stubFetch(routes({ [`POST ${base}/cases/c1/messages`]: (b: unknown) => ({ status: 201, json: msg('m3', 'merchant', (b as { body: string }).body) }) }));
    renderScreen(<Harness initial={{ tab: 'cases' }} />);
    expect((await screen.findAllByText('HD-4402')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('Resolved · Sep 5 · bank holiday').length).toBeGreaterThan(0);
    expect(await screen.findByRole('heading', { name: 'HD-4471 · conversation' })).toBeTruthy();
    expect(await screen.findByText(/document received and readable/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Attach' })).toBeTruthy();
    await user().type(screen.getByRole('textbox', { name: 'Reply in case HD-4471' }), 'Thanks!');
    await user().click(screen.getByRole('button', { name: 'Send' }));
    await waitFor(() => expect(calls.find(c => c.key === `POST ${base}/cases/c1/messages`)?.body).toEqual({ body: 'Thanks!', attachmentIds: [] }));
  });

  it('platform status', async () => {
    stubFetch(routes());
    renderScreen(<Harness initial={{ tab: 'status' }} />);
    expect(await screen.findByText('Ordering & checkout')).toBeTruthy();
    const row = screen.getByText('Notifications (SMS)').closest('li')!;
    expect(within(row).getByText(/^Degraded · carrier delays in AB · 12:40/)).toBeTruthy();
    expect(screen.getByText('Incidents post here and by SMS to the account owner. Scheduled maintenance: Sun 2–4 am MT.')).toBeTruthy();
  });
});
