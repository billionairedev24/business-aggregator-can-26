import { useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useRouterState } from '@tanstack/react-router';
import { CaretDown } from '@phosphor-icons/react';
import { AppShell, Avatar, Menu, useLocale, type MenuEntry } from '@northline/ui';
import { useSession, useSignOut } from '../../lib/session';
import { businessesQuery, navBadgesQuery, type MerchantSummary } from './api';
import { useShellT } from './messages';
import { AssistantButton } from '../assistant/AssistantDrawer';
import { homeScreen, screenFromPath, screenHref } from './nav';
import { buildNav } from './navMenu';

const RAIL_KEY = 'nl.studio.rail';
const readRail = () => { try { return localStorage.getItem(RAIL_KEY) === '1'; } catch { return false; } };

export const tierTag = (m: Pick<MerchantSummary, 'tier' | 'status'>): { cls: string; key: 'tier_master' | 'tier_trusted' | 'tier_registered' | 'tier_pending' | 'tier_applicant' } =>
  m.status === 'applicant' ? { cls: 'tag-neutral', key: 'tier_applicant' }
  : m.status === 'pending' ? { cls: 'tag-accent-2', key: 'tier_pending' }
  : m.tier === 'master' ? { cls: 'tag-accent', key: 'tier_master' }
  : m.tier === 'trusted' ? { cls: 'tag-accent', key: 'tier_trusted' }
  : { cls: 'tag-neutral', key: 'tier_registered' };

export const Brand = ({ label }: { label: string }) => <span className="nav-brand">Northline <span style={{ fontWeight: 400, color: 'var(--color-neutral-700)' }}>{label}</span></span>;

/** Studio chrome: top bar (brand, business · city, tier, account menu) + portal-aware sidebar. */
export function StudioLayout({ merchant, children }: { merchant: MerchantSummary; children: ReactNode }) {
  const t = useShellT();
  const nav = useNavigate();
  const pathname = useRouterState({ select: s => s.location.pathname });
  const badges = useQuery(navBadgesQuery(merchant.id)).data ?? {};
  const { pinned, groups } = buildNav(merchant.type, merchant.id, t, badges);
  const tier = tierTag(merchant);
  const [rail, setRail] = useState(readRail);
  return (
    <AppShell
      brand={<Brand label={t('studio')} />}
      headerStart={<>
        <span style={{ fontSize: 14, color: 'var(--color-neutral-700)' }}>{merchant.displayName}{merchant.city ? ` · ${merchant.city}` : ''}</span>
        <span className={`tag ${tier.cls}`}>{t(tier.key)}</span>
      </>}
      headerEnd={<><AssistantButton merchantId={merchant.id} /><AccountMenu merchant={merchant} badges={badges} /></>}
      pinned={pinned} groups={groups} currentKey={screenFromPath(pathname)}
      onNavigate={i => nav({ to: i.href })}
      rail={rail} onRailChange={r => { setRail(r); try { localStorage.setItem(RAIL_KEY, r ? '1' : '0'); } catch { /* private mode */ } }}
    >{children}</AppShell>
  );
}

function AccountMenu({ merchant, badges }: { merchant: MerchantSummary; badges: Record<string, string> }) {
  const t = useShellT();
  const nav = useNavigate();
  const { data: session } = useSession();
  const { data: businesses = [] } = useQuery(businessesQuery);
  const { locale, setLocale } = useLocale();
  const signOut = useSignOut();
  const [showBiz, setShowBiz] = useState(false);
  const user = session?.user;
  const roleKey = `role_${merchant.role ?? 'owner'}` as Parameters<typeof t>[0];
  const items: MenuEntry[] = [
    { label: t('profile'), meta: t('passkey'), onSelect: () => nav({ to: `${screenHref(merchant.id, 'settings')}`, search: { tab: 'security' } as never }) },
    { kind: 'custom', render: <button type="button" role="menuitem" className="nl-menu-item" aria-expanded={showBiz} onClick={() => setShowBiz(s => !s)}><span>{t('switchBusiness')}</span><span className="nl-menu-meta">{merchant.displayName} ▾</span></button> },
    ...(showBiz ? [
      ...businesses.map((b): MenuEntry => ({ label: b.displayName, meta: `${t(`type_${b.type}`)}${b.tier ? ` · ${t(tierTag(b).key)}` : ''}`, checked: b.id === merchant.id, indent: true, onSelect: () => nav({ to: screenHref(b.id, homeScreen(b.type)) }) })),
      { kind: 'custom', render: <a href="/onboarding" className="nl-menu-item nl-menu-sub" style={{ fontSize: 12 }} role="menuitem">{t('addBusiness')}</a> } as MenuEntry,
    ] : []),
    { label: t('team'), meta: merchant.teamCount ? t('members', { count: merchant.teamCount }) : undefined, onSelect: () => nav({ to: screenHref(merchant.id, 'settings'), search: { tab: 'team' } as never }) },
    { label: t('helpSupport'), meta: badges.help, onSelect: () => nav({ to: screenHref(merchant.id, 'help') }) },
    { kind: 'separator' },
    { label: t('language'), meta: t('languageMeta'), onSelect: () => setLocale(locale === 'en' ? 'fr' : 'en') },
    { label: t('signOut'), onSelect: () => void signOut() },
  ];
  return (
    <Menu label={t('accountMenu')} width={280} items={items} trigger={({ props }) => (
      <button type="button" aria-label={t('accountMenu')} {...props} style={{ display: 'flex', alignItems: 'center', gap: 8, border: 0, background: 'transparent', font: 'inherit', cursor: 'pointer', color: 'var(--color-text)', padding: 4, minHeight: 44 }}>
        <Avatar initials={user?.initials ?? '··'} />
        <span style={{ fontSize: 13, textAlign: 'left', lineHeight: 1.2 }}>{user ? `${user.firstName} ${user.lastName}` : ''}<span style={{ display: 'block', fontSize: 11, color: 'var(--color-neutral-700)' }}>{t(roleKey)} · {merchant.displayName}</span></span>
        <CaretDown size={13} color="var(--color-neutral-700)" />
      </button>
    )} />
  );
}
