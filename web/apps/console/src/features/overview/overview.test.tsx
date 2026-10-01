import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { overview } from '../../test/fixtures';
import { renderConsole, staffApi } from '../../test/render';
import { compactMoney } from './Overview';

describe('console overview (S-91, design 03)', () => {
  it('shows the day, the headline, the KPIs, GMV, health, the work queue and live now', async () => {
    staffApi(['admin'], c => (c.url.includes('/api/v1/console/overview') ? { body: overview() } : undefined));
    renderConsole('/');
    expect(await screen.findByRole('heading', { level: 1, name: '$212k GMV this week, 1,204 sellers, 7 verifications and 3 disputes waiting.' })).toBeTruthy();
    expect(screen.getByText('Tuesday 8 September · live')).toBeTruthy();
    const kpis: [string, string][] = [['$212k', 'GMV · week · +9% w/w'], ['$24.1k', 'net revenue · 11.4% blended take'], ['6,812', 'orders + bookings · 61% goods'],
      ['96.8%', 'on-time · target 95'], ['0.9%', 'dispute rate · target < 1.2'], ['$2.71', 'avg delivery fee paid']];
    for (const [value, label] of kpis) {
      expect(screen.getByText(label).previousElementSibling?.textContent, label).toBe(value);
    }
    expect(screen.getByRole('img', { name: 'GMV · 12 weeks' })).toBeTruthy();
    expect(screen.getByText('Goods in cyan, services in magenta')).toBeTruthy();
    expect(screen.getByText('184 ms')).toBeTruthy();
    expect(screen.getByText('27k conns')).toBeTruthy();
    expect(screen.getByText('1 offline')).toBeTruthy();
    expect(within(screen.getByText('Kafka lag').closest('.nl-ov-tile') as HTMLElement).getByText('—')).toBeTruthy();
    const verify = screen.getByRole('link', { name: /7 seller verifications/ });
    expect(verify.getAttribute('href')).toBe('/verification');
    expect(verify.textContent).toContain('oldest 3.0 d · SLA 2 d');
    expect(screen.getByRole('link', { name: /5 flagged listings/ }).textContent).toContain('SLA 4 h · oldest 2 h');
    expect(screen.getByRole('link', { name: /1 stuck delivery run/ }).textContent).toContain('12 min');
    expect(screen.getByRole('link', { name: /5 trust & safety flags/ }).textContent).toContain('includes off-platform payment');
    expect(screen.getByText('27 / 41')).toBeTruthy();
    expect(screen.getByText('Orders in tonight’s Calgary pool')).toBeTruthy();
    expect(screen.getByText('312 · closes 5:19 p.m.')).toBeTruthy();
    expect(screen.getByText('$418,220')).toBeTruthy();
  });

  it('lists every work item but links only the screens the role opens', async () => {
    staffApi(['analyst'], c => (c.url.includes('/api/v1/console/overview') ? { body: overview() } : undefined));
    renderConsole('/');
    expect(await screen.findByText('seller verifications')).toBeTruthy();
    expect(screen.queryByRole('link', { name: /seller verifications/ })).toBeNull();
  });

  it('filters by province and market from the region model', async () => {
    const calls = staffApi(['finance'], c => (c.url.includes('/api/v1/console/overview') ? { body: overview() } : undefined));
    const user = userEvent.setup({ delay: null });
    const { router } = renderConsole('/');
    await screen.findByRole('heading', { level: 1 });
    await user.selectOptions(screen.getByRole('combobox', { name: 'Province' }), 'AB');
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/v1/console/overview?province=AB'))).toBe(true));
    expect(router.state.location.search).toEqual({ province: 'AB' });
    expect(within(screen.getByRole('combobox', { name: 'Market' })).getAllByRole('option').map(o => o.textContent)).toEqual(['All markets', 'Calgary']);
    await user.selectOptions(screen.getByRole('combobox', { name: 'Market' }), 'calgary');
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
    expect(screen.getByText('on-time · target 95').previousElementSibling?.textContent).toBe('—');
    expect(screen.queryByText(/pool/)).toBeNull();
  });

  it('shows an error with retry when the overview fails', async () => {
    staffApi(['admin'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500, body: { detail: 'boom' } } : undefined));
    renderConsole('/');
    expect(await screen.findByText("The overview couldn't load. Try again.")).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
  });

  it('formats French figures', () => {
    expect(compactMoney(21_200_000, 'fr')).toMatch(/212\s?k\s?\$/);
    expect(compactMoney(271, 'en')).toBe('$2.71');
  });
});
