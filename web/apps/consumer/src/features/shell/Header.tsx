import { useRouterState } from '@tanstack/react-router';
import { CookingPot, Lifebuoy, ShoppingBag, SignIn, Storefront, Translate, UserPlus, Wrench } from '@phosphor-icons/react';
import { AccountMenu, LocationPill, SiteHeader, SiteLink, Skeleton, fullNavEnabled, useLocale } from '@northline/ui';
import { useCartCount } from '../cart/api';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { SiteSearch } from '../search/SiteSearch';
import { signInHref, useSignOut, useViewer } from '../session/api';
import { AccountArea } from './AccountArea';
import { useShellT } from './messages';
import { SCREENS, screenFor } from './screens';

/**
 * The consumer header (light direction, 2026-10-19): one white bar — logo · the location control (the place's name at
 * every width; opens the location dialog) · search (every page) · the avatar menu (signed in, or "Welcome, guest" with
 * Sign in / Create account) · cart (count) — and Services / Shop (/ Food) as quiet tabs, in the menu sheet on phones.
 * Orders & bookings are in the account menu only; the language switch is in the footer, the account menu and the
 * phone menu. The sign-in pages show the logo alone.
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
  const icons = { services: <Wrench size={22} aria-hidden />, shop: <ShoppingBag size={22} aria-hidden />, food: <CookingPot size={22} aria-hidden /> };
  const links = (fullNavEnabled() ? ['services', 'shop', 'food'] as const : ['services', 'shop'] as const).map(key => ({ key, label: t(key), href: `/${key}`, current: section === key, icon: icons[key] }));
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
