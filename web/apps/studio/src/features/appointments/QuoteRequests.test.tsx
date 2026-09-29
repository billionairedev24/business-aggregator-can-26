import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { QuoteRequests } from './QuoteRequests';

vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => 'owner' }));
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => vi.fn() }));

const draftQuote = {
  id: 'q1', requestId: 'r1', ref: 'QT-3104', version: 1, state: 'draft',
  lines: [{ kind: 'labour', description: 'Diagnose charging system', qty: 0.5, unitCents: 6500, amountCents: 3250, taxable: true }],
  labourCents: 3250, partsCents: 0, feesCents: 0, discountCents: 0, subtotalCents: 3250, taxBps: 500, taxCents: 163, totalCents: 3413,
  depositKind: 'none', depositBps: null, depositCents: 0, scope: 'Confirm charging fault.', exclusions: null, warranty: 'parts_labour_12m',
  proposedAt: null, durationMin: 120, validHours: 72, validUntil: null, sentAt: null, attachments: [],
};
const request = (quote: unknown) => ({ id: 'r1', ref: 'QT-3104', title: 'Alternator, 2016 Civic', customerName: 'M. Tran', reliability: 4.8, area: 'Inglewood', body: 'Battery light on', preferredAt: null, createdAt: '2026-09-29T15:00:00Z', respondBy: null, expiresAt: null, quote });

describe('Quote requests + composer', () => {
  let calls: ReturnType<typeof mockFetch>;
  beforeEach(() => {
    let current: unknown = draftQuote;
    calls = mockFetch({
      'GET /api/v1/merchants/m1/quote-requests': () => ({ items: [request(current)] }),
      'POST /api/v1/merchants/m1/quote-requests/r1/quotes': (_u, init) => {
        const body = JSON.parse(String(init.body));
        current = { ...draftQuote, state: 'sent', lines: body.lines.map((l: { unitCents: number; qty: number }) => ({ ...l, amountCents: l.unitCents * l.qty })), totalCents: 16538, validHours: body.validHours };
        return current;
      },
    });
  });
  afterEach(() => vi.unstubAllGlobals());

  it('opens the draft, shows inline errors + summary on send, then sends the fixed quote', async () => {
    const user = userEvent.setup();
    renderWithProviders(<QuoteRequests />);
    expect(await screen.findByText('Quote requests · 1')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Continue draft' }));
    expect(screen.getByText('Quote · QT-3104')).toBeTruthy();
    expect((screen.getByLabelText('Line 1 item') as HTMLInputElement).value).toBe('Diagnose charging system');

    await user.click(screen.getByRole('button', { name: '+ Add line' }));
    await user.clear(screen.getByLabelText(/Scope of work/));
    await user.click(screen.getByRole('button', { name: /^Send quote/ }));
    const alerts = screen.getAllByRole('alert').map(a => a.textContent);
    expect(alerts).toContain("Describe this line — customers must see what they're paying for.");
    expect(alerts).toContain('Describe the scope of work.');
    expect(alerts).toContain('2 things to fix before sending.');
    expect(calls.filter(c => c.method === 'POST')).toHaveLength(0);

    await user.type(screen.getByLabelText('Line 2 item'), 'Alternator — remanufactured');
    await user.selectOptions(screen.getByLabelText('Line 2 type'), 'part');
    await user.type(screen.getByLabelText('Line 2 amount'), '240');
    await user.type(screen.getByLabelText(/Scope of work/), 'Replace alternator.');
    await user.selectOptions(screen.getByLabelText('Quote valid for'), '168');
    expect(screen.getByRole('button', { name: 'Send quote · $286.13' })).toBeTruthy();
    await user.click(screen.getByRole('button', { name: /^Send quote/ }));

    await waitFor(() => expect(calls.find(c => c.method === 'POST')).toBeDefined());
    const post = calls.find(c => c.method === 'POST')!;
    expect(post.body).toMatchObject({ scope: 'Replace alternator.', validHours: 168, lines: [{ kind: 'labour', unitCents: 6500, qty: 0.5 }, { kind: 'part', unitCents: 24000, qty: 1 }] });
    expect(await screen.findByText(/Quote sent · \$165\.38 · 2 lines · valid 7 days/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'View as customer' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Revise' })).toBeTruthy();
  });

  it('maps server 422s onto the lines', async () => {
    mockFetch({
      'GET /api/v1/merchants/m1/quote-requests': () => ({ items: [request(draftQuote)] }),
      'POST /api/v1/merchants/m1/quote-requests/r1/quotes': () => { throw { status: 422, body: { errors: [{ field: 'lines[0].unitCents', rule: 'positive_unless_discount', message: 'Enter an amount.' }] } }; },
    });
    const user = userEvent.setup();
    renderWithProviders(<QuoteRequests />);
    await user.click(await screen.findByRole('button', { name: 'Continue draft' }));
    await user.click(screen.getByRole('button', { name: /^Send quote/ }));
    const row = await screen.findByText('Enter an amount.');
    expect(row.getAttribute('role')).toBe('alert');
    expect(within(row.parentElement!).getByLabelText('Line 1 amount').getAttribute('aria-invalid')).toBe('true');
  });
});
