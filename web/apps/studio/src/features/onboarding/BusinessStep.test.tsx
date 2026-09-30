import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TAXONOMY, onboarding } from '../../test/fixtures';
import { mockFetch, renderWithProviders, type Call } from '../../test/render';
import { BusinessStep } from './BusinessStep';

/** user-event with no timer between keystrokes: typing costs one pass, not a macrotask per character. */
const user = () => userEvent.setup({ delay: null });

beforeEach(() => vi.unstubAllGlobals());

function setup(respond?: (c: Call) => { status?: number; body?: unknown } | undefined, o = onboarding()) {
  const onDone = vi.fn();
  const calls = mockFetch(c => respond?.(c) ?? (c.url.startsWith('/api/v1/onboarding/taxonomy') ? { body: TAXONOMY } : undefined));
  renderWithProviders(<BusinessStep onboarding={o} onBack={() => {}} onDone={onDone} />);
  return { calls, onDone };
}

const type = (label: RegExp | string, text: string) => user().type(screen.getByRole('textbox', { name: label }), text);

describe('BusinessStep', () => {
  it('shows every problem and the attention summary on submit (validation-rules.md messages)', async () => {
    const { calls } = setup();
    await user().click(screen.getByRole('button', { name: 'Continue to verification' }));
    expect(screen.getByText('Enter the name customers will see.')).toBeTruthy();
    expect(screen.getByText('Enter the registered legal name.')).toBeTruthy();
    expect(screen.getByText('Pick at least one service.')).toBeTruthy();
    expect(screen.getAllByText('This is required.').length).toBeGreaterThan(0); // owner legal name + address
    expect(screen.getByText(/things need attention\./).textContent).toContain('5 things need attention.');
    expect(calls.some(c => c.method === 'PUT')).toBe(false);
  });

  it('requires GST for corporations and checks its format', async () => {
    setup();
    await user().selectOptions(screen.getByRole('combobox', { name: 'Business structure' }), 'corp_ab');
    await user().click(screen.getByRole('button', { name: 'Continue to verification' }));
    expect(screen.getByText('Required for corporations, co-ops and non-profits.')).toBeTruthy();
    await type(/GST\/HST number/, '12345');
    expect(screen.getByText('Format is 9 digits + RT0001 (e.g. 123456789 RT0001).')).toBeTruthy();
    expect(screen.getByText('Directors & beneficial owners')).toBeTruthy();
  });

  it('limits categories per type and offers a suggestion when nothing matches', async () => {
    setup(c => (c.url.startsWith('/api/v1/onboarding/taxonomy') ? { body: { ...TAXONOMY, type: 'kitchen', limit: 3 } } : undefined), onboarding({ type: 'kitchen' }));
    const picker = await screen.findByRole('combobox', { name: 'Type of food business' });
    await user().click(picker);
    for (const name of ['Mobile mechanic', 'Detailing', 'Dog walker']) await user().click(screen.getByRole('option', { name: new RegExp(name) }));
    expect(screen.getByRole('status').textContent).toContain('Limit reached');
    await user().type(picker, 'zzz');
    expect(screen.queryByRole('button', { name: /Suggest/ })).toBeNull(); // at the limit: no suggestion
    expect(screen.getByText('· 3 of 3 selected')).toBeTruthy();
  });

  it('sends the business with legal details and maps server 422s onto the fields', async () => {
    const { calls, onDone } = setup(c => (c.method === 'PUT'
      ? { status: 422, body: { errors: [{ field: 'legalDetails.address', rule: 'length', message: 'Enter the full address.' }, { field: 'displayName', rule: 'length', message: 'At least 2 characters.' }] } }
      : undefined));
    await type(/Business name/, 'Aspen Wrench');
    await type('Legal entity', 'Amara Okafor');
    await type(/Legal name of owner/, 'Amara Okafor');
    await type(/Home \/ business address/, '12 Glenmore Trail SW, Calgary');
    const picker = await screen.findByRole('combobox', { name: 'Services you offer' });
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
