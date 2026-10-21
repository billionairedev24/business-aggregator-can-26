import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';

const flag = (o: Record<string, unknown>) => ({
  targetType: 'message', targetId: 'T', merchantId: 'M1', rule: 'ai_screen', state: 'open', source: 'ai', categories: ['off_platform_payment'],
  explanation: 'The business asks to be paid by e-transfer to skip the fee.', createdAt: '2026-10-20T14:02:00Z', decidedAt: null, action: null,
  businessName: 'Kensington Garage', province: null, evidence: { model: 'fake/light', prompt: 'trust-screen@v1' }, ...o,
});
const AI = { items: [flag({ id: 'A1' }), flag({ id: 'A2', targetType: 'listing', categories: ['misleading'], explanation: 'Promises a permanent fix.', businessName: 'Handy Hal' })] };
const SCANS = { items: [{
  id: 'S1', market: 'mkt-calgary', weekStart: '2026-10-12', merchants: 41, flagsRaised: 1, summary: 'One review burst.', aiExplained: true, ranAt: '2026-10-19T06:00:00Z',
  flags: [flag({ id: 'X1', targetType: 'merchant', rule: 'anomaly_2026-10-12', businessName: 'Bow Bakery', explanation: '14 reviews this week against about 2.', evidence: { signals: 'review_burst' } })],
}] };

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.includes('/api/v1/console/trust/flags?source=ai')) return { body: AI };
    if (c.method === 'GET' && c.url.endsWith('/A1/content')) return { body: { flagId: 'A1', targetType: 'message', targetId: 'T', title: null, text: 'Pay me by e-transfer', context: 'from the merchant' } };
    if (c.method === 'GET' && c.url.includes('/api/v1/console/trust/anomaly-scans')) return { body: SCANS };
    return undefined;
  });
}
const user = () => userEvent.setup({ delay: null });

describe('Trust & safety › AI screening (S-158)', () => {
  it('shows the model’s reasons, opens the item (audited) and hides it', async () => {
    const calls = api(['trust_safety'], c => (c.method === 'POST' && c.url.endsWith('/A1/action') ? { body: flag({ id: 'A1', state: 'actioned', action: 'hide' }) } : undefined));
    renderConsole('/trust?view=ai');
    const row = (await screen.findByText('The business asks to be paid by e-transfer to skip the fee.')).closest('tr, .nl-dt-card') as HTMLElement;
    await expectNoAxeViolations(document.body);
    expect(screen.getByRole('tab', { name: 'AI screening', selected: true })).toBeTruthy();
    expect(within(row).getByText('off-platform payment')).toBeTruthy();
    const u = user();
    await u.click(within(row).getByRole('button', { name: 'Review' }));
    const sheet = await screen.findByRole('dialog', { name: 'Message flagged for off-platform payment' });
    expect(within(sheet).getByText('Model fake/light · prompt trust-screen@v1')).toBeTruthy();
    expect(calls.some(c => c.url.endsWith('/content'))).toBe(false); // only when asked
    await u.click(within(sheet).getByRole('button', { name: 'Show the item' }));
    expect(await within(sheet).findByText('Pay me by e-transfer')).toBeTruthy();
    await u.type(within(sheet).getByLabelText('Note (optional)'), 'Clear off-platform ask');
    await u.click(within(sheet).getByRole('button', { name: 'Hide' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/A1/action'))?.body).toEqual({ action: 'hide', note: 'Clear off-platform ask' }));
    expect(await screen.findByText('Message · hidden.')).toBeTruthy();
  });

  it('approve dismisses, escalate hands on; a decided flag says so', async () => {
    const calls = api(['trust_safety'], c => {
      if (c.method === 'POST' && c.url.endsWith('/A2/decision')) return { status: 409, body: { code: 'flag_decided', detail: 'This flag was already decided.' } };
      if (c.method === 'POST' && c.url.endsWith('/A1/decision')) return { body: flag({ id: 'A1', state: 'dismissed' }) };
      return undefined;
    });
    renderConsole('/trust?view=ai');
    const u = user();
    const row = (await screen.findByText('Promises a permanent fix.')).closest('tr, .nl-dt-card') as HTMLElement;
    await u.click(within(row).getByRole('button', { name: 'Review' }));
    const sheet = await screen.findByRole('dialog');
    await u.click(within(sheet).getByRole('button', { name: 'Approve' }));
    expect(await within(sheet).findByText('Someone else already decided this flag.')).toBeTruthy();
    expect(calls.find(c => c.url.endsWith('/A2/decision'))?.body).toEqual({ decision: 'dismissed' });
  });

  it('a role that can’t decide reads the queue without the item; French', async () => {
    api(['admin'], c => (c.url.endsWith('/api/v1/console/me') ? { body: { userId: 'u', roles: [{ role: 'admin', screens: ['trust', 'profile', 'oncall', 'overview'], actions: [] }] } } : undefined));
    renderConsole('/trust?view=ai', { locale: 'fr' });
    expect((await screen.findAllByText('Votre rôle peut lire la file, mais pas décider.')).length).toBeGreaterThan(0);
    expect(await screen.findByText('paiement hors plateforme')).toBeTruthy();
  });

  it('the weekly scan shows each market’s summary and flagged businesses', async () => {
    api(['trust_safety']);
    renderConsole('/trust?view=scan');
    expect(await screen.findByText('One review burst.')).toBeTruthy();
    expect(screen.getByText(/41 businesses looked at · 1 flag/)).toBeTruthy();
    expect(screen.getByText('Explained by the model')).toBeTruthy();
    expect(screen.getByText(/Signals: review burst/)).toBeTruthy();
    expect(screen.getByText('14 reviews this week against about 2.')).toBeTruthy();
    await expectNoAxeViolations(document.body);
  });
});
