import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';
import { configureAuthOrigin } from '@northline/auth-kit';
import { I18nProvider } from '@northline/ui';
import { mockFetch } from '../../test/render';
import { SignInPage } from './SignInPage';

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
    expect(screen.getByText('Staff sign in with company SSO; a passkey is required on every device. Role decides what you can see and do.')).toBeTruthy();
    expect(screen.getByRole('textbox', { name: 'Work email or mobile' })).toHaveProperty('placeholder', 'you@yourbusiness.ca');
    expect(screen.getByText('Locked out? Page the on-call admin · #console-access')).toBeTruthy();
  });

  it('explains why the console-bff refused a sign-in', () => {
    mockFetch(() => undefined);
    renderPage({ error: 'staff_only' });
    expect(screen.getByText('This is for Northline staff only.')).toBeTruthy();
  });

  it('signs in with a backup code, then hands off to the console-bff', async () => {
    const calls = mockFetch(call => {
      if (call.url.endsWith('/api/auth/sign-in')) return { body: { identifier: 'priya.natarajan@example.com', factors: ['backup_code'] } };
      if (call.url.endsWith('/api/auth/sign-in/backup-code')) return { body: { user: { id: 'u', firstName: 'Priya', lastName: 'Natarajan', initials: 'PN' }, acr: 'mfa' } };
      return undefined;
    });
    const navigate = vi.fn();
    const user = userEvent.setup({ delay: null });
    renderPage({ next: '/finance', navigate });
    await user.type(screen.getByRole('textbox', { name: 'Work email or mobile' }), 'priya.natarajan@example.com');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByText('Second factor required for priya.natarajan@example.com.')).toBeTruthy();
    await user.click(screen.getByRole('radio', { name: /Backup code/ }));
    await user.type(screen.getByRole('textbox', { name: 'Backup code' }), 'priya-n-00001');
    await user.click(screen.getByRole('button', { name: 'Verify backup code' }));
    expect(await screen.findByText('Welcome back, Priya.')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(navigate).toHaveBeenCalledWith('/bff/login?next=%2Ffinance');
    expect(calls.find(c => c.url.endsWith('/backup-code'))?.body).toEqual({ code: 'priya-n-00001' });
  });

  it('never hands off to another site', async () => {
    mockFetch(call => (call.url.endsWith('/api/auth/sign-in/backup-code') ? { body: { user: { id: 'u', firstName: 'Priya', lastName: 'N', initials: 'PN' } } } : call.url.endsWith('/api/auth/sign-in') ? { body: { identifier: 'x', factors: [] } } : undefined));
    const navigate = vi.fn();
    const user = userEvent.setup({ delay: null });
    renderPage({ next: '//evil.example', navigate });
    await user.type(screen.getByRole('textbox', { name: 'Work email or mobile' }), 'x');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(await screen.findByRole('radio', { name: /Backup code/ }));
    await user.type(screen.getByRole('textbox', { name: 'Backup code' }), 'a');
    await user.click(screen.getByRole('button', { name: 'Verify backup code' }));
    await user.click(await screen.findByRole('button', { name: 'Continue' }));
    expect(navigate).toHaveBeenCalledWith('/bff/login?next=%2F');
  });
});
