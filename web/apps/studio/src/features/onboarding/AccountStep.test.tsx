import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { MerchantType } from '../shell/api';
import { onboarding } from '../../test/fixtures';
import { mockFetch, renderWithProviders } from '../../test/render';
import { AccountStep } from './AccountStep';
import { OnboardingLayout } from './OnboardingLayout';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

/** user-event with no timer between keystrokes: typing costs one pass, not a macrotask per character. */
const user = () => userEvent.setup({ delay: null });

beforeEach(() => vi.unstubAllGlobals());

const SESSION = { user: { id: 'U', firstName: 'Ravi', lastName: 'Sandhu', email: 'ravi@prairiewrench.ca', phone: '+1 (403) 555-0192', initials: 'RS', locale: 'en-CA', memberSince: '2026-05-02' }, acr: 'mfa' };

describe('AccountStep', () => {
  it('shows the type picker when no type was given, then the chosen type with Change', async () => {
    mockFetch(c => (c.url === '/bff/session' ? { body: SESSION } : undefined));
    const onTypeChange = vi.fn();
    function Harness() {
      const [type, setType] = useState<MerchantType | undefined>(undefined);
      return <AccountStep type={type} onboarding={undefined} isNew={false} onTypeChange={t => { onTypeChange(t); setType(t); }} onDone={() => {}} />;
    }
    renderWithProviders(<Harness />);
    expect(screen.getByRole('heading', { name: 'Set up your business.' })).toBeTruthy();
    expect((screen.getByRole('button', { name: /Continue as/ }) as HTMLButtonElement).disabled).toBe(true);
    await user().click(screen.getByRole('radio', { name: /Food · kitchen/ }));
    expect(onTypeChange).toHaveBeenCalledWith('kitchen');
    expect(screen.getByText('Kitchen · food')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Change' })).toBeTruthy();
    expect(screen.getByText('Next: business, then the full AHS food checklist.')).toBeTruthy();
  });

  it('existing customer: optional work email is validated, then the applicant is created', async () => {
    const onDone = vi.fn();
    const calls = mockFetch(c => {
      if (c.url === '/bff/session') return { body: SESSION };
      if (c.url === '/api/v1/merchants' && c.method === 'POST') return { status: 201, body: onboarding({ type: 'provider' }) };
      return undefined;
    });
    renderWithProviders(<AccountStep type="provider" onboarding={undefined} isNew={false} onTypeChange={() => {}} onDone={onDone} />);
    expect(await screen.findByText(/ravi@prairiewrench.ca/)).toBeTruthy();
    expect(screen.getByText('Security step-up.')).toBeTruthy();
    await user().type(screen.getByRole('textbox', { name: /Work email/ }), 'nope');
    await user().click(screen.getByRole('button', { name: 'Continue as Ravi → business details' }));
    expect(screen.getByText("That doesn't look like an email address.")).toBeTruthy();
    await user().clear(screen.getByRole('textbox', { name: /Work email/ }));
    await user().type(screen.getByRole('textbox', { name: /Work email/ }), 'ravi@prairiewrench.ca');
    await user().click(screen.getByRole('button', { name: 'Continue as Ravi → business details' }));
    await waitFor(() => expect(onDone).toHaveBeenCalledWith('01J9ZD3V00000000000000TST1', 'provider'));
    expect(calls.find(c => c.method === 'POST')!.body).toEqual({ type: 'provider', province: 'AB', workEmail: 'ravi@prairiewrench.ca' });
  });

  it('brand-new account (07d): Business Terms must be accepted; waitlist provinces listed', async () => {
    mockFetch(c => (c.url === '/bff/session' ? { body: SESSION } : undefined));
    renderWithProviders(<AccountStep type="provider" onboarding={undefined} isNew onTypeChange={() => {}} onDone={() => {}} />);
    // the provinces and their launch status come from the region model (S-134; test data: src/test/regions.ts)
    expect(await screen.findByRole('option', { name: 'Ontario (waitlist)' })).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    await user().click(screen.getByRole('button', { name: /Continue as/ }));
    expect(screen.getByText('You need to accept the Business Terms.')).toBeTruthy();
    expect((screen.getByRole('link', { name: 'Business Terms' }) as HTMLAnchorElement).target).toBe('_blank');
  });
});

describe('OnboardingLayout rail', () => {
  it('names the steps per type and only lets you reach steps you got to', () => {
    mockFetch(c => (c.url === '/bff/session' ? { body: SESSION } : undefined));
    renderWithProviders(<OnboardingLayout step="business" type="seller" onboarding={onboarding({ type: 'seller', step: 'business' })} onGo={() => {}}><p>body</p></OnboardingLayout>);
    const store = screen.getByRole('button', { name: /Store/ });
    expect(store.textContent).toContain('brand & sections');
    expect((store as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByRole('button', { name: /^Business/ }).getAttribute('aria-current')).toBe('step');
    expect((screen.getByRole('button', { name: /^Account you/ }) as HTMLButtonElement).disabled).toBe(false);
    expect(screen.getByText('KYC, GST, product permits')).toBeTruthy();
  });
});
