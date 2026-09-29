import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { onboarding } from '../../test/fixtures';
import { mockFetch, renderWithProviders } from '../../test/render';
import type { Onboarding } from './api';
import { ReviewStep } from './ReviewStep';
import { VerificationStep } from './VerificationStep';

vi.mock('@tanstack/react-router', async orig => ({
  ...(await orig<typeof import('@tanstack/react-router')>()),
  Link: ({ children, className }: { children: React.ReactNode; className?: string }) => <a className={className} href="#">{children}</a>,
}));

beforeEach(() => vi.unstubAllGlobals());

const done = (o: Onboarding, key: string, patch: Partial<Onboarding['checklist'][number]> = {}): Onboarding => ({
  ...o, checklist: o.checklist.map(c => (c.key === key ? { ...c, status: 'verified', reference: 'passed', ...patch } : c)),
});

describe('VerificationStep', () => {
  it('runs instant checks through the api and shows the done label', async () => {
    const o = onboarding();
    const calls = mockFetch(c => (c.url.endsWith('/verifications/V1/complete') ? { body: done(o, 'kyc') } : undefined));
    renderWithProviders(<VerificationStep onboarding={o} onBack={() => {}} onSubmitted={() => {}} />);
    expect(screen.getByText('0 of 6 complete')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Start with Stripe' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/V1/complete'))).toBe(true));
    expect(((screen.getByRole('button', { name: 'Submit for review' })) as HTMLButtonElement).disabled).toBe(true);
  });

  it('asks for the licence number in a dialog and shows the server message', async () => {
    const o = onboarding();
    mockFetch(c => (c.url.endsWith('/V3/complete') ? { status: 422, body: { errors: [{ field: 'reference', rule: 'required', message: 'Enter the licence number.' }] } } : undefined));
    renderWithProviders(<VerificationStep onboarding={o} onBack={() => {}} onSubmitted={() => {}} />);
    expect(screen.getByText('AMVIC licence')).toBeTruthy();
    expect(screen.getByText('Required for automotive services in Alberta.')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Enter licence #' }));
    const dialog = screen.getByRole('dialog', { name: 'AMVIC licence' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Verify' }));
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
    await userEvent.click(screen.getByRole('button', { name: 'Submit for review' }));
    await waitFor(() => expect(onSubmitted).toHaveBeenCalled());
    expect(calls.filter(c => c.method === 'POST')).toHaveLength(1);
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
    await userEvent.click(screen.getByRole('button', { name: 'Simulate approval →' }));
    await waitFor(() => expect(onNext).toHaveBeenCalled());
    expect(calls.map(c => `${c.method} ${c.url}`)).toEqual([
      'POST /api/v1/dev/merchants/01J9ZD3V00000000000000TST1/approve',
      'PATCH /api/v1/merchants/01J9ZD3V00000000000000TST1/onboarding',
    ]);
  });
});
