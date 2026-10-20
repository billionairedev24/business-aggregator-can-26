import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';
import { configureAuthOrigin } from '@northline/auth-kit';
import { I18nProvider } from '@northline/ui';
import { mockFetch } from '../../test/render';
import { SignInPage } from './SignInPage';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const renderPage = (props: Parameters<typeof SignInPage>[0] = {}) => {
  configureAuthOrigin('http://auth.test');
  return render(<I18nProvider initial="en"><SignInPage {...props} /></I18nProvider>);
};

describe('console sign-in (design 03 signed out)', () => {
  it('shows the design copy', () => {
    mockFetch(() => undefined);
    renderPage();
    expect(screen.getByText('Northline Console · internal')).toBeTruthy();
    expect(screen.getByText('Signed out')).toBeTruthy();
    expect(screen.getByText('Staff sign in with their work email and password, then a passkey or an authenticator app. Role decides what you can see and do.')).toBeTruthy();
    expect(screen.getByRole('textbox', { name: 'Email' })).toHaveProperty('placeholder', 'you@yourbusiness.ca');
    expect(screen.getByLabelText('Password')).toBeTruthy();
    expect(screen.getByText('Locked out? Page the on-call admin · #console-access')).toBeTruthy();
  });

  it('explains why the console-bff refused a sign-in', () => {
    mockFetch(() => undefined);
    renderPage({ error: 'staff_only' });
    expect(screen.getByText('This is for Northline staff only.')).toBeTruthy();
  });

  it('signs in with a backup code, then hands off to the console-bff', async () => {
    const calls = mockFetch(call => {
      if (call.url.endsWith('/api/auth/sign-in/password')) return { body: { step: 'factor', factors: ['totp', 'backup_code'] } };
      if (call.url.endsWith('/api/auth/sign-in/backup-code')) return { body: { user: { id: 'u', firstName: 'Priya', lastName: 'Natarajan', initials: 'PN' }, acr: 'mfa' } };
      return undefined;
    });
    const navigate = vi.fn();
    const user = userEvent.setup({ delay: null });
    renderPage({ next: '/finance', navigate });
    await user.type(screen.getByRole('textbox', { name: 'Email' }), 'priya.natarajan@example.com');
    await user.type(screen.getByLabelText('Password'), 'northline-dev-password');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByText('Confirm it’s you, priya.natarajan@example.com.')).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    await user.click(screen.getByRole('radio', { name: /Backup code/ }));
    await user.type(screen.getByRole('textbox', { name: 'Backup code' }), 'priya-n-00001');
    await user.click(screen.getByRole('button', { name: 'Verify backup code' }));
    expect(await screen.findByText('Welcome back, Priya.')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(navigate).toHaveBeenCalledWith('/bff/login?next=%2Ffinance');
    expect(calls.find(c => c.url.endsWith('/backup-code'))?.body).toEqual({ code: 'priya-n-00001' });
  });

  it('the factor picker is one Tab stop; the arrow keys move and select (S-140)', async () => {
    mockFetch(call => (call.url.endsWith('/api/auth/sign-in/password') ? { body: { step: 'factor', factors: ['passkey', 'totp', 'backup_code'] } } : undefined));
    const user = userEvent.setup({ delay: null });
    renderPage({ next: '/', navigate: vi.fn() });
    await user.type(screen.getByRole('textbox', { name: 'Email' }), 'priya.natarajan@example.com');
    await user.type(screen.getByLabelText('Password'), 'northline-dev-password');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    const radios = await screen.findAllByRole('radio');
    const stop = radios.filter(r => r.getAttribute('tabindex') === '0');
    expect(stop).toHaveLength(1);
    stop[0]!.focus();
    await user.keyboard('{End}');
    const backup = screen.getByRole('radio', { name: /Backup code/ });
    expect(document.activeElement).toBe(backup);
    expect(backup.getAttribute('aria-checked')).toBe('true');
    await user.keyboard('{ArrowRight}');
    expect(radios[0]!.getAttribute('aria-checked')).toBe('true');
  });

  it('S-154: staff without a second factor set one up — never a dead end', async () => {
    mockFetch(call => {
      if (call.url.endsWith('/api/auth/sign-in/password')) return { body: { step: 'enroll', session: { user: { id: 'u', firstName: 'Priya', lastName: 'N', initials: 'PN', email: 'priya@example.com' }, needsSecondFactor: true } } };
      return undefined;
    });
    const user = userEvent.setup({ delay: null });
    renderPage({ navigate: vi.fn() });
    await user.type(screen.getByRole('textbox', { name: 'Email' }), 'priya.natarajan@example.com');
    await user.type(screen.getByLabelText('Password'), 'northline-dev-password');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByRole('heading', { name: 'Set up your second factor' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Skip for now' })).toBeNull();
  });

  it('S-154: wrong password, then Forgot password', async () => {
    const calls = mockFetch(call => {
      if (call.url.endsWith('/api/auth/sign-in/password')) return { status: 422, body: { errors: [{ field: 'password', rule: 'mismatch', message: 'x' }] } };
      if (call.url.endsWith('/api/auth/password/forgot')) return { status: 202, body: { step: 'verify-email', email: 'priya@example.com', resendAfterSeconds: 60 } };
      return undefined;
    });
    const user = userEvent.setup({ delay: null });
    renderPage({ navigate: vi.fn() });
    await user.type(screen.getByRole('textbox', { name: 'Email' }), 'priya.natarajan@example.com');
    await user.type(screen.getByLabelText('Password'), 'northline-dev-password');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByText("That email and password don't match. Check them and try again.")).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Forgot password?' }));
    await user.click(screen.getByRole('button', { name: 'Send code' }));
    expect(await screen.findByLabelText('Code from the email')).toBeTruthy();
    expect(calls.find(c => c.url.endsWith('/password/forgot'))?.body).toEqual({ email: 'priya.natarajan@example.com', app: 'console' });
  });

  it('never hands off to another site', async () => {
    mockFetch(call => (call.url.endsWith('/api/auth/sign-in/backup-code') ? { body: { user: { id: 'u', firstName: 'Priya', lastName: 'N', initials: 'PN' } } } : call.url.endsWith('/api/auth/sign-in/password') ? { body: { step: 'factor', factors: ['totp', 'backup_code'] } } : undefined));
    const navigate = vi.fn();
    const user = userEvent.setup({ delay: null });
    renderPage({ next: '//evil.example', navigate });
    await user.type(screen.getByRole('textbox', { name: 'Email' }), 'priya.natarajan@example.com');
    await user.type(screen.getByLabelText('Password'), 'northline-dev-password');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(await screen.findByRole('radio', { name: /Backup code/ }));
    await user.type(screen.getByRole('textbox', { name: 'Backup code' }), 'a');
    await user.click(screen.getByRole('button', { name: 'Verify backup code' }));
    await user.click(await screen.findByRole('button', { name: 'Continue' }));
    expect(navigate).toHaveBeenCalledWith('/bff/login?next=%2F');
  });
});
