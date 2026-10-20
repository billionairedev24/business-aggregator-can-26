import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';

const setting = (o: Record<string, unknown>) => ({ regionId: 'prov-xa', kind: 'province', city: null, frenchFirst: null, frenchListings: null, effectiveFrenchFirst: false, effectiveFrenchListings: 'off', fromConfiguration: false, ...o });
const PROVINCE = {
  id: 'prov-xa', code: 'XA', names: { en: 'Exampleland', fr: 'Exempleland' }, stage: 'pilot', languages: ['en', 'fr'], courierModel: 'own', tax: { gst: 500 },
  timeZones: [], holidays: ['new_year'], privacyLaw: null, registries: [], waitlist: 0,
  markets: [{ id: 'mkt-sample', city: 'Sampleville', stage: 'pilot', zones: 0, waitlist: 0 }], zones: [], checklist: { taxProfile: true, holidays: true, registries: false, marketWithZones: false },
  languageRules: [setting({}), setting({ regionId: 'mkt-sample', kind: 'market', city: 'Sampleville', effectiveFrenchFirst: true, effectiveFrenchListings: 'require', fromConfiguration: true })],
};

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/regions')) return { body: { provinces: [PROVINCE] } };
    return undefined;
  });
}

describe('switchboard › language toggles (S-158, S-116)', () => {
  it('an admin turns a province French-first; a market follows it; configuration is shown', async () => {
    const calls = api(['admin'], c => (c.method === 'PUT' && c.url.endsWith('/prov-xa/languages') ? { body: PROVINCE } : undefined));
    renderConsole('/provinces');
    const card = (await screen.findByRole('heading', { name: 'Languages · Exampleland' })).closest('section, .nl-chartcard, div') as HTMLElement;
    const u = userEvent.setup({ delay: null });
    const province = within(document.body).getByText('The province').closest('.nl-rg-market') as HTMLElement;
    await u.selectOptions(within(province).getByLabelText('French first'), 'Yes');
    await u.selectOptions(within(province).getByLabelText('French listing text'), 'Warn the merchant');
    await u.click(within(province).getByRole('button', { name: 'Save the language rules of The province' }));
    await waitFor(() => expect(calls.find(c => c.method === 'PUT')?.body).toEqual({ frenchFirst: true, frenchListings: 'warn' }));
    const market = screen.getByText('Applies: French first · listings required').closest('.nl-rg-market') as HTMLElement;
    expect(within(market).getByText(/French first by configuration/)).toBeTruthy();
    expect(within(market).getByText('Applies: French first · listings required')).toBeTruthy();
    expect(card).toBeTruthy();
  });

  it('read-only for other roles', async () => {
    api(['admin'], c => (c.url.endsWith('/api/v1/console/me') ? { body: { userId: 'u', roles: [{ role: 'admin', screens: ['regions', 'profile', 'oncall', 'overview'], actions: [] }] } } : undefined));
    renderConsole('/provinces');
    await screen.findByRole('heading', { name: 'Languages · Exampleland' });
    expect(screen.getByText('Only admins change the language rules.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Save the language rules/ })).toBeNull();
  });
});
