import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ago, D, H, M, renderScreen, stubFetch } from './testing';
import { agoParts } from './time';
import { draftProblem, MSG } from './validation';

const shell = vi.hoisted(() => ({ role: 'owner', navigate: vi.fn() }));
vi.mock('../shell/api', () => ({
  useMerchantId: () => '01J9ZD3V00000000000000PWM1',
  useMerchant: () => ({ id: '01J9ZD3V00000000000000PWM1', displayName: 'Prairie Wrench', type: 'provider', tier: 'master', status: 'active', role: shell.role }),
  useRole: () => shell.role,
}));
vi.mock('@tanstack/react-router', async orig => ({ ...(await orig<typeof import('@tanstack/react-router')>()), useNavigate: () => shell.navigate }));

import { MessagesScreen } from './MessagesScreen';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

/** user-event with no timer between keystrokes: typing costs one pass, not a macrotask per character. */
const user = () => userEvent.setup({ delay: null });

const base = `/api/v1/merchants/${M}`;
const thread = (id: string, name: string, over: Record<string, unknown> = {}) => ({
  id, kind: 'customer', counterpartName: name, subject: null, refType: 'booking', refId: `r-${id}`, refCode: null, assigneeId: null,
  lastMessageAt: ago(H), lastMessage: '', unread: false, ...over,
});
const THREADS = { items: [
  thread('t1', 'Amara Osei', { subject: 'brake inspection Tue 9:00', refCode: 'BK-7712', lastMessage: 'Thanks! The dash light came on again yesterday.', lastMessageAt: ago(10_000), unread: true }),
  thread('t2', 'M. Tran', { lastMessage: 'Saturday 10 works. Send the quote?', unread: true }),
  thread('t3', 'D. Kowalski', { lastMessage: 'Pads feel great, thanks Ravi.', lastMessageAt: ago(D + H) }),
  thread('t4', 'Northline support', { kind: 'support', refType: 'dispute', lastMessage: 'Dispute DS-1188: please respond by Thu.', lastMessageAt: ago(2 * D), unread: true }),
] };
const msg = (id: string, role: string, body: string, over: Record<string, unknown> = {}) => ({ id, senderRole: role, senderName: null, body, attachments: [], at: ago(H), flagged: false, templateKey: null, ...over });
const QUICK = [{ key: 'provider.on_my_way', text: 'On my way' }, { key: 'provider.running_late', text: 'Running 15 min late' }, { key: 'provider.job_complete', text: 'Job complete — please sign off' }, { key: 'provider.extra_parts', text: 'Extra parts approval' }];
const DETAIL = {
  thread: THREADS.items[0], quickReplies: QUICK,
  messages: [
    msg('m1', 'customer', 'Hi Ravi — parkade level P2, stall 118. Gate code shared in the app for 2 h.'),
    msg('m2', 'merchant', "Perfect, see you at 9. I'll send an ETA when I leave the previous job."),
    msg('m3', 'customer', 'Thanks! The dash light came on again yesterday.'),
  ],
};

beforeEach(() => { shell.role = 'owner'; shell.navigate.mockReset(); });
afterEach(() => vi.unstubAllGlobals());

describe('MessagesScreen', () => {
  it('shows the design inbox, opens the first thread with its context and marks it read', async () => {
    const calls = stubFetch({ [`GET ${base}/threads`]: THREADS, [`GET ${base}/threads/t1`]: DETAIL, [`POST ${base}/threads/t1/read`]: () => ({ status: 204 }) });
    renderScreen(<MessagesScreen onSelect={vi.fn()} />);
    expect(await screen.findByRole('heading', { name: 'Amara Osei · brake inspection Tue 9:00' })).toBeTruthy();
    const list = screen.getByRole('navigation', { name: 'Conversations' });
    expect(within(list).getAllByRole('button').map(b => b.textContent)).toEqual([
      expect.stringContaining('Amara Osei'), expect.stringContaining('M. Tran'), expect.stringContaining('D. Kowalski'), expect.stringContaining('Northline support'),
    ]);
    expect(within(list).getByText('Yesterday')).toBeTruthy();
    expect(screen.getByText('Phone numbers are masked; messages are kept for disputes.')).toBeTruthy();
    expect(screen.getByText('Booking BK-7712')).toBeTruthy();
    expect(await screen.findByText("Perfect, see you at 9. I'll send an ETA when I leave the previous job.")).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    for (const q of QUICK) expect(screen.getByRole('button', { name: q.text })).toBeTruthy();
    await waitFor(() => expect(calls.some(c => c.key === `POST ${base}/threads/t1/read`)).toBe(true));
  });

  it('a quick reply fills the composer and is sent with its template key', async () => {
    const calls = stubFetch({
      [`GET ${base}/threads`]: THREADS, [`GET ${base}/threads/t1`]: DETAIL, [`POST ${base}/threads/t1/read`]: () => ({ status: 204 }),
      [`POST ${base}/threads/t1/messages`]: (body: unknown) => ({ status: 201, json: msg('m4', 'merchant', (body as { body: string }).body, { templateKey: 'provider.on_my_way' }) }),
    });
    renderScreen(<MessagesScreen onSelect={vi.fn()} />);
    await user().click(await screen.findByRole('button', { name: 'On my way' }));
    const input = screen.getByRole('textbox', { name: 'Reply to Amara Osei' });
    expect((input as HTMLInputElement).value).toBe('On my way');
    await user().click(screen.getByRole('button', { name: 'Send' }));
    await waitFor(() => expect(calls.find(c => c.key === `POST ${base}/threads/t1/messages`)?.body).toEqual({ body: 'On my way', attachmentIds: [], templateKey: 'provider.on_my_way' }));
    await waitFor(() => expect((input as HTMLInputElement).value).toBe(''));
  });

  it('an empty send shows the rule, a 422 from the server shows its message', async () => {
    stubFetch({
      [`GET ${base}/threads`]: THREADS, [`GET ${base}/threads/t1`]: DETAIL, [`POST ${base}/threads/t1/read`]: () => ({ status: 204 }),
      [`POST ${base}/threads/t1/messages`]: () => ({ status: 422, json: { errors: [{ field: 'attachmentIds', rule: 'exists', message: MSG.FILE_GONE }] } }),
    });
    renderScreen(<MessagesScreen onSelect={vi.fn()} />);
    await screen.findByRole('button', { name: 'On my way' });
    await user().click(screen.getByRole('button', { name: 'Send' }));
    expect((await screen.findByRole('alert')).textContent).toBe('Write a message or attach a file.');
    await user().type(screen.getByRole('textbox', { name: 'Reply to Amara Osei' }), 'Hello');
    await user().click(screen.getByRole('button', { name: 'Send' }));
    expect((await screen.findByRole('alert')).textContent).toBe('That file is no longer available. Attach it again.');
    expect((screen.getByRole('textbox', { name: 'Reply to Amara Osei' }) as HTMLInputElement).value).toBe('Hello');
  });

  it('flagged messages carry the off-platform note', async () => {
    stubFetch({
      [`GET ${base}/threads`]: THREADS, [`POST ${base}/threads/t1/read`]: () => ({ status: 204 }),
      [`GET ${base}/threads/t1`]: { ...DETAIL, messages: [msg('m9', 'merchant', 'Text me at •••-•••-••••', { flagged: true })] },
    });
    renderScreen(<MessagesScreen onSelect={vi.fn()} />);
    expect(await screen.findByRole('note')).toBeTruthy();
    expect(screen.getByRole('note').textContent).toContain('keep payment in the app');
  });

  it('empty states per role, error state with retry', async () => {
    shell.role = 'bookkeeper';
    stubFetch({ [`GET ${base}/threads`]: { items: [] } });
    const { unmount } = renderScreen(<MessagesScreen onSelect={vi.fn()} />);
    expect(await screen.findByText('Customer messages are visible to the owner and the team member on the job.')).toBeTruthy();
    unmount();
    shell.role = 'owner';
    let fail = true;
    stubFetch({ [`GET ${base}/threads`]: () => (fail ? { status: 500, json: {} } : { json: { items: [] } }) });
    renderScreen(<MessagesScreen onSelect={vi.fn()} />);
    expect(await screen.findByText("We couldn't load your messages.")).toBeTruthy();
    fail = false;
    await user().click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('No conversations yet — customers can message you once they book or order.')).toBeTruthy();
  });

  it('selecting another conversation reports it to the route', async () => {
    stubFetch({ [`GET ${base}/threads`]: THREADS, [`GET ${base}/threads/t1`]: DETAIL, [`POST ${base}/threads/t1/read`]: () => ({ status: 204 }) });
    const onSelect = vi.fn();
    renderScreen(<MessagesScreen onSelect={onSelect} />);
    await user().click(await screen.findByRole('button', { name: /M\. Tran/ }));
    expect(onSelect).toHaveBeenCalledWith('t2');
  });

  it('French copy', async () => {
    stubFetch({ [`GET ${base}/threads`]: THREADS, [`GET ${base}/threads/t1`]: DETAIL, [`POST ${base}/threads/t1/read`]: () => ({ status: 204 }) });
    renderScreen(<MessagesScreen onSelect={vi.fn()} />, 'fr');
    expect(await screen.findByText('Les numéros de téléphone sont masqués; les messages sont conservés en cas de litige.')).toBeTruthy();
    expect(screen.getByText('Soutien Northline')).toBeTruthy();
  });
});

describe('rules', () => {
  it('draft needs text or a file, at most 2,000 characters and 5 files', () => {
    expect(draftProblem('  ', [])).toBe(MSG.MESSAGE_REQUIRED);
    expect(draftProblem('', [{ id: 'a' }])).toBeUndefined();
    expect(draftProblem('x'.repeat(2001), [])).toBe(MSG.MESSAGE_TOO_LONG);
    expect(draftProblem('hi', Array.from({ length: 6 }, (_, i) => ({ id: `${i}` })))).toBe(MSG.TOO_MANY_FILES);
  });

  it('relative times read like the design', () => {
    const now = Date.parse('2026-09-29T18:00:00Z');
    expect(agoParts('2026-09-29T17:59:40Z', now).unit).toBe('now');
    expect(agoParts('2026-09-29T17:00:00Z', now)).toEqual({ unit: 'h', n: 1 });
    expect(agoParts('2026-09-28T17:00:00Z', now).unit).toBe('yesterday');
    expect(agoParts('2026-09-27T18:00:00Z', now)).toEqual({ unit: 'd', n: 2 });
    expect(agoParts('2026-09-22T18:00:00Z', now)).toEqual({ unit: 'w', n: 1 });
  });
});
