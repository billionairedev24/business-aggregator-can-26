import { beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useParams } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { AccountScreen } from './AccountScreen';
import { ProblemScreen } from './ProblemScreen';

const AMARA = { id: 'C1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: null, initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };
const soon = (h: number) => new Date(Date.now() + h * 3_600_000).toISOString();
const CONTEXT = {
  kind: 'order', id: 'O1', ref: 'NL-48213', title: '', date: '2026-09-30T00:00:00Z', status: 'open', reportBy: soon(120), card: { brand: 'visa', last4: '4471' },
  reasons: ['missing', 'damaged', 'wrong_item', 'poor_quality', 'late'],
  items: [
    { ref: 'L1', title: 'Kale bunch', qty: 1, amountCents: 425, taxCents: 21, merchantId: 'M1', merchantName: 'Sunnyside Greens', status: 'open', reportBy: soon(120) },
    { ref: 'L2', title: 'Country sourdough', qty: 2, amountCents: 1500, taxCents: 0, merchantId: 'M2', merchantName: 'Glenmore Bakery', status: 'open', reportBy: soon(120) },
    { ref: 'L3', title: 'Wiper blades', qty: 1, amountCents: 3800, taxCents: 190, merchantId: 'M3', merchantName: 'Prairie Wrench Parts', status: 'reported', reportBy: null },
  ],
};
const REPORTED = {
  caseId: 'T1', caseCode: 'HD-4481', submittedAt: '2026-10-01T01:04:00Z', totalCents: 446, card: { brand: 'visa', last4: '4471' },
  refunds: [{ id: 'R1', number: 'RF-2201', amountCents: 425, taxCents: 21, merchantName: 'Sunnyside Greens', respondBy: soon(24) }],
};
const CASE = {
  id: 'R1', number: 'RF-2201', kind: 'refund', state: 'seller_review', open: true, what: 'Damaged · Kale bunch', amountCents: 425, taxCents: 21,
  merchantName: 'Sunnyside Greens', openedAt: '2026-09-27T01:00:00Z', respondBy: soon(14), outcome: null, settledCents: null, subject: null,
};
const CLOSED = { ...CASE, id: 'R0', number: 'RF-2102', what: 'Late arrival', state: 'paid', open: false, outcome: 'credit', settledCents: 1500, amountCents: 1500, taxCents: 0, merchantName: 'Bow Valley Cleaners', respondBy: null };

type Reply = { status?: number; body?: unknown } | undefined;
let calls: Call[];
let context: Reply;
let report: Reply;
let triage: Reply;
const server = (c: Call): Reply => {
  if (c.url === '/bff/session') return { body: { user: AMARA, guestId: 'g' } };
  if (c.url === '/api/v1/me/problems/order/O1') return context;
  if (c.url === '/api/v1/me/problems') return report;
  if (c.url === '/api/v1/me/help/triage') return triage;
  if (c.url === '/api/v1/me/case-uploads') return { status: 201, body: { id: 'U1', fileName: 'kale.jpg', contentType: 'image/jpeg', byteSize: 1200 } };
  if (c.url === '/api/v1/me/cases') return { body: { items: [CASE, CLOSED] } };
  if (c.url === '/api/v1/me/cases/R1' && c.method === 'GET') return { body: detail([]) };
  if (c.url === '/api/v1/me/cases/R1/notes') return { body: detail([{ at: '2026-10-01T02:00:00Z', by: 'you', body: (c.body as { body: string }).body, attachments: [] }]) };
  return undefined;
};
const detail = (notes: unknown[]) => ({
  row: CASE, card: { brand: 'visa', last4: '4471' },
  steps: [{ key: 'submitted', state: 'done', at: CASE.openedAt }, { key: 'seller', state: 'current', at: CASE.respondBy }, { key: 'northline', state: 'todo', at: null }, { key: 'refund', state: 'todo', at: null }],
  thread: { id: 'T1', code: 'HD-4481', state: 'new', notes: [{ at: CASE.openedAt, by: 'you', body: 'Reported: Damaged\n- Kale bunch (Sunnyside Greens)', attachments: [] }, ...notes] },
});
function Problem() {
  const { kind, id } = useParams({ strict: false }) as { kind: string; id: string };
  return <ProblemScreen kind={kind} id={id} />;
}
const open = (locale: 'en' | 'fr' = 'en') => {
  calls = mockFetch(server);
  return renderApp('/account/problem/order/O1', { locale, routes: { problem: () => <Problem /> } });
};
const body = (url: string) => calls.filter(c => c.method === 'POST' && c.url === url).map(c => c.body);

beforeEach(() => {
  context = { body: CONTEXT };
  report = { status: 201, body: REPORTED };
  triage = { status: 404, body: {} };
});

describe('Something’s wrong (consumer app refund)', () => {
  it('picks items and a reason, adds a photo, and opens a case — never an instant refund', async () => {
    open();
    expect(await screen.findByRole('heading', { level: 1, name: 'Report a problem' })).toBeInTheDocument();
    expect(screen.getByText('Pick what went wrong. Your request opens a case: the seller gets 24 h to respond, then Northline decides. Refunds land 3–5 business days after a decision.')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: /Wiper blades/ })).toBeDisabled();
    expect(screen.getByText('already reported')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('checkbox', { name: /Kale bunch/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Damaged' }));
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(['x'], 'kale.jpg', { type: 'image/jpeg' })] } });
    expect(await screen.findByText('kale.jpg')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Request $4.46 refund' }));
    expect(await screen.findByText('Case RF-2201 · in review')).toBeInTheDocument();
    expect(screen.getByText('Request received · $4.46')).toBeInTheDocument();
    expect(screen.getByText('The seller’s payout for these items is paused while we look.')).toBeInTheDocument();
    const steps = within(screen.getByRole('list')).getAllByRole('listitem').map(li => li.textContent);
    expect(steps[0]).toMatch(/^Submitted · .* · photo \+ reason attached$/);
    expect(steps[1]).toMatch(/^Seller reviews · Sunnyside Greens has until .* to accept or respond$/);
    expect(steps[2]).toBe('Northline decides · if the seller disputes, an agent rules within 2 business days');
    expect(steps[3]).toBe('Refund issued · to Visa ··4471, 3–5 business days · points adjusted');
    expect(body('/api/v1/me/problems')).toEqual([{ kind: 'order', id: 'O1', items: ['L1'], reason: 'damaged', attachmentIds: ['U1'] }]);
  });

  it('asks for an item and a reason', async () => {
    open();
    await userEvent.click(await screen.findByRole('button', { name: 'Request $0.00 refund' }));
    expect(await screen.findByText('Pick at least one item.')).toBeInTheDocument();
    expect(screen.getByText('Pick what went wrong.')).toBeInTheDocument();
    expect(body('/api/v1/me/problems')).toEqual([]);
  });

  it('offers S-132’s triage as a suggestion, and sends it only when used', async () => {
    triage = { body: { category: 'damaged', route: 'refund_request', urgent: false, summary: 'Kale arrived crushed.' } };
    open();
    await userEvent.click(await screen.findByRole('checkbox', { name: /Kale bunch/ }));
    await userEvent.type(screen.getByLabelText('What happened'), 'The kale arrived crushed and wilted');
    await userEvent.tab();
    expect(await screen.findByText(/Sounds like: Damaged/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Use “Damaged”' }));
    await userEvent.click(screen.getByRole('button', { name: 'Request $4.46 refund' }));
    await waitFor(() => expect(body('/api/v1/me/problems')).toEqual([{
      kind: 'order', id: 'O1', items: ['L1'], reason: 'damaged', note: 'The kale arrived crushed and wilted', attachmentIds: [],
      triageCategory: 'damaged', triageSummary: 'Kale arrived crushed.',
    }]));
  });

  it('without triage (AI off) nothing is suggested', async () => {
    open();
    await userEvent.type(await screen.findByLabelText('What happened'), 'The kale arrived crushed and wilted');
    await userEvent.tab();
    await waitFor(() => expect(calls.some(c => c.url === '/api/v1/me/help/triage')).toBe(true));
    expect(screen.queryByText(/Sounds like/)).not.toBeInTheDocument();
  });

  it('outside the escrow window: says so and points to Help & cases', async () => {
    context = { body: { ...CONTEXT, status: 'closed', items: CONTEXT.items.map(i => ({ ...i, status: 'closed' })) } };
    open();
    expect(await screen.findByText('The time to report a problem with this has passed. Contact Northline from Help & cases.')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Help & cases' }).some(l => l.getAttribute('href') === '/account?tab=help')).toBe(true);
  });

  it('errors: Retry; a server message is shown', async () => {
    let fail = true;
    context = undefined;
    calls = mockFetch(c => (c.url === '/api/v1/me/problems/order/O1' ? (fail ? { status: 500, body: {} } : { body: CONTEXT }) : server(c)));
    renderApp('/account/problem/order/O1', { routes: { problem: () => <Problem /> } });
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('checkbox', { name: /Kale bunch/ })).toBeInTheDocument();
  });

  it('in French', async () => {
    open('fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Signaler un problème' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Endommagé' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Demander un remboursement de 0,00/ })).toBeInTheDocument();
  });
});

describe('Help & cases (design 06 help)', () => {
  const help = (path = '/account?tab=help') => {
    calls = mockFetch(server);
    return renderApp(path, { routes: { account: () => <AccountScreen /> } });
  };

  it('lists the cases with the design’s statuses', async () => {
    help();
    expect(await screen.findByRole('heading', { level: 1, name: 'Help & cases' })).toBeInTheDocument();
    expect(await screen.findByText('RF-2201')).toBeInTheDocument();
    expect(screen.getByText(/^Seller reviewing · 1[34] h left$/)).toBeInTheDocument();
    expect(screen.getByText('Closed · $15.00 credit')).toBeInTheDocument();
    expect(screen.getByText('Sunnyside Greens · Damaged · Kale bunch · $4.46')).toBeInTheDocument();
  });

  it('a case: its timeline, and a message added to it', async () => {
    help('/account?tab=help&case=R1');
    expect(await screen.findByRole('heading', { level: 1, name: 'Case RF-2201' })).toBeInTheDocument();
    expect(screen.getByText('Northline case HD-4481')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Add a message'), 'Photo of the crushed kale coming.');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByText('Photo of the crushed kale coming.')).toBeInTheDocument();
    expect(body('/api/v1/me/cases/R1/notes')).toEqual([{ body: 'Photo of the crushed kale coming.', attachmentIds: [] }]);
  });
});
