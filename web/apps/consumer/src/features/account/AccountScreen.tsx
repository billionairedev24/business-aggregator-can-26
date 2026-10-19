import type { ReactNode } from 'react';
import { useSearch } from '@tanstack/react-router';
import { Bell, CalendarCheck, CreditCard, Heart, Lifebuoy, MapPinLine, Package, ShieldCheck, Storefront, User } from '@phosphor-icons/react';
import { EmptyState, SiteLink, Skeleton, fullNavEnabled } from '@northline/ui';
import { signInHref, useViewer } from '../session/api';
import { useAccountT } from './messages';
import { isAccountTab, tabHref, type AccountTab } from './tabs';
import { AddressesTab } from './AddressesTab';
import { DietaryTab } from './DietaryTab';
import { FavouritesTab } from './FavouritesTab';
import { HelpTab } from './HelpTab';
import { LanguageTab } from './LanguageTab';
import { NotificationsTab } from './NotificationsTab';
import { CardList, PaymentsTab } from './PaymentsTab';
import { PlusTab } from './PlusTab';
import { ProfileTab } from './ProfileTab';
import { SecurityTab } from './SecurityTab';
import { Crumbs } from '../pages/Crumbs';
import { WalletTab } from './WalletTab';

/** Which story builds a tab that isn't here yet. */
const PENDING: Partial<Record<AccountTab, string>> = {};

const TABS: Partial<Record<AccountTab, () => ReactNode>> = {
  wallet: () => <WalletTab cards={<CardList />} />,
  favourites: () => <FavouritesTab />,
  payments: () => <PaymentsTab />,
  profile: () => <ProfileTab />,
  addresses: () => <AddressesTab />,
  security: () => <SecurityTab />,
  notifications: () => <NotificationsTab />,
  language: () => <LanguageTab />,
  dietary: () => <DietaryTab />,
  plus: () => <PlusTab />,
  help: () => <HelpTab />,
};

/** The account's sections hidden for the demo (redesign § 3.6): Wallet & points, Dietary, Plus and Language. */
export const LATER_TABS: readonly AccountTab[] = ['wallet', 'dietary', 'plus', 'language'];
const visible = (tab: AccountTab) => fullNavEnabled() || !LATER_TABS.includes(tab);

/**
 * The account (redesign § 2.1, AT Shop:596 AccountHome): `/account` is "Your Account" — a card grid (Orders, Bookings,
 * Addresses, Payments, Login & security, Notifications, Saved, Profile, Help, Sell) — and `?tab=` opens one section
 * under a "Your account ›" crumb (the account menu's links). The eleven-tab rail that opened on Wallet & points is gone
 * (§ 3.6); the sections hidden for the demo open the card grid unless `VITE_NL_FULL_NAV` is set. Personal: loaded in
 * the browser.
 */
export function AccountScreen() {
  const t = useAccountT();
  const search = useSearch({ strict: false }) as { tab?: unknown };
  const tab: AccountTab | null = isAccountTab(search.tab) && visible(search.tab) ? search.tab : null;
  const { user, loading } = useViewer();
  if (loading) return <AccountSkeleton />;
  if (!user) {
    return (
      <div className="nl-page nl-acct">
        <EmptyState action={<SiteLink href={signInHref(tab ? tabHref(tab) : '/account')} className="btn btn-primary">{t('signInAction')}</SiteLink>}>{t('signIn')}</EmptyState>
      </div>
    );
  }
  if (!tab) return <AccountHome />;
  return (
    <AccountLayout tab={tab}>
      {TABS[tab]?.() ?? <TabPending tab={tab} />}
    </AccountLayout>
  );
}

const CARDS = [
  { key: 'orders', href: '/account/orders', icon: Package },
  { key: 'bookings', href: '/account/orders?tab=bookings', icon: CalendarCheck },
  { key: 'addresses', href: tabHref('addresses'), icon: MapPinLine },
  { key: 'payments', href: tabHref('payments'), icon: CreditCard },
  { key: 'security', href: tabHref('security'), icon: ShieldCheck },
  { key: 'notifications', href: tabHref('notifications'), icon: Bell },
  { key: 'favourites', href: tabHref('favourites'), icon: Heart },
  { key: 'profile', href: tabHref('profile'), icon: User },
  { key: 'help', href: tabHref('help'), icon: Lifebuoy },
  { key: 'sell', href: '/sell', icon: Storefront },
] as const;

/** "Your Account" (AT Shop:596): max-width 1100, a card grid minmax(300px,1fr) — a 48px icon circle, title, one line. */
function AccountHome() {
  const t = useAccountT();
  return (
    <div className="nl-page nl-acct nl-acct-home">
      <h1 className="nl-acct-h1">{t('homeTitle')}</h1>
      <ul className="nl-acct-cards" aria-label={t('homeLabel')}>
        {CARDS.map(c => (
          <li key={c.key}>
            <SiteLink href={c.href} className="nl-acct-card">
              <span className="nl-acct-card-icon" aria-hidden><c.icon size={24} /></span>
              <span className="nl-acct-card-text">
                <span className="nl-acct-card-title">{t(`card_${c.key}`)}</span>
                <span className="nl-acct-card-desc">{t(`card_${c.key}_desc`)}</span>
              </span>
            </SiteLink>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** One section of the account under the "Your account ›" crumb (the section's own h1 follows). */
export function AccountLayout({ tab, children }: { tab: AccountTab; children: ReactNode }) {
  const t = useAccountT();
  return (
    <div className="nl-page nl-acct">
      <Crumbs label={t('crumbs')} items={[{ label: t('crumbAccount'), href: '/account' }, { label: t(`tab_${tab}`) }]} />
      <div className="nl-acct-split">
        <AccountSide current={tab} />
        <section className="nl-acct-body" aria-labelledby="acct-title">{children}</section>
      </div>
    </div>
  );
}

/** The account's sections beside a section (wide screens): the same list as the card grid, the current one marked. */
export function AccountSide({ current }: { current?: string }) {
  const t = useAccountT();
  return (
    <nav aria-label={t('homeLabel')} className="nl-acct-side">
      <ul>
        {CARDS.filter(c => c.key !== 'sell').map(c => (
          <li key={c.key}>
            <SiteLink href={c.href} className="nl-acct-side-item" aria-current={c.key === current ? 'page' : undefined}>
              <c.icon size={20} weight={c.key === current ? 'fill' : 'regular'} aria-hidden />{t(`card_${c.key}`)}
            </SiteLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}

function TabPending({ tab }: { tab: AccountTab }) {
  const t = useAccountT();
  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t(`tab_${tab}`)}</h1>
      <EmptyState>{t('tabPending', { story: PENDING[tab] ?? '' })}</EmptyState>
    </>
  );
}

export function AccountSkeleton() {
  const t = useAccountT();
  return (
    <div className="nl-page nl-acct" aria-busy="true">
      <span className="nl-sr-only">{t('loading')}</span>
      <div className="nl-acct-grid">
        <Skeleton width="40%" height={40} />
        <div className="nl-acct-cards">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} height={96} radius={8} />)}</div>
      </div>
    </div>
  );
}
