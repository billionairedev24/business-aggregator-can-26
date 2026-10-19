import { useRouterState } from '@tanstack/react-router';
import { Lifebuoy, SignIn, Storefront, Translate, UserPlus } from '@phosphor-icons/react';
import { AccountMenu, LocationPill, SiteHeader, SiteLink, Skeleton, fullNavEnabled, useLocale } from '@northline/ui';
import { useCartCount } from '../cart/api';
import { CUISINES, DEPARTMENTS, SERVICE_CATEGORIES, type Tile } from '../home/catalog';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { SiteSearch } from '../search/SiteSearch';
import { signInHref, useSignOut, useViewer } from '../session/api';
import { AccountArea } from './AccountArea';
import { useShellT } from './messages';
import { SCREENS, screenFor } from './screens';

/** The nav row's shortcuts after the sections: the busiest service categories, then the main departments. */
const SERVICE_SHORTCUTS = ['mechanic', 'cleaning', 'plumber', 'movers', 'barber'];
const SHOP_SHORTCUTS = ['groceries', 'bakery', 'butcher', 'produce', 'pharmacy', 'clothing'];

/**
 * The consumer header (owner, 2026-10-19, after AfroTransact's): a compact white top bar — logo · the location control
 * (the place's name at every width; opens the location dialog) · search (the one search of every page, home included)
 * · the avatar menu (signed in, or "Welcome, guest" with Sign in / Create account) · cart (count) — and a slim nav row:
 * "All" (every category by section, sell, the language), Services / Shop (/ Food), the main categories and
 * departments (the catalogue's tiles, region-neutral), Help at the end. Orders & bookings are in the account menu only;
 * the language switch is in the footer, the account menu and the "All" sheet. The sign-in pages show the logo alone.
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
  // on the results page the field shows what was searched
  const searched = useRouterState({ select: st => (st.location.pathname === '/search' ? (st.location.search as { q?: unknown }).q : undefined) });
  const section = screen ? SCREENS[screen].section : undefined;
  const auth = !!(screen && SCREENS[screen].auth);
  // redesign § 3.7: Food as its own vertical is hidden for the demo (its routes stay)
  const full = fullNavEnabled();
  const here = (to: string) => pathname === to || pathname.startsWith(`${to}/`);
  const link = (tile: Tile) => ({ key: tile.key, label: locale === 'fr' ? tile.fr : tile.en, href: tile.href, current: !tile.href.includes('?') && here(tile.href) });
  const pick = (all: readonly Tile[], keys: readonly string[]) => keys.map(k => all.find(x => x.key === k)).filter((x): x is Tile => !!x).map(link);
  const shortcuts = [...pick(SERVICE_CATEGORIES, SERVICE_SHORTCUTS), ...pick(DEPARTMENTS, SHOP_SHORTCUTS)];
  // one current item in the row: the category's shortcut when it has one, else its section
  const inShortcut = shortcuts.some(l => l.current);
  // the nav row is words only (owner 2026-10-19: photos, not decorative icons); the sheet keeps its icons
  const links = (full ? ['services', 'shop', 'food'] as const : ['services', 'shop'] as const)
    .map(key => ({ key, label: t(key), href: `/${key}`, current: section === key && !inShortcut }));
  const menu = [
    { key: 'services', heading: t('services'), href: '/services', links: SERVICE_CATEGORIES.map(link) },
    { key: 'shop', heading: t('shop'), href: '/shop', links: DEPARTMENTS.map(link) },
    ...(full ? [{ key: 'food', heading: t('food'), href: '/food', links: CUISINES.map(link) }] : []),
  ];
  const toggleLocale = () => setLocale(locale === 'fr' ? 'en' : 'fr');
  const language = { key: 'language', label: t('langSwitch'), lang: locale === 'fr' ? 'en-CA' : 'fr-CA', icon: Translate, onSelect: toggleLocale };

  const account = auth ? null
    : loading ? <Skeleton width={56} height={44} radius={4} />
    : user ? <AccountArea user={user} onSignOut={() => void signOut('/')} onToggleLocale={toggleLocale} />
    : (
      <AccountMenu sections={[
        { items: [
          { key: 'signIn', label: t('signIn'), icon: SignIn, href: signInHref(href) },
          { key: 'register', label: t('createAccount'), icon: UserPlus, href: signInHref(href, 'register') },
        ] },
        { heading: '', items: [language] },
      ]} />
    );

  return (
    <SiteHeader
      location={auth ? null : <LocationPill status={location.status} label={location.label} kicker={section === 'services' ? 'near' : 'deliverTo'} onOpen={chooser.show} />}
      search={auth ? undefined : <SiteSearch variant="header" placeholder={t('search')} initial={typeof searched === 'string' ? searched : ''} />}
      links={auth ? [] : links}
      shortcuts={auth ? [] : shortcuts}
      end={auth ? [] : [{ key: 'help', label: t('helpLink'), href: '/help', current: pathname === '/help' }]}
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
