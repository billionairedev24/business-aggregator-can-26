import { useQuery } from '@tanstack/react-query';
import { meQuery } from './api';
import { useActiveGrant } from './ConsoleLayout';
import { useShellT, type ShellKey } from './messages';

/**
 * The active role's grant for a screen's controls (CONSOLE_PLAN: gate controls, never data): `can(action)` hides or
 * disables what the role may not change — the api refuses it anyway (403 `insufficient_role`) — and `roleName` is the
 * Data Table's "View only · {role}".
 */
export function useGrant() {
  const t = useShellT();
  const me = useQuery(meQuery).data;
  const grant = useActiveGrant(me ?? { userId: '', roles: [] });
  const can = (action: string) => grant?.actions.includes(action) ?? false;
  const roleName = grant ? t(`role_${grant.role}` as ShellKey) : t('noRole');
  return { grant, can, roleName };
}
