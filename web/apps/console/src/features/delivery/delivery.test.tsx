import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call, screenReady } from '../../test/render';
import { fit, project, tiles, toView } from './mapGeometry';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const NOW = Date.now();
const iso = (minutes: number) => new Date(NOW + minutes * 60_000).toISOString();
const run = (o: Record<string, unknown>) => ({ id: 'r1', label: 'R-611', part: 1, market: 'Calgary', kind: 'pooled', state: 'en_route', courier: { id: 'c1', userId: 'u1', name: 'Jordan' },
  orders: 6, stopsDone: 2, stopsTotal: 6, nextEta: iso(20), late: false, heuristic: 'nearest', ...o });
const RUNS = [run({}), run({ id: 'r2', label: 'R-608', courier: { id: 'c2', userId: 'u2', name: 'Sam' }, late: true, nextEta: iso(-12) }),
  run({ id: 'r3', label: 'R-615', state: 'planned', courier: { id: 'c3', userId: 'u3', name: 'Alex' }, stopsTotal: 5 })];
const courier = (o: Record<string, unknown>) => ({ id: 'c1', userId: 'u1', name: 'Jordan', market: 'Calgary', vehicle: 'ebike', status: 'on_run', active: true,
  shift: { id: 's1', startsAt: iso(-60), endsAt: iso(300), state: 'on' }, runId: 'r1', position: { lat: 51.04, lng: -114.07, at: iso(-1) }, ...o });
const COURIERS = [courier({}), courier({ id: 'c2', userId: 'u2', name: 'Sam', runId: 'r2', position: { lat: 51.05, lng: -114.1, at: iso(-12) } }),
  courier({ id: 'c3', userId: 'u3', name: 'Alex', runId: 'r3', status: 'on_run' }), courier({ id: 'c4', userId: 'u4', name: 'Maya', status: 'available', runId: null, position: null })];
const MAP = { market: { id: 'calgary', city: 'Calgary', province: 'AB', lat: 51.0447, lng: -114.0719 },
  zones: [{ id: 'z1', marketId: 'calgary', name: 'Beltline', ring: [{ lat: 51.03, lng: -114.09 }, { lat: 51.03, lng: -114.06 }, { lat: 51.045, lng: -114.06 }, { lat: 51.03, lng: -114.09 }],
    runsPerDay: 3, feeStdCents: 299, feePlusCents: 0, minBasketCents: 2500 }], basemap: null };

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.url.includes('/api/v1/console/fulfilment/runs?')) return { body: { items: RUNS } };
    if (c.url.includes('/api/v1/console/fulfilment/couriers?')) return { body: { items: COURIERS } };
    if (c.url.includes('/api/v1/console/delivery/map')) return { body: MAP };
    return undefined;
  });
}

/** A Data Table row (table row or card) by its primary text. */
const card = (text: string, button?: string) => screen.getAllByText(text).map(e => e.closest('tr, .nl-dt-card') as HTMLElement | null)
  .find(e => e && (!button || within(e).queryByRole('button', { name: button }))) as HTMLElement;

describe('delivery ops (S-81, design 03)', () => {
  it('shows the market’s couriers, runs, the map and zone pricing', async () => {
    const calls = api(['dispatch']);
    renderConsole('/delivery');
    expect(await screen.findByRole('heading', { level: 1, name: '3 couriers on 3 runs · 66.7% on time · 1 stuck' })).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('Delivery operations · Calgary')).toBeTruthy();
    expect(calls.some(c => c.url.endsWith('/api/v1/console/fulfilment/runs?market=Calgary'))).toBe(true);
    expect(calls.some(c => c.url.endsWith('/api/v1/console/delivery/map?market=calgary'))).toBe(true);
    const map = screen.getByRole('img', { name: 'Couriers and zones in Calgary' });
    expect(map.querySelectorAll('[data-courier]')).toHaveLength(3);
    // S-146 (WCAG 1.4.1): a stuck courier differs by shape too (a diamond, not a dot), and the legend shows both
    expect(map.querySelectorAll('path[data-courier][data-stuck="true"]')).toHaveLength(1);
    expect(map.querySelectorAll('circle[data-courier][data-stuck="true"]')).toHaveLength(0);
    const legend = map.closest('figure')!.querySelector('figcaption')!;
    expect(legend.querySelector('svg path[data-stuck="true"]')).not.toBeNull();
    expect(legend.querySelector('svg circle[data-stuck="false"]')).not.toBeNull();
    expect(legend.textContent).toContain('stuck > 10 min');
    expect(within(map).getByText('Beltline')).toBeTruthy();
    expect(screen.getByText('Tonight’s pooled runs')).toBeTruthy();
    expect(screen.getByText('Stuck · 12 min overdue')).toBeTruthy();
    expect(screen.getAllByText('On time').length).toBeGreaterThan(0);
    expect(screen.getByText('Zone pricing & unit economics')).toBeTruthy();
    expect(screen.getByText('$2.99')).toBeTruthy();
    expect(screen.getByText('Free')).toBeTruthy();
    expect(screen.getByText('1 courier on shift hasn’t shared a position yet.')).toBeTruthy();
  });

  it('reassigns a run to a free courier', async () => {
    const calls = api(['dispatch'], c => (c.method === 'POST' && c.url.includes('/runs/r3/assign') ? { body: run({ id: 'r3', state: 'planned' }) } : undefined));
    const user = userEvent.setup({ delay: null });
    renderConsole('/delivery');
    await screenReady();
    const row = card('R-615');
    await user.click(within(row).getByRole('button', { name: 'Reassign' }));
    const dialog = await screen.findByRole('dialog', { name: 'Reassign R-615' });
    expect(within(dialog).getByRole('combobox')).toBeTruthy();
    await user.click(within(dialog).getByRole('button', { name: 'Reassign' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/runs/r3/assign'))).toBe(true));
    expect(calls.find(c => c.url.endsWith('/runs/r3/assign'))?.body).toEqual({ courierId: 'c4' });
  });

  it('shows the api’s refusal when a run has started', async () => {
    api(['admin'], c => (c.method === 'POST' && c.url.includes('/assign') ? { status: 409, body: { code: 'run_started', detail: "This run has started; it can't be reassigned." } } : undefined));
    const user = userEvent.setup({ delay: null });
    renderConsole('/delivery');
    await screenReady();
    const row = card('R-608');
    await user.click(within(row).getByRole('button', { name: 'Reassign' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Reassign' }));
    expect(await within(dialog).findByText("This run has started; it can't be reassigned.")).toBeTruthy();
  });

  it('pauses a courier with a reason and resumes a paused one', async () => {
    const paused = { ...COURIERS[3], active: false };
    const calls = api(['dispatch'], c => {
      if (c.method === 'POST' && c.url.includes('/couriers/c1/pause')) return { body: { ...COURIERS[0], active: false } };
      if (c.method === 'POST' && c.url.includes('/couriers/c4/resume')) return { body: COURIERS[3] };
      if (c.url.includes('/api/v1/console/fulfilment/couriers?')) return { body: { items: [COURIERS[0], paused] } };
      return undefined;
    });
    const user = userEvent.setup({ delay: null });
    renderConsole('/delivery');
    await screenReady();
    await user.click(within(card('Jordan', 'Pause')).getByRole('button', { name: 'Pause' }));
    const dialog = await screen.findByRole('dialog', { name: 'Pause Jordan' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Reason' }), 'App offline mid-run');
    await user.click(within(dialog).getByRole('button', { name: 'Pause' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/couriers/c1/pause'))?.body).toEqual({ reason: 'App offline mid-run' }));
    await user.click(within(card('Maya', 'Resume')).getByRole('button', { name: 'Resume' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/couriers/c4/resume'))).toBe(true));
  });

  it('is view only for a role without the dispatch action and refused to roles that don’t open it', async () => {
    api(['dispatch'], c => (c.url.includes('/api/v1/console/me') && !c.url.includes('role-view')
      ? { body: { userId: 'u', roles: [{ role: 'dispatch', screens: ['overview', 'delivery'], actions: [] }] } } : undefined));
    const first = renderConsole('/delivery');
    await screenReady();
    expect(screen.queryByRole('button', { name: 'Reassign' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull();
    first.unmount();
    staffApi(['support'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500 } : undefined));
    renderConsole('/delivery');
    expect(await screen.findByText(/Not available in this role\./)).toBeTruthy();
  });

  it('projects with Web Mercator and picks basemap tiles from the data', () => {
    expect(project({ lat: 0, lng: 0 })).toEqual({ x: 128, y: 128 });
    const b = fit([{ lat: 51.03, lng: -114.09 }, { lat: 51.06, lng: -114.05 }])!;
    const a = toView(b, { lat: 51.03, lng: -114.09 }), c = toView(b, { lat: 51.06, lng: -114.05 });
    expect(a.x).toBeLessThan(c.x);
    expect(a.y).toBeGreaterThan(c.y);
    for (const p of [a, c]) { expect(p.x).toBeGreaterThan(0); expect(p.x).toBeLessThan(400); }
    const ts = tiles(b, 'https://tiles.example/{z}/{x}/{y}.png');
    expect(ts.length).toBeGreaterThan(0);
    expect(ts[0]?.href).toMatch(/^https:\/\/tiles\.example\/\d+\/\d+\/\d+\.png$/);
  });
});
