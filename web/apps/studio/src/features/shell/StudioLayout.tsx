import { useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useRouterState } from '@tanstack/react-router';
import { CaretDown } from '@phosphor-icons/react';
import { AppShell, Avatar, Logo, Menu, PageHeadingProvider, ShellSearch, testingToolsEnabled, useLocale, WorkspaceSwitcher, type MenuEntry, type ShellSearchScope } from '@northline/ui';
import { useSession, useSignOut } from '../../lib/session';
import { businessesQuery, navBadgesQuery, type MerchantSummary, type MerchantType } from './api';
import { useShellT } from './messages';
import { AssistantButton } from '../assistant/AssistantDrawer';
import { threadsQuery } from '../messages/api';
import { PilotControl } from '../pilot/PilotControl';
import { homeScreen, screenFromPath, screenHref, screensFor } from './nav';
import { buildNav } from './navMenu';

const RAIL_KEY = 'nl.studio.rail';
const readRail = () => { try { return localStorage.getItem(RAIL_KEY) === '1'; } catch { return false; } };

export const tierTag = (m: Pick<MerchantSummary, 'tier' | 'status'>): { cls: string; key: 'tier_master' | 'tier_trusted' | 'tier_registered' | 'tier_pending' | 'tier_applicant' } =>
  m.status === 'applicant' ? { cls: 'tag-neutral', key: 'tier_applicant' }
  : m.status === 'pending' ? { cls: 'tag-warning', key: 'tier_pending' }
  : m.tier === 'master' ? { cls: 'tag-success', key: 'tier_master' }
  : m.tier === 'trusted' ? { cls: 'tag-success', key: 'tier_trusted' }
  : { cls: 'tag-neutral', key: 'tier_registered' };

/** The brand's logo with the portal's name ("Studio") on the white top bar (design/revamp/a.html: mark 28px + "Studio"). */
export const Brand = ({ label }: { label: string }) => <Logo size={28} product={label} />;

/**
 * Studio chrome (E-15, design/revamp/a.html screens 7–8): the white top bar (logo + "Studio", the business switcher, the
 * global search with its "/" shortcut, the assistant, the account menu) over the grouped, portal-aware sidebar. The
 * pilot's "Send feedback" only with the testing tools on.
 */
export function StudioLayout({ merchant, children }: { merchant: MerchantSummary; children: ReactNode }) {
  const t = useShellT();
  const nav = useNavigate();
  const pathname = useRouterState({ select: s => s.location.pathname });
  const badges = useQuery(navBadgesQuery(merchant.id)).data ?? {};
  const { pinned, groups } = buildNav(merchant.type, merchant.id, t, badges);
  const [rail, setRail] = useState(readRail);
  const current = screenFromPath(pathname);
  // S-142: a screen that is loading or failed keeps its name (the nav label) as the page's h1
  const heading = [...pinned, ...groups.flatMap(g => g.items)].find(i => i.key === current)?.label;
  return (
    <AppShell
      brand={<Brand label={t('studio')} />}
      headerStart={<BusinessSwitcher merchant={merchant} />}
      search={<StudioSearch merchantId={merchant.id} type={merchant.type} />}
      headerEnd={<><AssistantButton merchantId={merchant.id} /><AccountMenu merchant={merchant} badges={badges} /></>}
      pinned={pinned} groups={groups} currentKey={current}
      onNavigate={i => nav({ to: i.href })}
      rail={rail} onRailChange={r => { setRail(r); try { localStorage.setItem(RAIL_KEY, r ? '1' : '0'); } catch { /* private mode */ } }}
    ><PageHeadingProvider value={heading}>{children}</PageHeadingProvider>{testingToolsEnabled() ? <PilotControl merchantId={merchant.id} /> : null}</AppShell>
  );
}

/** The business switcher: this business and its city; the menu lists every business of the account, then "Add another". */
function BusinessSwitcher({ merchant }: { merchant: MerchantSummary }) {
  const t = useShellT();
  const nav = useNavigate();
  const { data: businesses = [] } = useQuery(businessesQuery);
  const items: MenuEntry[] = [
    ...businesses.map((b): MenuEntry => ({
      label: b.displayName, meta: [b.city, t(`type_${b.type}`), b.tier ? t(tierTag(b).key) : null].filter(Boolean).join(' · '),
      checked: b.id === merchant.id, onSelect: () => nav({ to: screenHref(b.id, homeScreen(b.type)) }),
    })),
    ...(businesses.length ? [{ kind: 'separator' } as const] : []),
    { label: t('addBusiness'), href: '/onboarding', onSelect: () => {} },
  ];
  return <WorkspaceSwitcher label={t('switchBusiness')} name={merchant.displayName} meta={merchant.city} items={items} />;
}

/**
 * The global search: where a query can go in this portal — the orders table (refs, customers, items), the catalogue
 * table, or the first conversation with that name. Screens the portal doesn't have are left out.
 */
function StudioSearch({ merchantId, type }: { merchantId: string; type: MerchantType }) {
  const t = useShellT();
  const nav = useNavigate();
  const qc = useQueryClient();
  const has = new Set(screensFor(type));
  const products = type === 'seller' ? 'scope_products_seller' : type === 'provider' ? 'scope_products_provider' : 'scope_products_both';
  const scopes: ShellSearchScope[] = [
    ...(has.has('orders') ? [{ key: 'orders', label: (q: string) => t('scope_orders', { q }) }] : []),
    ...(has.has('products') ? [{ key: 'products', label: (q: string) => t(products, { q }) }] : []),
    { key: 'messages', label: (q: string) => t('scope_messages', { q }) },
  ];
  const onSearch = async (q: string, scope: string) => {
    if (scope === 'orders') return void nav({ to: screenHref(merchantId, 'orders'), search: { q } as never });
    if (scope === 'products') return void nav({ to: screenHref(merchantId, 'products'), search: { q } as never });
    const threads = await qc.fetchQuery(threadsQuery(merchantId)).catch(() => []);
    const needle = q.toLocaleLowerCase();
    const hit = threads.find(th => [th.counterpartName, th.subject, th.refCode].some(v => v?.toLocaleLowerCase().includes(needle)));
    void nav({ to: screenHref(merchantId, 'messages'), search: (hit ? { thread: hit.id } : {}) as never });
  };
  const placeholder = t(type === 'kitchen' ? 'search_kitchen' : type === 'seller' ? 'search_seller' : type === 'provider' ? 'search_provider' : 'search_both');
  return <ShellSearch label={t('search')} placeholder={placeholder} scopes={scopes} onSearch={(q, s) => void onSearch(q, s)} />;
}

function AccountMenu({ merchant, badges }: { merchant: MerchantSummary; badges: Record<string, string> }) {
  const t = useShellT();
  const nav = useNavigate();
  const { data: session } = useSession();
  const { locale, setLocale } = useLocale();
  const signOut = useSignOut();
  const user = session?.user;
  const roleKey = `role_${merchant.role ?? 'owner'}` as Parameters<typeof t>[0];
  const items: MenuEntry[] = [
    { label: t('profile'), meta: t('passkey'), onSelect: () => nav({ to: `${screenHref(merchant.id, 'settings')}`, search: { tab: 'security' } as never }) },
    { label: t('team'), meta: merchant.teamCount ? t('members', { count: merchant.teamCount }) : undefined, onSelect: () => nav({ to: screenHref(merchant.id, 'settings'), search: { tab: 'team' } as never }) },
    { label: t('helpSupport'), meta: badges.help, onSelect: () => nav({ to: screenHref(merchant.id, 'help') }) },
    { kind: 'separator' },
    { label: t('language'), meta: t('languageMeta'), onSelect: () => setLocale(locale === 'en' ? 'fr' : 'en') },
    { label: t('signOut'), onSelect: () => void signOut() },
  ];
  return (
    <Menu label={t('accountMenu')} width={280} items={items} trigger={({ props }) => (
      <button type="button" aria-label={t('accountMenu')} {...props} className="nl-topbar-item">
        <Avatar initials={user?.initials ?? '··'} size={30} />
        <span className="nl-topbar-item-text">{user ? `${user.firstName} ${user.lastName}` : ''}<span>{t(roleKey)}</span></span>
        <CaretDown size={13} aria-hidden />
      </button>
    )} />
  );
}
