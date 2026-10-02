import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const rule = (key: string, value: Record<string, unknown>, fields: { name: string; kind: string; min: number; max: number }[] = []) => ({ key, value, defaults: value, fields, updatedBy: null, edited: null });
const RULES = { items: [
  rule('tier_registered', { takeRateBps: 1500 }),
  rule('tier_trusted', { minJobs: 20, minQuality: 80, minOnTimePct: 92, maxDisputePct: 1.5, takeRateBps: 1200 }, [
    { name: 'minJobs', kind: 'integer', min: 0, max: 10000 }, { name: 'minQuality', kind: 'integer', min: 0, max: 100 },
    { name: 'minOnTimePct', kind: 'number', min: 0, max: 100 }, { name: 'maxDisputePct', kind: 'number', min: 0, max: 100 }, { name: 'takeRateBps', kind: 'integer', min: 0, max: 3000 }]),
  rule('tier_master', { minJobs: 100, minQuality: 85, minOnTimePct: 95, maxDisputePct: 1, minPhotosPct: 90, takeRateBps: 900 }),
  rule('rating_floor', { rating: 4.2, days: 90, recoverDays: 30 }),
  rule('provider_no_shows', { count: 3, days: 30 }), rule('customer_no_shows', { count: 2 }), rule('missing_photo_delay', { hours: 48 }),
  rule('off_platform_phrases', { phrases: ['save the fee'] }, [{ name: 'phrases', kind: 'words', min: 1, max: 200 }]),
  rule('restricted_keywords', { words: ['miracle'] }, [{ name: 'words', kind: 'words', min: 1, max: 200 }]),
] };
const flag = (o: Record<string, unknown>) => ({ targetType: 'message', targetId: 'T', merchantId: 'M', state: 'open', source: 'rules', categories: [], createdAt: '2026-09-08T14:02:00Z', decidedAt: null, action: null, province: 'AB', ...o });
const FLAGS = { items: [
  flag({ id: 'F1', rule: 'off_platform_payment', businessName: 'Kensington Garage on Wheels', explanation: '“e-transfer me directly and save the fee” · message 14:02' }),
  flag({ id: 'F2', rule: 'ai_screen', targetType: 'listing', businessName: 'Handy Hal', explanation: 'Regulated work without permit' }),
  flag({ id: 'F3', rule: 'review_report', businessName: 'Bow River Mechanics', explanation: 'The business reported this review.', state: 'dismissed' }),
] };

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.url.includes('/rating_floor/impact')) return { body: { rating: 4.4, days: 90, affected: 118, total: 1204 } };
    if (c.method === 'GET' && c.url.includes('/api/v1/console/trust/rules')) return { body: RULES };
    if (c.method === 'GET' && c.url.includes('/api/v1/console/trust/flags/queue')) return { body: FLAGS };
    if (c.method === 'PUT') return { body: RULES.items[1] };
    if (c.method === 'POST') return { body: { ...FLAGS.items[0], state: 'actioned', action: 'warn' } };
    return undefined;
  });
}
const user = () => userEvent.setup({ delay: null });

describe('trust & safety (S-93, design 03 trust)', () => {
  it('shows the design copy, tier rules and consequences from the rules, and the open flags', async () => {
    api(['trust_safety']);
    renderConsole('/trust');
    expect(await screen.findByRole('heading', { level: 1, name: 'Rules that keep sellers honest, and the levers to tune them' })).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('20+ jobs · quality ≥ 80 · on-time ≥ 92% · disputes ≤ 1.5%')).toBeTruthy();
    expect(screen.getByText('Instant book, 12% take, badge')).toBeTruthy();
    expect(screen.getByText('9% take, first placement, weekly payouts, priority support')).toBeTruthy();
    expect(screen.getByText('Verified · 0–19 jobs')).toBeTruthy();
    expect(screen.getByText('· Rating floor 4.2 (90-day) → removed from search, coaching checklist, 30 days to recover')).toBeTruthy();
    expect(screen.getByText('· 3 no-shows in 30 days → instant book off')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Open flags · 2' })).toBeTruthy();
    const f1 = screen.getByText('Kensington Garage on Wheels').closest('li') as HTMLElement;
    expect(within(f1).getByText(/Off-platform payment mention/)).toBeTruthy();
    expect(within(f1).getByRole('button', { name: 'Warn' })).toBeTruthy();
    expect(within(screen.getByText('Handy Hal').closest('li') as HTMLElement).getByRole('button', { name: 'Reject listing' })).toBeTruthy();
    expect(within(screen.getByText('Bow River Mechanics').closest('li') as HTMLElement).getByText('Dismissed')).toBeTruthy();
  });

  it('acts on flags with the role header', async () => {
    const calls = api(['trust_safety']);
    renderConsole('/trust');
    const f1 = (await screen.findByText('Kensington Garage on Wheels')).closest('li') as HTMLElement;
    await user().click(within(f1).getByRole('button', { name: 'Warn' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/api/v1/console/trust/flags/F1/action'))).toBe(true));
    const post = calls.find(c => c.method === 'POST')!;
    expect(post.body).toEqual({ action: 'warn' });
    expect(post.headers['X-Console-Role']).toBe('trust_safety');
    await user().click(within(screen.getByText('Handy Hal').closest('li') as HTMLElement).getByRole('button', { name: 'Dismiss' }));
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/flags/F2/decision') && (c.body as { decision: string }).decision === 'dismissed')).toBe(true));
  });

  it('simulates the rating floor and saves the keyword lists', async () => {
    const calls = api(['admin']);
    renderConsole('/trust');
    const floor = await screen.findByRole('textbox', { name: 'Search visibility floor (90-day rating)' });
    await user().clear(floor);
    await user().type(floor, '4.4');
    await user().click(screen.getByRole('button', { name: 'Simulate impact' }));
    expect(await screen.findByText('At 4.4: 118 sellers affected (9.8%).')).toBeTruthy();
    expect(calls.some(c => c.url.endsWith('/rating_floor/impact?rating=4.4'))).toBe(true);
    const words = screen.getByRole('textbox', { name: 'Restricted keywords for listings (one per line)' });
    await user().type(words, '\nguaranteed to pass');
    const save = within(words.closest('.nl-q-field') as HTMLElement).getByRole('button', { name: 'Save' });
    await user().click(save);
    await waitFor(() => expect(calls.some(c => c.method === 'PUT' && c.url.endsWith('/api/v1/console/trust/rules/restricted_keywords'))).toBe(true));
    expect(calls.find(c => c.method === 'PUT')!.body).toEqual({ value: { words: ['miracle', 'guaranteed to pass'] } });
  });

  it('edits a tier rule and shows the api messages', async () => {
    const calls = api(['admin'], c => (c.method === 'PUT' ? { status: 422, body: { errors: [{ field: 'value.minQuality', rule: 'range', message: 'Enter a whole number in the allowed range.' }] } } : undefined));
    renderConsole('/trust');
    const trusted = (await screen.findByText('Trusted')).closest('tr, .nl-dt-card') as HTMLElement;
    await user().click(within(trusted).getByRole('button', { name: /Edit/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Edit Trusted' });
    const quality = within(dialog).getByRole('textbox', { name: 'Minimum quality' });
    await user().clear(quality);
    await user().type(quality, '180');
    await user().click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await within(dialog).findByText('Enter a whole number in the allowed range.')).toBeTruthy();
    expect((calls.find(c => c.method === 'PUT')!.body as { value: Record<string, number> }).value.minQuality).toBe(180);
  });

  it('is refused to roles that do not open it', async () => {
    const calls = api(['support'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500 } : undefined));
    renderConsole('/trust');
    expect(await screen.findByText('Not available in this role.')).toBeTruthy();
    expect(calls.some(c => c.url.includes('/api/v1/console/trust'))).toBe(false);
  });

  it('speaks French', async () => {
    api(['admin']);
    renderConsole('/trust', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Les règles qui gardent les vendeurs honnêtes, et les leviers pour les ajuster' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Avertir' })).toBeTruthy();
  });
});
