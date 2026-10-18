import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TAXONOMY, onboarding } from '../../test/fixtures';
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

describe('BusinessStep', () => {
  it('shows every problem of the Business step and the attention summary (validation-rules.md messages)', async () => {
    const { calls } = setup();
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
    await user().selectOptions(screen.getByRole('combobox', { name: 'Business structure' }), 'corp_ab');
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
