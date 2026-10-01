import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi } from '../../test/render';
import type { Report } from './api';

const weeks: Report['weeks'] = Array.from({ length: 13 }, (_, i) => ({
  week: new Date(Date.UTC(2026, 5, 8 + i * 7)).toISOString().slice(0, 10), customers: 1200 + i * 40, previous: 1100 + i * 30,
}));
weeks[3] = { ...weeks[3]!, customers: null };
const REPORT: Report = {
  asOf: '2026-09-08T18:00:00Z', province: null, from: '2026-06-11', weeks,
  funnel: [
    { step: 'app_opens', count: null, recorded: false }, { step: 'browsed', count: 31_900, recorded: true }, { step: 'cart', count: 12_400, recorded: true },
    { step: 'checkout', count: 8_900, recorded: true }, { step: 'paid', count: 7_100, recorded: true },
  ],
  cohorts: [
    { month: '2026-05', customers: 1204, m1: 58, m2: 49, m3: 44 }, { month: '2026-06', customers: 1890, m1: 61, m2: 52, m3: null },
    { month: '2026-07', customers: null, m1: null, m2: null, m3: null }, { month: '2026-08', customers: 3310, m1: 66, m2: null, m3: null },
  ],
  topCategories: [{ categoryId: 'shop.food.groceries', names: { en: 'Groceries', fr: 'Épicerie' }, salesCents: 61_200_000 }, { categoryId: 'service.auto.mech', names: { en: 'Mobile mechanic' }, salesCents: 28_800_000 }],
  waitlist: [{ province: 'ON', people: 2140 }],
};

describe('reports & analytics (S-95, design 03)', () => {
  it('shows weekly active customers, the funnel, cohorts, top categories and waitlist demand', async () => {
    staffApi(['analyst'], c => (c.url.includes('/api/v1/console/reports') ? { body: REPORT } : undefined));
    renderConsole('/reports');
    expect(await screen.findByRole('heading', { level: 1, name: 'Marketplace health · 90 days' })).toBeTruthy();
    expect(screen.getByRole('img', { name: 'Weekly active customers' })).toBeTruthy();
    expect(screen.getByText('Solid: this period · dotted: previous')).toBeTruthy();
    const step = (name: string) => screen.getByText(name).closest('.nl-meter') as HTMLElement;
    expect(within(step('App opens')).getByText('not recorded')).toBeTruthy();
    expect(within(step('Searched / browsed')).getByText('31,900')).toBeTruthy();
    expect(within(step('Paid')).getByText('7,100')).toBeTruthy();
    const may = screen.getByText('May').closest('tr') as HTMLElement;
    expect(within(may).getByText('1,204')).toBeTruthy();
    expect(within(may).getByText('58%')).toBeTruthy();
    expect(within(screen.getByText('Jul').closest('tr') as HTMLElement).getByText('fewer than 5')).toBeTruthy();
    expect(screen.getByText('Groceries')).toBeTruthy();
    expect(screen.getByText('$612,000')).toBeTruthy();
    expect(screen.getByText('Waitlist demand · Ontario')).toBeTruthy();
    expect(screen.getByText('2,140')).toBeTruthy();
    expect(screen.getAllByText('fewer than 5').length).toBeGreaterThan(1); // the withheld week, in the table view
  });

  it('filters by province through the region model', async () => {
    const calls = staffApi(['finance'], c => (c.url.includes('/api/v1/console/reports') ? { body: REPORT } : undefined));
    const user = userEvent.setup({ delay: null });
    renderConsole('/reports');
    await screen.findByRole('heading', { level: 1 });
    await user.click(screen.getByRole('radio', { name: 'Alberta' }));
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/v1/console/reports?province=AB'))).toBe(true));
  });

  it('is closed to roles without reports, and speaks French', async () => {
    staffApi(['support'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500 } : undefined));
    const first = renderConsole('/reports');
    expect(await screen.findByText(/Not available in this role\./)).toBeTruthy();
    first.unmount();
    staffApi(['analyst'], c => (c.url.includes('/api/v1/console/reports') ? { body: REPORT } : undefined));
    renderConsole('/reports', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Santé du marché · 90 jours' })).toBeTruthy();
    expect(screen.getByText('Épicerie')).toBeTruthy();
  });
});
