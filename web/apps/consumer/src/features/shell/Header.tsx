import { useRouterState } from '@tanstack/react-router';
import { LocationPill, SiteHeader, SiteLink, Skeleton, useLocale } from '@northline/ui';
import { useCartCount } from '../cart/api';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { SiteSearch } from '../search/SiteSearch';
import { signInHref, useSignOut, useViewer } from '../session/api';
import { AccountArea } from './AccountArea';
import { useShellT } from './messages';
import { SCREENS, screenFor } from './screens';

/**
 * The consumer header (design 06): brand · location pill · search (off-home) · Services / Shop / Food · FR/EN ·
 * cart (count) · account (menu, or Sign in + Create account). Orders & bookings are in the account menu only.
 */
export function Header() {
  const t = useShellT();
  const { locale, setLocale } = useLocale();
  const pathname = useRouterState({ select: s => s.location.pathname });
  const href = useRouterState({ select: s => s.location.href });
  const screen = screenFor(pathname);
  const { user, loading } = useViewer();
  const cartCount = useCartCount(!loading);
  const { location } = useDeliveryLocation();
  const signOut = useSignOut();
  // on the results page the field shows what was searched
  const searched = useRouterState({ select: st => (st.location.pathname === '/search' ? (st.location.search as { q?: unknown }).q : undefined) });
  const section = screen ? SCREENS[screen].section : undefined;
  const links = (['services', 'shop', 'food'] as const).map(key => ({ key, label: t(key), href: `/${key}`, current: section === key }));

  const account = screen && SCREENS[screen].auth ? null
    : loading ? <Skeleton width={70} height={44} radius={999} />
    : user ? <AccountArea user={user} onSignOut={() => void signOut('/')} />
    : (
      <div className="nl-site-actions">
        <SiteLink href={signInHref(href)} className="btn btn-ghost">{t('signIn')}</SiteLink>
        <SiteLink href={signInHref(href, 'register')} className="btn btn-primary">{t('createAccount')}</SiteLink>
      </div>
    );

  return (
    <SiteHeader
      location={<LocationPill status={location.status} label={location.label} href="/location" />}
      search={screen === 'home' ? undefined : (
        <SiteSearch variant="header" placeholder={t('search')} initial={typeof searched === 'string' ? searched : ''} />
      )}
      links={links}
      locale={locale}
      onToggleLocale={() => setLocale(locale === 'fr' ? 'en' : 'fr')}
      cart={{ count: cartCount, href: '/cart' }}
      account={account}
    />
  );
}
