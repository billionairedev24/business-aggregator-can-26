import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { overview } from '../../test/fixtures';
import { renderConsole, staffApi, screenReady } from '../../test/render';
import { compactMoney } from './Overview';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

describe('console overview (S-91, design 03)', () => {
  it('shows the page head, the KPI strip, GMV, the to-do queues, live now and health (BO:263)', async () => {
    staffApi(['admin'], c => (c.url.includes('/api/v1/console/overview') ? { body: overview() } : undefined));
    const user = userEvent.setup({ delay: null });
    const { router } = renderConsole('/');
    expect((await screenReady()).textContent).toBe('Overview');
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('Tuesday 8 September · live')).toBeTruthy();
    const kpis = within(screen.getByRole('list', { name: 'The last 7 days' }));
    const card = (label: string) => kpis.getByText(label).parentElement as HTMLElement;
    const expected: [string, string, string?][] = [['GMV · week', '$212k', '+9% vs last week'], ['Net revenue', '$24.1k', '11.4% blended take'], ['Orders + bookings', '6,812', '61% goods'],
      ['On-time', '96.8%', 'target 95%'], ['Dispute rate', '0.9%', 'target under 1.2%'], ['Avg delivery fee paid', '$2.71']];
    for (const [label, value, delta] of expected) {
      expect(card(label).querySelector('.bo-kpi-value')?.textContent, label).toBe(value);
      if (delta) expect(card(label).querySelector('.bo-kpi-delta')?.textContent, label).toBe(delta);
    }
    expect(card('GMV · week').querySelector('.bo-kpi-delta')?.getAttribute('data-trend')).toBe('up');
    expect(screen.getByRole('img', { name: 'GMV · 12 weeks' })).toBeTruthy();
    expect(screen.getByText('184 ms')).toBeTruthy();
    expect(screen.getByText('27k conns')).toBeTruthy();
    expect(screen.getByText('1 offline')).toBeTruthy();
    expect(within(screen.getByText('Kafka lag').closest('.nl-ov-tile') as HTMLElement).getByText('—')).toBeTruthy();
    // S-177: "Needs attention" — one action card per queue, most urgent first (past the SLA, a stuck run, an off-platform payment)
    const grid = screen.getByRole('region', { name: /Needs attention/ });
    const titles = within(grid).getAllByRole('heading', { level: 3 }).map(h => h.textContent);
    expect(titles).toEqual(['Urgent: 7 applications to review', 'Urgent: 1 stuck delivery run', 'Urgent: 5 trust & safety flags', 'Due soon: 5 flagged listings', 'Due soon: 3 disputes for an agent', '41 sellers below a floor']);
    const applications = within(grid).getByRole('link', { name: 'Review applications' });
    expect(applications.className).toContain('btn-primary');
    expect(grid.textContent).toContain('oldest 3.0 d · SLA 2 d');
    expect(grid.textContent).toContain('SLA 4 h · oldest 2 h');
    expect(grid.textContent).toContain('Oldest overdue 12 min');
    expect(grid.textContent).toContain('includes off-platform payment');
    expect(within(grid).getByRole('link', { name: 'Vet listings' }).className).toContain('btn-secondary');
    expect(screen.getByText('27 / 41')).toBeTruthy();
    expect(screen.getByText('Orders in tonight’s Calgary pool')).toBeTruthy();
    expect(screen.getByText('312 · closes 5:19 p.m.')).toBeTruthy();
    expect(screen.getByText('$418,220.00')).toBeTruthy();
    await user.click(applications);
    await waitFor(() => expect(router.state.location.pathname).toBe('/verification'));
  });

  it('shows only the queues the role can work, and says when nothing needs attention', async () => {
    staffApi(['analyst'], c => (c.url.includes('/api/v1/console/overview') ? { body: overview() } : undefined));
    renderConsole('/');
    expect(await screen.findByText('Nothing needs attention in this scope.')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Review applications' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Dispute rate/ })).toBeNull();
  });

  it('follows the top bar’s scope, and a link’s own province and market (S-177)', async () => {
    const calls = staffApi(['finance'], c => (c.url.includes('/api/v1/console/overview') ? { body: overview() } : undefined));
    const user = userEvent.setup({ delay: null });
    renderConsole('/?province=AB');
    await screenReady();
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/v1/console/overview?province=AB'))).toBe(true));
    await user.click(screen.getByRole('button', { name: /^Change the market/ }));
    await user.click(screen.getByRole('menuitemradio', { name: 'Calgary' }));
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/v1/console/overview?province=AB&market=calgary'))).toBe(true));
    const last = calls.filter(c => c.url.includes('/console/overview')).at(-1);
    expect(last?.headers['X-Console-Role']).toBe('finance');
  });

  it('shows what isn’t measured yet and an empty day without inventing numbers', async () => {
    staffApi(['admin'], c => (c.url.includes('/api/v1/console/overview')
      ? { body: overview({ kpis: { gmvCents: 0, previousGmvCents: 0, revenueCents: 0, orders: 0, bookings: 0, onTimeRatio: null, disputeRate: null, averageDeliveryFeeCents: null }, live: { couriersOnRuns: 0, couriersActive: 0, providersOnJobs: 0, escrowHeldCents: 0, pools: [] } }) }
      : undefined));
    renderConsole('/');
    expect(await screen.findByText('GMV · week')).toBeTruthy();
    expect(screen.getByText('On-time').nextElementSibling?.textContent).toBe('—');
    expect(screen.queryByText(/vs last week/)).toBeNull();
    expect(screen.queryByText(/pool/)).toBeNull();
  });

  it('shows an error with retry when the overview fails', async () => {
    staffApi(['admin'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500, body: { detail: 'boom' } } : undefined));
    renderConsole('/');
    expect(await screen.findByText("The overview couldn't load. Try again.")).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
  });

  it('formats French figures', () => {
    expect(compactMoney(21_200_000, 'fr')).toBe('212 k $');
    expect(compactMoney(271, 'en')).toBe('$2.71');
  });
});
