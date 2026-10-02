import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';
import type { AuditPage, Team } from './api';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const TEAM: Team = {
  roles: [
    { role: 'admin', people: 3, screens: ['api', 'finance'], actions: ['keys'] },
    { role: 'finance', people: 2, screens: ['disputes', 'finance', 'overview', 'reports', 'team'], actions: ['payouts', 'refund'] },
  ],
  members: [
    { id: 'u1', name: 'Marc Leblanc', email: 'marc@example.test', roles: ['finance', 'staff'], since: '2026-01-02T00:00:00Z' },
    { id: 'u2', name: 'Dev Kaur', email: 'dev@example.test', roles: ['staff', 'trust_safety'] },
  ],
};
const PAGE1: AuditPage = {
  items: [
    { id: 'a1', at: '2026-09-08T20:02:00Z', actorId: 'u1', actorName: 'Marc Leblanc', role: 'finance', action: 'payments.reconciliation_exported', targetType: 'reconciliation', targetId: '2026-09-07' },
    { id: 'a2', at: '2026-09-08T19:41:00Z', actorId: 'system', role: 'system', action: 'merchant.search_hidden', targetType: 'merchant', targetId: 'm1', merchantId: 'm1', businessName: 'Bow River Mechanics' },
  ],
  next: 'cursor-2',
};

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.url.endsWith('/api/v1/console/team')) return { body: TEAM };
    if (c.url.includes('/api/v1/console/audit')) return { body: c.url.includes('before=cursor-2') ? { items: [] } : PAGE1 };
    if (c.url.includes('/api/v1/console/team/')) return { body: TEAM.members[0] };
    return undefined;
  });
}

describe('team, roles & audit (S-96, design 03)', () => {
  it('lists the roles, the people and the audit log, and pages and filters the log', async () => {
    const calls = api(['finance']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/team');
    expect(await screen.findByRole('heading', { level: 1, name: 'Who can do what, and who did what' })).toBeTruthy();
    const finance = screen.getAllByText('Finance').map(e => e.closest('tr, .nl-dt-card')).find(Boolean) as HTMLElement;
    expect(within(finance).getByText('2')).toBeTruthy();
    expect(await screen.findByText('payments.reconciliation_exported')).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getAllByText('Marc Leblanc').length).toBeGreaterThan(1);
    expect(screen.getByText('payments.reconciliation_exported')).toBeTruthy();
    expect(screen.getByText('Northline (automatic)')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Invite' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByText('Only admins change roles.')).toBeTruthy();
    await user.selectOptions(screen.getByRole('combobox', { name: 'Action' }), 'payments.');
    await waitFor(() => expect(calls.some(c => c.url.includes('/api/v1/console/audit?action=payments.'))).toBe(true));
    await user.click(screen.getByRole('button', { name: 'Older entries' }));
    expect(await screen.findByText('No entry matches.')).toBeTruthy();
  });

  it('lets an admin invite someone and add or remove a role, showing the api’s messages', async () => {
    let first = true;
    const calls = api(['admin'], c => {
      if (c.url.endsWith('/team/invite') && first) {
        first = false;
        return { status: 422, body: { errors: [{ field: 'email', rule: 'unknown', message: 'No Northline account uses that email. They sign up first, then you add the role.' }] } };
      }
      if (c.method === 'DELETE') return { status: 409, body: { type: 'about:blank', title: 'Conflict', status: 409, code: 'last_admin', detail: 'Northline needs at least one admin.' } };
      return undefined;
    });
    const user = userEvent.setup({ delay: null });
    renderConsole('/team');
    await user.click(await screen.findByRole('button', { name: 'Invite' }));
    const dialog = await screen.findByRole('dialog', { name: 'Invite to the console' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Their email' }), 'new@example.test');
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Role' }), 'analyst');
    await user.click(within(dialog).getByRole('button', { name: 'Add role' }));
    expect(await within(dialog).findByText('No Northline account uses that email. They sign up first, then you add the role.')).toBeTruthy();
    await user.click(within(dialog).getByRole('button', { name: 'Add role' }));
    await waitFor(() => expect(calls.filter(c => c.url.endsWith('/team/invite')).at(-1)?.body).toEqual({ email: 'new@example.test', role: 'analyst' }));

    await user.click(screen.getByText('Marc Leblanc', { selector: '.nl-dt-card *, td *, td' }));
    const manage = await screen.findByRole('dialog', { name: 'Roles · Marc Leblanc' });
    await user.selectOptions(within(manage).getByRole('combobox', { name: 'Add a role' }), 'analyst');
    await user.click(within(manage).getByRole('button', { name: 'Add role' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/team/u1/roles'))?.body).toEqual({ role: 'analyst' }));
    await user.click(within(manage).getByRole('button', { name: 'Remove Finance' }));
    expect(await within(manage).findByRole('alert')).toBeTruthy();
  });

  it('is closed to roles without Team, and speaks French', async () => {
    staffApi(['dispatch'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500 } : undefined));
    const first = renderConsole('/team');
    expect(await screen.findByText(/Not available in this role\./)).toBeTruthy();
    first.unmount();
    api(['admin']);
    renderConsole('/team', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Qui peut faire quoi, et qui a fait quoi' })).toBeTruthy();
  });
});
