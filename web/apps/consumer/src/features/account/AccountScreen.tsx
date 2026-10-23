import type { ReactNode } from 'react';
import { useSearch } from '@tanstack/react-router';
import { Bell, CalendarCheck, Coins, CreditCard, Crown, Gift, Heart, Lifebuoy, MapPinLine, Package, ShieldCheck, Storefront, User } from '@phosphor-icons/react';
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
import { PlusTab } from '../loyalty/PlusTab';
import { InviteTab } from '../loyalty/InviteTab';
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
  invite: () => <InviteTab />,
  help: () => <HelpTab />,
};

/**
 * The account's sections hidden for the demo (redesign § 3.6): Dietary and Language. Wallet & points and Plus came
 * back with S-152 (points earn, Plus bills).
 */
export const LATER_TABS: readonly AccountTab[] = ['dietary', 'language'];
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
  { key: 'points', href: tabHref('wallet'), icon: Coins },
  { key: 'plus', href: tabHref('plus'), icon: Crown },
  { key: 'invite', href: tabHref('invite'), icon: Gift },
  { key: 'profile', href: tabHref('profile'), icon: User },
  { key: 'help', href: tabHref('help'), icon: Lifebuoy },
  { key: 'sell', href: '/sell', icon: Storefront },
] as const;

/**
 * "Your account" (E-15 S-173, audit #9: the old 1100 px grid left a dead right column): the account's sections as a grid
 * of cards across the page — four a row at 1440, one on a phone — each a square icon in the well grey, the title and one
 * line; "Sell or offer services" is a line under the grid, not a card.
 */
function AccountHome() {
  const t = useAccountT();
  const { user } = useViewer();
  return (
    <div className="nl-page nl-acct nl-acct-home">
      <div className="nl-acct-head">
        <h1 className="nl-acct-h1">{t('homeTitle')}</h1>
        {user ? <span className="nl-acct-hello">{t('homeHello', { name: user.firstName })}</span> : null}
      </div>
      <ul className="nl-acct-cards" aria-label={t('homeLabel')}>
        {CARDS.filter(c => c.key !== 'sell').map(c => (
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
      <p className="nl-acct-sell"><Storefront size={18} aria-hidden /> <SiteLink href="/sell">{t('card_sell')}</SiteLink> <span className="nl-acct-card-desc">{t('card_sell_desc')}</span></p>
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
        <AccountSide current={tab === 'wallet' ? 'points' : tab} />
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
              <c.icon size={18} weight={c.key === current ? 'bold' : 'regular'} aria-hidden />{t(`card_${c.key}`)}
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
        <Skeleton width="40%" height={32} />
        <div className="nl-acct-cards">{Array.from({ length: 8 }, (_, i) => <Skeleton key={i} height={88} />)}</div>
      </div>
    </div>
  );
}
