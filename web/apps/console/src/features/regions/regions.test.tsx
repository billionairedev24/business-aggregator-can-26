import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';
import { cents } from './Switchboard';
import type { Province } from './api';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const province = (o: Partial<Province>): Province => ({
  id: 'prov-ab', code: 'AB', names: { en: 'Alberta', fr: 'Alberta' }, stage: 'live', languages: ['en', 'fr'], courierModel: 'own', tax: { gst: 500 },
  timeZones: ['America/Edmonton'], holidays: ['new_year'], privacyLaw: 'ab_pipa', registries: ['alberta_corporate_registry'], waitlist: 0,
  markets: [{ id: 'mkt-calgary', city: 'Calgary', stage: 'live', lat: 51.04, lng: -114.07, radiusKm: 25, zones: 1, waitlist: 0 },
    { id: 'mkt-red-deer', city: 'Red Deer', stage: 'pilot', lat: 52.27, lng: -113.81, radiusKm: 15, zones: 0, waitlist: 18 }],
  zones: [{ id: 'z1', marketId: 'mkt-calgary', name: 'Beltline', runsPerDay: 3, feeStdCents: 299, feePlusCents: 0, minBasketCents: 2500, areaKm2: 2.1 }],
  checklist: { taxProfile: true, holidays: true, registries: true, marketWithZones: true }, ...o,
});
const BC = province({ id: 'prov-bc', code: 'BC', names: { en: 'British Columbia', fr: 'Colombie-Britannique' }, stage: 'pilot', tax: { gst: 500, pst: 700 }, courierModel: null,
  registries: [], markets: [{ id: 'mkt-vancouver', city: 'Vancouver', stage: 'pilot', zones: 0, waitlist: 9 }], zones: [],
  checklist: { taxProfile: true, holidays: true, registries: false, marketWithZones: false } });
const QC = province({ id: 'prov-qc', code: 'QC', names: { en: 'Quebec', fr: 'Québec' }, stage: 'off', languages: ['fr', 'en'], tax: { gst: 500, qst: 997.5 }, markets: [], zones: [] });
const BOARD = { provinces: [province({}), BC, QC] };

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/regions')) return { body: BOARD };
    if (c.method !== 'GET' && c.url.includes('/api/v1/console/regions')) return { body: province({}) };
    return undefined;
  });
}

describe('province switchboard (S-84, design 03)', () => {
  it('lists the provinces with tax, languages and stage, and the live one’s markets and zones', async () => {
    api(['admin']);
    renderConsole('/provinces');
    expect(await screen.findByRole('heading', { level: 1, name: 'Turning on a province is a record, not a release.' })).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    const list = screen.getByRole('list');
    expect(within(list).getByText('GST 5% + PST 7% · en · fr')).toBeTruthy();
    expect(within(list).getByText('GST 5% + QST 9.975% · fr · en')).toBeTruthy();
    expect(within(list).getAllByText('Live').length).toBe(1);
    expect(screen.getByRole('heading', { level: 2, name: 'Alberta' })).toBeTruthy();
    expect(screen.getByText('Markets inside Alberta · each has its own stage')).toBeTruthy();
    expect(screen.getByText('0 zones · 18 on waitlist')).toBeTruthy();
    expect(screen.getByText('Delivery zones in Alberta · 1')).toBeTruthy();
    expect(screen.getByText('3 runs/day · $2.99 · Plus Free · min $25.00 · polygon · 2.1 km²')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Save & keep live' })).toBeTruthy();
  });

  it('keeps a market under its province’s stage', async () => {
    api(['admin']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/provinces?province=BC');
    await screen.findByRole('heading', { level: 2, name: 'British Columbia' });
    const stages = screen.getByRole('radiogroup', { name: 'Stage of Vancouver' });
    expect((within(stages).getByRole('radio', { name: 'Live' }) as HTMLButtonElement).disabled).toBe(true);
    expect((within(stages).getByRole('radio', { name: 'Waitlist' }) as HTMLButtonElement).disabled).toBe(false);
    await user.click(within(stages).getByRole('radio', { name: 'Waitlist' }));
    const dialog = await screen.findByRole('alertdialog', { name: 'Vancouver → Waitlist' });
    expect((within(dialog).getByRole('button', { name: 'Confirm' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('turns a province live only after the checklist and a typed confirmation', async () => {
    const calls = api(['admin'], c => (c.method === 'POST' && c.url.includes('/provinces/BC/stage')
      ? { status: 409, body: { code: 'not_ready', detail: 'Complete the checklist before going live.' } } : undefined));
    const user = userEvent.setup({ delay: null });
    renderConsole('/provinces?province=BC');
    await screen.findByRole('heading', { level: 2, name: 'British Columbia' });
    await user.click(within(screen.getByRole('radiogroup', { name: 'Rollout stage' })).getByRole('radio', { name: /Live/ }));
    await user.click(screen.getByRole('button', { name: 'Save & keep live' }));
    const dialog = await screen.findByRole('alertdialog', { name: 'British Columbia → Live' });
    expect(within(dialog).getByText('Business registries · missing')).toBeTruthy();
    expect(within(dialog).getByText('A market with delivery zones · missing')).toBeTruthy();
    expect(within(dialog).getByText('Tax profile · done')).toBeTruthy();
    const confirm = within(dialog).getByRole('button', { name: 'Confirm' }) as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    await user.type(within(dialog).getByRole('textbox', { name: /Confirmation/ }), 'bc');
    expect(confirm.disabled).toBe(false);
    await user.click(confirm);
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/provinces/BC/stage'))?.body).toEqual({ stage: 'live', confirm: 'bc' }));
    expect(await within(dialog).findByText('Complete the checklist before going live.')).toBeTruthy();
  });

  it('adds a market and saves a zone from GeoJSON', async () => {
    const calls = api(['admin']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/provinces');
    await screen.findByRole('heading', { level: 2, name: 'Alberta' });
    await user.type(screen.getByRole('textbox', { name: 'Add a city or area…' }), 'Lethbridge');
    await user.type(screen.getByRole('textbox', { name: 'Centre latitude' }), '49.69');
    await user.type(screen.getByRole('textbox', { name: 'Centre longitude' }), '-112.84');
    await user.click(screen.getByRole('button', { name: 'Add market' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/api/v1/console/regions/markets'))?.body).toEqual({ province: 'AB', city: 'Lethbridge', lat: 49.69, lng: -112.84, radiusKm: 15 }));
    await user.click(screen.getByRole('button', { name: 'Edit Beltline' }));
    const form = screen.getByRole('group', { name: 'Edit zone · Beltline' });
    await user.clear(within(form).getByRole('textbox', { name: 'Standard fee' }));
    await user.type(within(form).getByRole('textbox', { name: 'Standard fee' }), '3.49');
    await user.click(within(form).getByRole('button', { name: 'Import GeoJSON' }));
    await user.type(within(form).getByRole('textbox', { name: 'GeoJSON polygon (lng, lat)' }), '{{"type":"Polygon"}');
    await user.click(within(form).getByRole('button', { name: 'Save zone' }));
    await waitFor(() => expect(calls.find(c => c.method === 'PUT' && c.url.endsWith('/zones/z1'))?.body).toMatchObject({
      marketId: 'mkt-calgary', name: 'Beltline', runsPerDay: 3, feeStdCents: 349, feePlusCents: 0, minBasketCents: 2500, boundary: '{"type":"Polygon"}' }));
  });

  it('removes a zone after a confirmation', async () => {
    const calls = api(['admin']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/provinces');
    await screen.findByRole('heading', { level: 2, name: 'Alberta' });
    await user.click(screen.getByRole('button', { name: 'Remove Beltline' }));
    const dialog = await screen.findByRole('alertdialog', { name: 'Remove Beltline?' });
    await user.click(within(dialog).getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(calls.some(c => c.method === 'DELETE' && c.url.endsWith('/api/v1/console/regions/zones/z1'))).toBe(true));
  });

  it('is denied to every role but admin, and speaks French', async () => {
    staffApi(['trust_safety'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500 } : undefined));
    const first = renderConsole('/provinces');
    expect(await screen.findByText(/Not available in this role\./)).toBeTruthy();
    first.unmount();
    api(['admin']);
    renderConsole('/provinces', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Ouvrir une province est une inscription, pas une mise en production.' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Enregistrer et garder en service' })).toBeTruthy();
  });

  it('reads typed dollars', () => {
    expect(cents('$2.99', 'Free')).toBe(299);
    expect(cents('Free', 'Free')).toBe(0);
    expect(cents('', 'Free')).toBeNull();
    expect(cents('2,50', 'Gratuit')).toBe(250);
  });
});
