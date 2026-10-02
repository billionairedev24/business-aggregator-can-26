import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi } from '../../test/render';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const SECURITY = {
  email: 'priya@example.test', mfaPrimary: 'passkey',
  passkeys: [{ id: 'p1', label: 'MacBook Pro (Touch ID)' }, { id: 'p2', label: 'YubiKey 5C' }],
  authenticator: true, backupCodesRemaining: 7, signIns: [],
  sessions: [
    { id: 'x1', device: 'MacBook Pro · Chrome', city: 'Calgary', signedInAt: '2026-09-08T10:00:00Z', apps: [], current: true },
    { id: 'x2', device: 'Windows · Edge', city: 'Edmonton', signedInAt: '2026-09-05T10:00:00Z', lastSeenAt: '2026-09-05T12:00:00Z', apps: [], current: false },
  ],
};

describe('my profile (S-96, design 03)', () => {
  it('shows passkeys and the second factor from northline-auth, and signs out everywhere else', async () => {
    const calls = staffApi(['admin', 'finance'], c => {
      if (c.url.includes('/api/auth/security/sessions/revoke-others')) return { body: { revoked: 2 } };
      if (c.url.includes('/api/auth/security')) return { body: SECURITY };
      return undefined;
    });
    const user = userEvent.setup({ delay: null });
    renderConsole('/profile');
    expect(await screen.findByText('Passkey · MacBook Pro (Touch ID)')).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('Primary')).toBeTruthy();
    expect(screen.getByText('7 of 10 unused')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Sign out everywhere' }));
    expect(await screen.findByText('2 other sessions signed out.')).toBeTruthy();
    expect(calls.some(c => c.url.endsWith('/api/auth/security/sessions/revoke-others'))).toBe(true);
  });

  it('lists devices and signs one out, shows my audit trail, and switches the language', async () => {
    const calls = staffApi(['analyst'], c => {
      if (c.url.includes('/sessions/x2/revoke')) return { body: { sessions: [SECURITY.sessions[0]] } };
      if (c.url.includes('/api/auth/security')) return { body: SECURITY };
      if (c.url.includes('/api/v1/console/me/audit')) return { body: { items: [{ id: 'a1', at: '2026-09-08T09:58:00Z', actorId: 'me', actorName: 'Priya Natarajan', role: 'admin', action: 'region.stage_changed', targetType: 'province', targetId: 'BC' }] } };
      return undefined;
    });
    const user = userEvent.setup({ delay: null });
    renderConsole('/profile?tab=sessions');
    expect(await screen.findByText('Windows · Edge')).toBeTruthy();
    expect(screen.getByText('This browser')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Sign out' }));
    await waitFor(() => expect(calls.some(c => c.url.includes('/api/auth/security/sessions/x2/revoke'))).toBe(true));
    await user.click(screen.getByRole('tab', { name: 'My audit trail' }));
    expect(await screen.findByText('region.stage_changed')).toBeTruthy();
    await user.click(screen.getByRole('tab', { name: 'Preferences' }));
    await user.click(await screen.findByRole('radio', { name: 'Français' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Mon profil' })).toBeTruthy();
  });

  it('asks for a recent second factor when northline-auth has none', async () => {
    staffApi(['support'], c => (c.url.includes('/api/auth/security') ? { status: 401 } : undefined));
    renderConsole('/profile');
    expect(await screen.findByText(/need a recent second factor/)).toBeTruthy();
  });
});
