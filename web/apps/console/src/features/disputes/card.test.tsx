import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, screenReady, staffApi, type Call } from '../../test/render';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const row = (o: Record<string, unknown> = {}) => ({
  id: 'D1', caseNumber: 'DS-1201', merchantId: 'M1', subject: 'Card dispute · product not received', amountCents: 16000, stripeDispute: 'dp_1',
  stripeStatus: 'needs_response', stripeReason: 'product_not_received', dueBy: '2026-10-29T23:59:00Z', respondBy: '2026-10-27T23:59:00Z',
  openedAt: '2026-10-19T18:00:00Z', decision: null, submittedAt: null, feeCents: 1500, ...o,
});
const ITEM = { row: row(), businessName: 'Prairie Wrench', province: 'AB' };
const DETAIL = (o: Record<string, unknown> = {}) => ({
  item: ITEM,
  detail: { merchantResponse: 'Job done Sep 4; the invoice is signed.', caseEvidence: [{ id: 'E1', kind: 'document', name: 'invoice.pdf', contentType: 'application/pdf', size: 16, by: 'merchant', at: '2026-09-06T20:00:00Z', file: true }],
    fields: {}, files: {}, updatedBy: null, updatedAt: null, submittedBy: null, lastError: null, ...o },
});

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/card-disputes/D1')) return { body: DETAIL() };
    if (c.method === 'GET' && c.url.includes('/api/v1/console/card-disputes')) return { body: { items: [ITEM] } };
    return undefined;
  });
}
const user = () => userEvent.setup({ delay: null });

describe('card disputes (S-156)', () => {
  it('lists chargebacks with the deadline and assembles the evidence from the case', async () => {
    const calls = api(['trust_safety'], c => {
      if (c.method === 'PUT') return { body: DETAIL({ fields: (c.body as { fields: Record<string, string> }).fields, files: (c.body as { files: Record<string, string> }).files }) };
      if (c.method === 'POST') return { body: { ...DETAIL(), item: { ...ITEM, row: row({ submittedAt: '2026-10-20T18:00:00Z', stripeStatus: 'under_review' }) } } };
      return undefined;
    });
    renderConsole('/disputes/card?dispute=D1');
    expect((await screenReady()).textContent).toBe('Card disputes');
    const sheet = await screen.findByRole('dialog', { name: 'DS-1201 · Prairie Wrench' });
    expect(await within(sheet).findByText('Job done Sep 4; the invoice is signed.')).toBeTruthy();
    expect(within(sheet).getByText(/Stripe fee \$15\.00/)).toBeTruthy();
    await expectNoAxeViolations(document.body);
    await user().click(within(sheet).getByRole('button', { name: 'Use the business’s answer' }));
    await user().type(within(sheet).getByLabelText('Service date'), '2026-09-04');
    await user().selectOptions(within(sheet).getByLabelText('Receipt'), 'E1');
    await user().click(within(sheet).getByRole('button', { name: 'Submit to Stripe' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/D1/evidence/submit'))).toBe(true));
    expect(calls.find(c => c.method === 'PUT')?.body).toEqual({
      fields: { uncategorized_text: 'Job done Sep 4; the invoice is signed.', service_date: '2026-09-04' }, files: { receipt: 'E1' },
    });
  });

  it('shows Stripe’s refusal and the field errors', async () => {
    api(['trust_safety'], c => {
      if (c.method === 'GET' && c.url.endsWith('/card-disputes/D1')) return { body: DETAIL({ lastError: 'the dispute is not accepting evidence' }) };
      if (c.method === 'PUT') return { status: 422, body: { errors: [{ field: 'fields.service_date', rule: 'length', message: 'Keep each answer under 5,000 characters.' }] } };
      return undefined;
    });
    renderConsole('/disputes/card?dispute=D1');
    const sheet = await screen.findByRole('dialog', { name: 'DS-1201 · Prairie Wrench' });
    expect(await within(sheet).findByText('Stripe refused the last submission: the dispute is not accepting evidence')).toBeTruthy();
    await user().click(within(sheet).getByRole('button', { name: 'Save draft' }));
    expect(await within(sheet).findByText('Keep each answer under 5,000 characters.')).toBeTruthy();
  });
});
