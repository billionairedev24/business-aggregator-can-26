import { useRouterState } from '@tanstack/react-router';
import { Lifebuoy, SignIn, Storefront, Translate, UserPlus } from '@phosphor-icons/react';
import { AccountMenu, LocationPill, SiteHeader, SiteLink, Skeleton, useLocale, type SiteNavLink } from '@northline/ui';
import { useCartCount } from '../cart/api';
import { CUISINES, DEPARTMENTS, SERVICE_CATEGORIES, type Tile } from '../home/catalog';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import type { Scope } from '../search/api';
import { SiteSearch } from '../search/SiteSearch';
import { signInHref, useSignOut, useViewer } from '../session/api';
import { AccountArea } from './AccountArea';
import { useShellT } from './messages';
import { SCREENS, screenFor } from './screens';

/** The strip's shortcuts after the sections (design/revamp/a.html): the main departments, then the busiest service categories. */
const SHOP_SHORTCUTS = ['bakery', 'butcher', 'groceries', 'pharmacy', 'clothing'];
const SERVICE_SHORTCUTS = ['mechanic', 'cleaning', 'barber', 'tutor', 'plumber'];
/** Today's deals: the shop's items on tonight's pooled run (the shared delivery is the day's offer; no deals feed yet). */
export const DEALS_HREF = '/search?scope=shop&delivery=tonight';

/**
 * The consumer header (E-15 A · Plainsight, design/revamp/a.html): logo · "Deliver to" (opens the location dialog,
 * never a geolocation prompt on load) · the search with its All / Services / Shop / Food scope (the current section's
 * by default) · "Hello, … / Account & orders" (the account menu, where orders, bookings and the FR/EN switch live) ·
 * cart with its count; then the violet department strip (S-188): "All" (every category by section, in a sheet — the menu button
 * opens it on phones), Services · Shop · Food, the main departments | the busiest service categories, "Today's deals"
 * and "Sell on …" at the right end. The sign-in pages show the logo alone.
 */
export function Header() {
  const t = useShellT();
  const { locale, setLocale } = useLocale();
  const pathname = useRouterState({ select: s => s.location.pathname });
  const href = useRouterState({ select: s => s.location.href });
  const screen = screenFor(pathname);
  const { user, loading } = useViewer();
  const cartCount = useCartCount(!loading);
  const { location, chooser } = useDeliveryLocation();
  const signOut = useSignOut();
  // on the results page the field shows what was searched, in the scope it was searched in
  const searched = useRouterState({ select: st => (st.location.pathname === '/search' ? st.location.search as { q?: unknown; scope?: unknown } : undefined) });
  const section = screen ? SCREENS[screen].section : undefined;
  const auth = !!(screen && SCREENS[screen].auth);
  const searchedScope = typeof searched?.scope === 'string' && ['services', 'shop', 'food'].includes(searched.scope) ? searched.scope as Scope : undefined;
  const scope: Scope = searched ? searchedScope ?? 'all' : section ?? 'all';
  const here = (to: string) => pathname === to || pathname.startsWith(`${to}/`);
  const link = (tile: Tile, group?: string): SiteNavLink => ({ key: tile.key, label: locale === 'fr' ? tile.fr : tile.en, href: tile.href, group, current: !tile.href.includes('?') && here(tile.href) });
  const pick = (all: readonly Tile[], keys: readonly string[], group: string) => keys.map(k => all.find(x => x.key === k)).filter((x): x is Tile => !!x).map(x => link(x, group));
  const shortcuts = [...pick(DEPARTMENTS, SHOP_SHORTCUTS, 'shop'), ...pick(SERVICE_CATEGORIES, SERVICE_SHORTCUTS, 'services')];
  // one current item in the strip: the category's shortcut when it has one, else its section
  const inShortcut = shortcuts.some(l => l.current);
  const links = (['services', 'shop', 'food'] as const).map(key => ({ key, label: t(key), href: `/${key}`, current: section === key && !inShortcut }));
  const end: SiteNavLink[] = [
    { key: 'deals', label: t('deals'), href: DEALS_HREF, tone: 'deal' },
    { key: 'sell', label: t('sell'), href: '/sell', current: pathname === '/sell' },
  ];
  const menu = [
    { key: 'services', heading: t('services'), href: '/services', links: SERVICE_CATEGORIES.map(x => link(x)) },
    { key: 'shop', heading: t('shop'), href: '/shop', links: DEPARTMENTS.map(x => link(x)) },
    { key: 'food', heading: t('food'), href: '/food', links: CUISINES.map(x => link(x)) },
  ];
  const toggleLocale = () => setLocale(locale === 'fr' ? 'en' : 'fr');
  const language = { key: 'language', label: t('langSwitch'), lang: locale === 'fr' ? 'en-CA' : 'fr-CA', icon: Translate, onSelect: toggleLocale };
  const firstName = user?.firstName?.trim() || undefined;

  const account = auth ? null
    : loading ? <Skeleton width={120} height={36} radius="var(--radius-control)" />
    : user ? <AccountArea user={user} onSignOut={() => void signOut('/')} onToggleLocale={toggleLocale} greeting={{ line1: firstName ? t('hello', { name: firstName }) : t('helloSignedIn'), line2: t('accountOrders') }} />
    : (
      <AccountMenu greeting={{ line1: t('helloGuest'), line2: t('accountOrders') }} sections={[
        { items: [
          { key: 'signIn', label: t('signIn'), icon: SignIn, href: signInHref(href) },
          { key: 'register', label: t('createAccount'), icon: UserPlus, href: signInHref(href, 'register') },
        ] },
        { heading: '', items: [language] },
      ]} />
    );

  return (
    <SiteHeader
      location={auth ? null : <LocationPill status={location.status} label={location.label} name={firstName} kicker={section === 'services' ? 'near' : 'deliverTo'} onOpen={chooser.show} />}
      search={auth ? undefined : <SiteSearch variant="header" placeholder={t('search')} scope={scope} initial={typeof searched?.q === 'string' ? searched.q : ''} />}
      links={auth ? [] : links}
      shortcuts={auth ? [] : shortcuts}
      end={auth ? [] : end}
      menu={auth ? [] : menu}
      menuExtra={auth ? undefined : (
        <>
          <SiteLink href="/help"><Lifebuoy size={20} aria-hidden />{t('helpLink')}</SiteLink>
          <SiteLink href="/sell"><Storefront size={20} aria-hidden />{t('sellOrOffer')}</SiteLink>
          <button type="button" lang={language.lang} onClick={toggleLocale}><Translate size={20} aria-hidden />{t('langSwitch')}</button>
        </>
      )}
      cart={auth ? undefined : { count: cartCount, href: '/cart' }}
      account={account}
    />
  );
}
