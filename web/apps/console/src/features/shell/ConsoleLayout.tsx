import { useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useRouterState } from '@tanstack/react-router';
import { CaretDown, MagnifyingGlass } from '@phosphor-icons/react';
import { Alert, AppShell, Avatar, Menu, useLocale, type MenuEntry } from '@northline/ui';
import { PilotControl } from '../pilot/PilotControl';
import { useSession, useSignOut } from '../../lib/session';
import { meQuery, opens, useRegions, useSwitchRole, type Me, type RoleGrant, type Regions } from './api';
import { useShellT, type ShellKey, type ShellT } from './messages';
import { buildNav } from './navMenu';
import { Brand } from './Brand';
import { useActiveRole } from './roleView';
import { SCREEN_PATH, screenFromPath, type ScreenKey } from './screens';
import './shell.css';

const RAIL_KEY = 'nl.console.rail';
const readRail = () => { try { return localStorage.getItem(RAIL_KEY) === '1'; } catch { return false; } };

/** The active role's grant (undefined: the person holds no console role, or none is active). */
export function useActiveGrant(me: Me): RoleGrant | undefined {
  const active = useActiveRole();
  return me.roles.find(r => r.role === active);
}

/** "Ops · Alberta + BC pilot": live provinces by name, pilot ones by code — from the region model, never from code. */
export function regionLine(regions: Regions | undefined, t: ShellT): string {
  if (!regions) return t('ops');
  const live = regions.provinces.filter(p => p.status === 'live').map(p => p.name);
  const pilot = regions.provinces.filter(p => p.status === 'pilot').map(p => p.code);
  const parts = [...live, ...(pilot.length ? [t('pilotIn', { codes: pilot.join(', ') })] : [])];
  return parts.length ? `${t('ops')} · ${parts.join(' + ')}` : t('ops');
}

/**
 * Console chrome (design 03, SCREENS.md "Console shell"): top bar — brand + "Console", the global search pill, the
 * region line, the account menu (profile, role switch, audit, on-call, language, sign out) — and the sticky sidebar
 * filtered by the active role. A screen the role can't open shows the denied banner instead (the api refuses it too).
 */
export function ConsoleLayout({ children, denied }: { children: ReactNode; denied: ReactNode }) {
  const t = useShellT();
  const nav = useNavigate();
  const { locale } = useLocale();
  const me = useQuery(meQuery).data!;
  const grant = useActiveGrant(me);
  const pathname = useRouterState({ select: s => s.location.pathname });
  const screen = screenFromPath(pathname);
  const { pinned, groups } = buildNav(grant, t);
  const regions = useRegions(locale).data;
  const [rail, setRail] = useState(readRail);
  const allowed = screen === undefined || opens(grant, screen);
  return (
    <AppShell
      brand={<Brand label={t('console')} />}
      headerStart={<>
        <form role="search" aria-label={t('searchLabel')} className="nl-console-search" onSubmit={e => e.preventDefault()}>
          <MagnifyingGlass size={18} weight="duotone" className="nl-console-search-icon" aria-hidden />
          <input className="input" type="search" aria-label={t('searchLabel')} placeholder={t('searchPlaceholder')} />
        </form>
        <span className="nl-console-region">{regionLine(regions, t)}</span>
      </>}
      headerEnd={<AccountMenu me={me} grant={grant} screen={screen} />}
      pinned={pinned} groups={groups} currentKey={screen ?? ''}
      onNavigate={i => nav({ to: i.href })}
      rail={rail} onRailChange={r => { setRail(r); try { localStorage.setItem(RAIL_KEY, r ? '1' : '0'); } catch { /* private mode */ } }}
    >{allowed ? children : denied}<PilotControl /></AppShell>
  );
}

/** Design 03 `deniedNote`: shown above the overview when the active role can't open the screen in the address bar. */
export function DeniedBanner() {
  const t = useShellT();
  const me = useQuery(meQuery).data!;
  const grant = useActiveGrant(me);
  return (
    <div className="nl-console-denied">
      <Alert tone="error" role="alert"><strong>{t('deniedTitle')}</strong> {t('deniedNote', { role: roleName(grant?.role, t) })}</Alert>
    </div>
  );
}

export const roleName = (role: string | undefined, t: ShellT) => (role ? t(`role_${role}` as ShellKey) : t('noRole'));

/** "8 views · suspend, decide, verify, vet" (design `rolePick.scopes`). */
export function roleScopes(grant: RoleGrant, t: ShellT): string {
  const navScreens = grant.screens.filter(s => s !== 'profile' && s !== 'oncall');
  const views = grant.role === 'admin' ? t('allViews') : t('views', { count: navScreens.length });
  const can = grant.actions.length ? grant.actions.map(a => t(`a_${a}` as ShellKey)).join(', ') : t('readOnly');
  return `${views} · ${can}`;
}

function AccountMenu({ me, grant, screen }: { me: Me; grant: RoleGrant | undefined; screen: ScreenKey | undefined }) {
  const t = useShellT();
  const nav = useNavigate();
  const { data: session } = useSession();
  const { locale, setLocale } = useLocale();
  const signOut = useSignOut();
  const switchRole = useSwitchRole();
  const [showRoles, setShowRoles] = useState(false);
  const user = session?.user;
  const items: MenuEntry[] = [
    { label: t('profile'), meta: t('profileMeta'), onSelect: () => nav({ to: SCREEN_PATH.profile, search: { tab: 'security' } as never }) },
    { kind: 'custom', render: <button type="button" role="menuitem" className="nl-menu-item" aria-expanded={showRoles} onClick={() => setShowRoles(s => !s)}><span>{t('switchRole')}</span><span className="nl-menu-meta">{roleName(grant?.role, t)} ▾</span></button> },
    ...(showRoles ? [
      ...me.roles.map((r): MenuEntry => ({
        label: roleName(r.role, t), meta: roleScopes(r, t), checked: r.role === grant?.role, indent: true,
        onSelect: () => switchRole.mutate(r.role, {
          // design: switching keeps the screen when the new role opens it, else goes to the overview
          onSuccess: g => { if (screen && !opens(g, screen)) void nav({ to: SCREEN_PATH.overview }); },
        }),
      })),
      { kind: 'custom', render: <div className="nl-console-role-note">{me.roles.length ? t('roleNote') : t('noRoles')}</div> } as MenuEntry,
    ] : []),
    { label: t('myAudit'), onSelect: () => nav({ to: SCREEN_PATH.profile, search: { tab: 'audit' } as never }) },
    { label: t('oncall'), onSelect: () => nav({ to: SCREEN_PATH.oncall }) },
    { kind: 'separator' },
    { label: t('language'), meta: t('languageMeta'), onSelect: () => setLocale(locale === 'en' ? 'fr' : 'en') },
    { label: t('signOut'), onSelect: () => void signOut() },
  ];
  const name = user ? `${user.firstName} ${user.lastName.slice(0, 1)}${user.lastName ? '.' : ''}` : '';
  return (
    <>
      {switchRole.isError ? <span role="alert" className="nl-console-switch-error">{t('switchFailed')}</span> : null}
      <Menu label={t('accountMenu')} width={280} items={items} trigger={({ props }) => (
        <button type="button" aria-label={t('accountMenu')} {...props} className="nl-console-account">
          <Avatar initials={user?.initials ?? '··'} />
          <span className="nl-console-account-name">{name}<span>{roleName(grant?.role, t)}</span></span>
          <CaretDown size={13} color="var(--color-neutral-700)" />
        </button>
      )} />
    </>
  );
}
