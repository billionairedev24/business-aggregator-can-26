import { useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useRouterState } from '@tanstack/react-router';
import { CaretDown } from '@phosphor-icons/react';
import { AppShell, Avatar, Logo, Menu, PageHeadingProvider, testingToolsEnabled, useLocale, type MenuEntry } from '@northline/ui';
import { useSession, useSignOut } from '../../lib/session';
import { businessesQuery, navBadgesQuery, type MerchantSummary } from './api';
import { useShellT } from './messages';
import { AssistantButton } from '../assistant/AssistantDrawer';
import { PilotControl } from '../pilot/PilotControl';
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

/** The brand's logo with the portal's name ("Studio"), on the dark header (redesign § 1.3, BO:30: logo 28px + portal title). */
export const Brand = ({ label }: { label: string }) => <Logo size={28} onDark product={label} />;

/**
 * Studio chrome (redesign § 1.3, AfroTransact BO:30–110): the dark top bar (logo + "Studio", business · city, tier, the
 * assistant, the account menu) + the portal-aware sidebar. The pilot's "Send feedback" only with the testing tools on.
 */
export function StudioLayout({ merchant, children }: { merchant: MerchantSummary; children: ReactNode }) {
  const t = useShellT();
  const nav = useNavigate();
  const pathname = useRouterState({ select: s => s.location.pathname });
  const badges = useQuery(navBadgesQuery(merchant.id)).data ?? {};
  const { pinned, groups } = buildNav(merchant.type, merchant.id, t, badges);
  const tier = tierTag(merchant);
  const [rail, setRail] = useState(readRail);
  const current = screenFromPath(pathname);
  // S-142: a screen that is loading or failed keeps its name (the nav label) as the page's h1
  const heading = [...pinned, ...groups.flatMap(g => g.items)].find(i => i.key === current)?.label;
  return (
    <AppShell
      brand={<Brand label={t('studio')} />}
      headerStart={<>
        <span className="nl-topbar-meta"><strong>{merchant.displayName}</strong>{merchant.city ? ` · ${merchant.city}` : ''}</span>
        <span className={`tag ${tier.cls}`}>{t(tier.key)}</span>
      </>}
      headerEnd={<><AssistantButton merchantId={merchant.id} /><AccountMenu merchant={merchant} badges={badges} /></>}
      pinned={pinned} groups={groups} currentKey={current}
      onNavigate={i => nav({ to: i.href })}
      rail={rail} onRailChange={r => { setRail(r); try { localStorage.setItem(RAIL_KEY, r ? '1' : '0'); } catch { /* private mode */ } }}
    ><PageHeadingProvider value={heading}>{children}</PageHeadingProvider>{testingToolsEnabled() ? <PilotControl merchantId={merchant.id} /> : null}</AppShell>
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
      { kind: 'custom', render: <a href="/onboarding" className="nl-menu-item nl-menu-sub nl-menu-item-small" role="menuitem">{t('addBusiness')}</a> } as MenuEntry,
    ] : []),
    { label: t('team'), meta: merchant.teamCount ? t('members', { count: merchant.teamCount }) : undefined, onSelect: () => nav({ to: screenHref(merchant.id, 'settings'), search: { tab: 'team' } as never }) },
    { label: t('helpSupport'), meta: badges.help, onSelect: () => nav({ to: screenHref(merchant.id, 'help') }) },
    { kind: 'separator' },
    { label: t('language'), meta: t('languageMeta'), onSelect: () => setLocale(locale === 'en' ? 'fr' : 'en') },
    { label: t('signOut'), onSelect: () => void signOut() },
  ];
  return (
    <Menu label={t('accountMenu')} width={280} items={items} trigger={({ props }) => (
      <button type="button" aria-label={t('accountMenu')} {...props} className="nl-topbar-item">
        <Avatar initials={user?.initials ?? '··'} size={32} />
        <span className="nl-topbar-item-text">{user ? `${user.firstName} ${user.lastName}` : ''}<span>{t(roleKey)} · {merchant.displayName}</span></span>
        <CaretDown size={13} aria-hidden />
      </button>
    )} />
  );
}
