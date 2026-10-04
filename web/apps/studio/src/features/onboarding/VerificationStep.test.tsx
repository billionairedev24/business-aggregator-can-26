import { ProvincePlace } from '../shell/place';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { onboarding } from '../../test/fixtures';
import { mockFetch, renderWithProviders } from '../../test/render';
import type { Onboarding, OwnerIdentity } from './api';
import { CheckDialog } from './CheckDialog';
import { IdentityDialog } from './IdentityDialog';
import { IdentityDoneScreen } from './IdentityDoneScreen';
import { ReviewStep } from './ReviewStep';
import { VerificationStep } from './VerificationStep';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

/** user-event with no timer between keystrokes: typing costs one pass, not a macrotask per character. */
const user = () => userEvent.setup({ delay: null });

vi.mock('@tanstack/react-router', async orig => ({
  ...(await orig<typeof import('@tanstack/react-router')>()),
  Link: ({ children, className }: { children: React.ReactNode; className?: string }) => <a className={className} href="#">{children}</a>,
}));

beforeEach(() => vi.unstubAllGlobals());

const done = (o: Onboarding, key: string, patch: Partial<Onboarding['checklist'][number]> = {}): Onboarding => ({
  ...o, checklist: o.checklist.map(c => (c.key === key ? { ...c, status: 'verified', reference: 'passed', ...patch } : c)),
});

describe('CheckDialog choices (S-140)', () => {
  it('the returns policy options are one Tab stop; the arrow keys move and select', async () => {
    const onSubmit = vi.fn();
    const check = { id: 'V9', key: 'returns_policy', checkType: 'returns_policy', action: 'choose' as const, registry: null, status: 'todo' as const, reference: null, document: null, expiresOn: null, updatedAt: '2026-09-29T16:00:00Z' };
    renderWithProviders(<CheckDialog merchantId="M1" check={check} title="Returns policy" pending={false} errors={{}} onSubmit={onSubmit} onClose={() => {}} />);
    const group = screen.getByRole('radiogroup', { name: 'Returns policy' });
    const [standard, perishables] = within(group).getAllByRole('radio');
    expect([standard, perishables].map(r => r!.getAttribute('tabindex'))).toEqual(['0', '-1']);
    standard!.focus();
    await user().keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(perishables);
    expect(perishables!.getAttribute('aria-checked')).toBe('true');
    await user().keyboard('{ArrowDown}');
    expect(standard!.getAttribute('aria-checked')).toBe('true');
    await user().click(screen.getByRole('button', { name: 'Submit' }));
    expect(onSubmit).toHaveBeenCalledWith({ choice: 'standard' });
  });
});

describe('VerificationStep', () => {
  it('runs instant checks through the api and shows the done label', async () => {
    const o = onboarding();
    const calls = mockFetch(c => (c.url.endsWith('/verifications/V5/complete') ? { body: done(o, 'bank') } : undefined));
    renderWithProviders(<VerificationStep onboarding={o} onBack={() => {}} onSubmitted={() => {}} />);
    expect(screen.getByText('0 of 6 complete')).toBeTruthy();
    await user().click(screen.getByRole('button', { name: 'Connect bank' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/V5/complete'))).toBe(true));
    expect(((screen.getByRole('button', { name: 'Submit for review' })) as HTMLButtonElement).disabled).toBe(true);
  });

  it('asks for the licence number in a dialog and shows the server message', async () => {
    const o = onboarding();
    mockFetch(c => (c.url.endsWith('/V3/complete') ? { status: 422, body: { errors: [{ field: 'reference', rule: 'required', message: 'Enter the licence number.' }] } } : undefined));
    // the onboarding screen fills the place from the application's province (region model; test data: Alberta)
    renderWithProviders(<ProvincePlace code="AB"><VerificationStep onboarding={o} onBack={() => {}} onSubmitted={() => {}} /></ProvincePlace>);
    expect(screen.getByText('AMVIC licence')).toBeTruthy();
    expect(await screen.findByText('Required for automotive services in Alberta.')).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    await user().click(screen.getByRole('button', { name: 'Enter licence #' }));
    const dialog = screen.getByRole('dialog', { name: 'AMVIC licence' });
    await user().click(within(dialog).getByRole('button', { name: 'Verify' }));
    expect(await within(dialog).findByText('Enter the licence number.')).toBeTruthy();
  });

  it('submits for review once every check is complete', async () => {
    let o = onboarding();
    for (const c of o.checklist) o = done(o, c.key, c.key === 'insurance' ? { status: 'submitted', expiresOn: '2027-03-31' } : {});
    const onSubmitted = vi.fn();
    const calls = mockFetch(c => (c.url.endsWith('/onboarding/submit') ? { body: { ...o, status: 'pending', step: 'review', submittedAt: new Date().toISOString() } } : undefined));
    renderWithProviders(<VerificationStep onboarding={o} onBack={() => {}} onSubmitted={onSubmitted} />);
    expect(screen.getByText('6 of 6 complete')).toBeTruthy();
    expect(screen.getByText(/Valid to/)).toBeTruthy();
    await user().click(screen.getByRole('button', { name: 'Submit for review' }));
    await waitFor(() => expect(onSubmitted).toHaveBeenCalled());
    expect(calls.filter(c => c.method === 'POST')).toHaveLength(1);
  });
});

describe('Business registration labels — S-23', () => {
  it('shows a registry lookup waiting for an agent, and sole proprietors who need no registration', () => {
    let o = onboarding();
    o = { ...o, checklist: o.checklist.map(c => (c.key === 'registry' ? { ...c, status: 'submitted', reference: '2000000001' } : c)) };
    const { unmount } = renderWithProviders(<VerificationStep onboarding={o} onBack={() => {}} onSubmitted={() => {}} />);
    expect(screen.getByText('2000000001 · checking')).toBeTruthy();
    unmount();
    renderWithProviders(<VerificationStep onboarding={done(onboarding(), 'registry', { reference: 'not_required' })} onBack={() => {}} onSubmitted={() => {}} />, { locale: 'fr' });
    expect(screen.getByText('Aucun enregistrement requis')).toBeTruthy();
  });
});

describe('Identity (Stripe KYC) — S-22', () => {
  const owner = (over: Partial<OwnerIdentity> = {}): OwnerIdentity => ({
    principalId: 'P1', legalName: 'Ravi Sandhu', role: 'director', ownershipPct: 60, you: false, status: 'not_started',
    delivery: null, emailMasked: null, lastError: null, nameMatch: null, dobMatch: null, attempts: 0, updatedAt: null, ...over,
  });
  const owners = [owner(), owner({ principalId: 'P2', legalName: 'Priya Sandhu', role: 'shareholder', ownershipPct: 40, status: 'retry', lastError: 'selfie_face_mismatch', delivery: 'email', emailMasked: 'p***@example.ca', attempts: 1 })];

  it('opens the owners list from the checklist instead of completing the row', async () => {
    const calls = mockFetch(c => (c.url.endsWith('/identity-checks') ? { body: { items: owners } } : undefined));
    renderWithProviders(<VerificationStep onboarding={onboarding()} onBack={() => {}} onSubmitted={() => {}} />);
    await user().click(screen.getByRole('button', { name: 'Start with Stripe' }));
    const dialog = screen.getByRole('dialog', { name: 'Identity (Stripe KYC)' });
    expect(await within(dialog).findByText('Ravi Sandhu')).toBeTruthy();
    expect(within(dialog).getByText('Director · 60 %')).toBeTruthy();
    expect(within(dialog).getByText("Try again · Selfie didn't match the ID")).toBeTruthy();
    expect(calls.some(c => c.url.includes('/complete'))).toBe(false);
  });

  it('sends the signed-in owner to Stripe and emails the others, with the server message', async () => {
    const navigate = vi.fn();
    const calls = mockFetch(c => {
      if (c.url.endsWith('/identity-checks')) return { body: { items: owners } };
      if (c.url.endsWith('/P1/session')) return { body: { owner: owner({ you: true, status: 'pending', delivery: 'self' }), url: 'https://verify.stripe.com/start/test_x' } };
      if (c.url.endsWith('/P2/session')) return (c.body as { email?: string }).email ? { body: { owner: owners[1], url: null } } : { status: 422, body: { errors: [{ field: 'email', rule: 'required', message: "Enter the owner's email address." }] } };
      return undefined;
    });
    renderWithProviders(<IdentityDialog merchantId="M1" title="Identity" onClose={() => {}} navigate={navigate} />);
    const dialog = await screen.findByRole('dialog', { name: 'Identity' });
    await user().click((await within(dialog).findAllByRole('button', { name: 'This is me · verify now' }))[0]!);
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('https://verify.stripe.com/start/test_x'));
    expect(calls.find(c => c.url.endsWith('/P1/session'))?.body).toEqual({ delivery: 'self' });

    await user().click(within(dialog).getByRole('button', { name: 'Send a new link' }));
    await user().click(within(dialog).getByRole('button', { name: 'Send link' }));
    expect(await within(dialog).findByText("Enter the owner's email address.")).toBeTruthy();
    await user().type(within(dialog).getByLabelText("Priya Sandhu's email"), 'priya@example.ca');
    await user().click(within(dialog).getByRole('button', { name: 'Send link' }));
    expect(await within(dialog).findByText(/Link sent to p\*\*\*@example.ca/)).toBeTruthy();
  });

  it('reads French', async () => {
    mockFetch(c => (c.url.endsWith('/identity-checks') ? { body: { items: [owner({ status: 'processing' })] } } : undefined));
    renderWithProviders(<IdentityDialog merchantId="M1" title="Identité" onClose={() => {}} />, { locale: 'fr' });
    expect(await screen.findByText('Vérification par Stripe')).toBeTruthy();
    expect(screen.getByText('Administrateur · 60 %')).toBeTruthy();
  });

  it('thanks owners who verified from an emailed link', () => {
    renderWithProviders(<IdentityDoneScreen />);
    expect(screen.getByRole('heading', { name: "You're done" })).toBeTruthy();
  });
});

describe('ReviewStep', () => {
  it('lists the checks, shows the food block for kitchens and offers the dev-only approval', async () => {
    const o = { ...onboarding({ type: 'kitchen', status: 'pending', step: 'review', submittedAt: new Date().toISOString() }) };
    const onNext = vi.fn();
    const calls = mockFetch(c => {
      if (c.url.endsWith('/approve')) return { body: { ...o, status: 'active' } };
      if (c.method === 'PATCH') return { body: { ...o, step: 'page' } };
      return undefined;
    });
    renderWithProviders(<ReviewStep onboarding={o} onNext={onNext} />);
    expect(screen.getByText('Under review')).toBeTruthy();
    expect(screen.getByText('No shortcuts for food.')).toBeTruthy();
    expect(screen.getByText(/Submitted today/)).toBeTruthy();
    await user().click(screen.getByRole('button', { name: 'Simulate approval →' }));
    await waitFor(() => expect(onNext).toHaveBeenCalled());
    expect(calls.map(c => `${c.method} ${c.url}`)).toEqual([
      'POST /api/v1/dev/merchants/01J9ZD3V00000000000000TST1/approve',
      'PATCH /api/v1/merchants/01J9ZD3V00000000000000TST1/onboarding',
    ]);
  });
});
