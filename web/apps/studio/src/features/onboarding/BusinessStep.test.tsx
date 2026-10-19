import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TAXONOMY, onboarding } from '../../test/fixtures';
import { LEGAL_FORMS, isLegalForms } from '../../test/legalForms';
import { mockFetch, renderWithProviders, type Call } from '../../test/render';
import { BusinessStep } from './BusinessStep';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

/** user-event with no timer between keystrokes: typing costs one pass, not a macrotask per character. */
const user = () => userEvent.setup({ delay: null });

beforeEach(() => vi.unstubAllGlobals());

/** The wizard's two steps around one mounted form: Business → Categories (and Back). */
function Wizard({ o, onDone, start }: { o: ReturnType<typeof onboarding>; onDone: () => void; start: 'business' | 'categories' }) {
  const [part, setPart] = useState(start);
  return <BusinessStep onboarding={o} part={part} onBack={() => setPart('business')} onNext={() => setPart('categories')} onDone={onDone} />;
}

function setup(respond?: (c: Call) => { status?: number; body?: unknown } | undefined, o = onboarding(), start: 'business' | 'categories' = 'business') {
  const onDone = vi.fn();
  const calls = mockFetch(c => respond?.(c) ?? (c.url.startsWith('/api/v1/onboarding/taxonomy') ? { body: TAXONOMY } : undefined));
  renderWithProviders(<Wizard o={o} onDone={onDone} start={start} />);
  return { calls, onDone };
}

const type = (label: RegExp | string, text: string) => user().type(screen.getByRole('textbox', { name: label }), text);
/** The province's legal forms have arrived (the Business part waits for them). */
const ready = () => screen.findByRole('heading', { level: 1 }).then(() => waitFor(() => expect((screen.getByRole('button', { name: 'Continue to categories' }) as HTMLButtonElement).disabled).toBe(false)));

describe('BusinessStep', () => {
  it('shows every problem of the Business step and the attention summary (validation-rules.md messages)', async () => {
    const { calls } = setup();
    await ready();
    await user().click(screen.getByRole('button', { name: 'Continue to categories' }));
    expect(screen.getByText('Enter the name customers will see.')).toBeTruthy();
    expect(screen.getByText('Enter the registered legal name.')).toBeTruthy();
    expect(screen.getAllByText('This is required.').length).toBeGreaterThan(0); // owner legal name + address
    expect(screen.getByText(/things need attention\./).textContent).toContain('4 things need attention.');
    expect(screen.getByRole('heading', { name: 'Tell us about the business.' })).toBeTruthy(); // still on Business
    expect(calls.some(c => c.method === 'PUT')).toBe(false);
  });

  it('Categories asks for at least one, worded for the type', async () => {
    const saved = onboarding({ business: { displayName: 'Aspen Wrench', legalName: 'Amara Okafor', structure: 'sole', gstNumber: null, legalDetails: { owner_legal_name: 'Amara Okafor', address: '12 Glenmore Trail SW, Calgary', sin_collected_by_stripe: true }, principals: [], categories: [], profile: {}, documents: [] } });
    const { calls } = setup(undefined, saved, 'categories');
    expect(screen.getByRole('heading', { name: 'What services do you offer?' })).toBeTruthy();
    await user().click(screen.getByRole('button', { name: 'Continue to verification' }));
    expect(screen.getByText('Pick at least one service.')).toBeTruthy();
    expect(calls.some(c => c.method === 'PUT')).toBe(false);
  });

  it('requires GST for corporations and checks its format', async () => {
    setup();
    await ready();
    await user().selectOptions(screen.getByRole('combobox', { name: 'Business structure' }), 'corp_prov');
    await user().click(screen.getByRole('button', { name: 'Continue to categories' }));
    expect(screen.getByText('Required for corporations, co-ops and non-profits.')).toBeTruthy();
    await type(/GST\/HST number/, '12345');
    expect(screen.getByText('Format is 9 digits + RT0001 (e.g. 123456789 RT0001).')).toBeTruthy();
    expect(screen.getByText('Directors & beneficial owners')).toBeTruthy();
  });

  it('limits categories per type and offers a suggestion when nothing matches', async () => {
    setup(c => (c.url.startsWith('/api/v1/onboarding/taxonomy') ? { body: { ...TAXONOMY, type: 'kitchen', limit: 3 } } : undefined), onboarding({ type: 'kitchen' }), 'categories');
    const picker = await screen.findByRole('combobox', { name: 'Type of food business' });
    await user().click(picker);
    for (const name of ['Mobile mechanic', 'Detailing', 'Dog walker']) await user().click(screen.getByRole('option', { name: new RegExp(name) }));
    expect(screen.getByRole('status').textContent).toContain('Limit reached');
    await user().type(picker, 'zzz');
    expect(screen.queryByRole('button', { name: /Suggest/ })).toBeNull(); // at the limit: no suggestion
    expect(screen.getByText('· 3 of 3 selected')).toBeTruthy();
  });

  it('sends business + categories in one PUT from Categories, and takes server 422s back to the Business fields', async () => {
    const { calls, onDone } = setup(c => (c.method === 'PUT'
      ? { status: 422, body: { errors: [{ field: 'legalDetails.address', rule: 'length', message: 'Enter the full address.' }, { field: 'displayName', rule: 'length', message: 'At least 2 characters.' }] } }
      : undefined));
    await ready();
    await type(/Business name/, 'Aspen Wrench');
    await type('Legal entity', 'Amara Okafor');
    await type(/Legal name of owner/, 'Amara Okafor');
    await type(/Home \/ business address/, '12 Glenmore Trail SW, Calgary');
    await user().click(screen.getByRole('button', { name: 'Continue to categories' }));
    const picker = await screen.findByRole('combobox', { name: 'Services you offer' });
    await expectNoAxeViolations(document.body); // S-109
    await user().click(picker);
    await user().click(screen.getByRole('option', { name: /Mobile mechanic/ }));
    await user().click(within(screen.getByRole('listbox').parentElement!).getByRole('button', { name: 'Done' }));
    await user().click(screen.getByRole('button', { name: 'Continue to verification' }));

    await waitFor(() => expect(calls.some(c => c.method === 'PUT')).toBe(true));
    const body = calls.find(c => c.method === 'PUT')!.body as Record<string, unknown>;
    expect(body).toMatchObject({
      displayName: 'Aspen Wrench', legalName: 'Amara Okafor', structure: 'sole', categoryIds: ['service.automotive.mobile-mechanic'],
      legalDetails: { owner_legal_name: 'Amara Okafor', address: '12 Glenmore Trail SW, Calgary', sin_collected_by_stripe: true },
      principals: [],
    });
    expect(await screen.findByText('Enter the full address.')).toBeTruthy();
    expect(screen.getByText('At least 2 characters.')).toBeTruthy();
    expect(onDone).not.toHaveBeenCalled();
  });
});

/** The legal details are the province's (region data served by the api); the Studio names no province itself. */
describe('BusinessStep · legal details per province', () => {
  const inProvince = (province: keyof typeof LEGAL_FORMS, locale: 'en' | 'fr' = 'en') => {
    const calls = mockFetch(c => (isLegalForms(c.url) ? { body: LEGAL_FORMS[province] } : c.url.startsWith('/api/v1/onboarding/taxonomy') ? { body: TAXONOMY } : undefined));
    renderWithProviders(<BusinessStep onboarding={onboarding({ province })} part="business" onBack={() => {}} onNext={() => {}} onDone={() => {}} />, { locale });
    return calls;
  };
  const structure = (value: string) => user().selectOptions(screen.getByRole('combobox', { name: /Business structure|Structure de l'entreprise/ }), value);

  it.each([
    ['AB', 'Corporate access number', 'Alberta Corporate Registry', 'Business Corporations Act (Alberta)'],
    ['ON', 'Ontario Corporation Number (OCN)', 'Ontario Business Registry', 'Business Corporations Act (Ontario)'],
    ['BC', 'Incorporation number', 'BC Registries and Online Services', 'Business Corporations Act (British Columbia)'],
    ['QC', 'Québec enterprise number (NEQ)', 'Registraire des entreprises du Québec', 'Business Corporations Act (Québec)'],
  ] as const)('%s: the provincial corporation asks for its %s, names its registry and act, and passes axe', async (province, label, registry, law) => {
    const calls = inProvince(province);
    await ready();
    await structure('corp_prov');
    expect(screen.getByRole('textbox', { name: new RegExp(label.replace(/[()]/g, '\\$&')) })).toBeTruthy();
    expect(screen.getByText(`Registry: ${registry}`)).toBeTruthy();
    expect(screen.getByText(law)).toBeTruthy();
    expect(calls.some(c => isLegalForms(c.url))).toBe(true);
    await expectNoAxeViolations(document.body);
  });

  it('shows the province\'s format message, help and example (Québec, in French)', async () => {
    inProvince('QC', 'fr');
    await waitFor(() => expect((screen.getByRole('button', { name: /catégories/i }) as HTMLButtonElement).disabled).toBe(false));
    await structure('corp_prov');
    const neq = screen.getByRole('textbox', { name: /Numéro d'entreprise du Québec \(NEQ\)/ });
    expect((neq as HTMLInputElement).placeholder).toBe('1171234567');
    expect(screen.getByText("Attribué à l'immatriculation au Registraire des entreprises.")).toBeTruthy();
    await user().type(neq, '123');
    await user().tab();
    expect(await screen.findByText('Le NEQ compte 10 chiffres.')).toBeTruthy();
    await expectNoAxeViolations(document.body);
  });

  it('asks for the agent for service only where and when the province does (Ontario: from outside Canada)', async () => {
    inProvince('ON');
    await ready();
    await structure('corp_ex');
    const home = screen.getByRole('combobox', { name: /Home jurisdiction/ }) as HTMLSelectElement;
    expect([...home.options].map(o => o.value)).not.toContain('ON');
    await user().selectOptions(home, 'BC');
    const group = screen.getByRole('group', { name: /Agent for service in Ontario/ });
    expect(within(group).getByText('· if it applies', { exact: false })).toBeTruthy();
    await user().selectOptions(home, 'US');
    expect(within(screen.getByRole('group', { name: /Agent for service in Ontario/ })).getAllByText('· required', { exact: false }).length).toBeGreaterThan(0);
  });

  it('sends the generic keys (British Columbia sole proprietor with a registered trade name)', async () => {
    const calls = mockFetch(c => (isLegalForms(c.url) ? { body: LEGAL_FORMS.BC } : c.url.startsWith('/api/v1/onboarding/taxonomy') ? { body: TAXONOMY } : undefined));
    renderWithProviders(<Wizard o={onboarding({ province: 'BC' })} onDone={() => {}} start="business" />);
    await ready();
    await type(/Business name/, 'Aspen Wrench');
    await type('Legal entity', 'Amara Okafor');
    await type(/Legal name of owner/, 'Amara Okafor');
    await type(/Trade name \(if any\)/, 'Aspen Wrench');
    await type(/Registration number \(FM\)/, 'fm 1016176');
    await type(/Home \/ business address/, '12 Main St, Unit 4');
    await user().click(screen.getByRole('button', { name: 'Continue to categories' }));
    const picker = await screen.findByRole('combobox', { name: 'Services you offer' });
    await user().click(picker);
    await user().click(screen.getByRole('option', { name: /Mobile mechanic/ }));
    await user().click(within(screen.getByRole('listbox').parentElement!).getByRole('button', { name: 'Done' }));
    await user().click(screen.getByRole('button', { name: 'Continue to verification' }));
    await waitFor(() => expect(calls.some(c => c.method === 'PUT')).toBe(true));
    expect((calls.find(c => c.method === 'PUT')!.body as { legalDetails: unknown }).legalDetails).toEqual({
      owner_legal_name: 'Amara Okafor', trade_name: 'Aspen Wrench', trade_name_registration: 'FM1016176', sin_collected_by_stripe: true, address: '12 Main St, Unit 4',
    });
  });
});
