import type { ReactNode } from 'react';
import clsx from 'clsx';
import { Link, useSearch } from '@tanstack/react-router';
import { EmptyState, SiteLink, Skeleton } from '@northline/ui';
import { signInHref, useViewer } from '../session/api';
import { useAccountT } from './messages';
import { ACCOUNT_TABS, isAccountTab, tabHref, type AccountTab } from './tabs';
import { AddressesTab } from './AddressesTab';
import { DietaryTab } from './DietaryTab';
import { FavouritesTab } from './FavouritesTab';
import { LanguageTab } from './LanguageTab';
import { NotificationsTab } from './NotificationsTab';
import { CardList, PaymentsTab } from './PaymentsTab';
import { PlusTab } from './PlusTab';
import { ProfileTab } from './ProfileTab';
import { SecurityTab } from './SecurityTab';
import { WalletTab } from './WalletTab';

/** Which story builds a tab that isn't here yet. */
const PENDING: Partial<Record<AccountTab, string>> = { help: 'S-60' };

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
};

/**
 * The account area (design 06 `account`): the tabs on the left (a wrapping row on phones), the chosen tab on the right.
 * `?tab=` picks it (the account menu's links); unknown or missing → Wallet & points. Personal: loaded in the browser.
 */
export function AccountScreen() {
  const t = useAccountT();
  const search = useSearch({ strict: false }) as { tab?: unknown };
  const tab: AccountTab = isAccountTab(search.tab) ? search.tab : 'wallet';
  const { user, loading } = useViewer();
  if (loading) return <AccountSkeleton />;
  if (!user) {
    return (
      <div className="nl-page nl-acct">
        <EmptyState action={<SiteLink href={signInHref(tabHref(tab))} className="btn btn-primary">{t('signInAction')}</SiteLink>}>{t('signIn')}</EmptyState>
      </div>
    );
  }
  return (
    <AccountLayout tab={tab}>
      {TABS[tab]?.() ?? <TabPending tab={tab} />}
    </AccountLayout>
  );
}

export function AccountLayout({ tab, children }: { tab: AccountTab; children: ReactNode }) {
  const t = useAccountT();
  return (
    <div className="nl-page nl-acct">
      <div className="nl-acct-grid">
        <nav className="nl-acct-tabs" aria-label={t('tabsLabel')}>
          {ACCOUNT_TABS.map(k => (
            <Link key={k} to="/account" search={{ tab: k } as never} className={clsx('nl-acct-tab', k === tab && 'is-current')} aria-current={k === tab ? 'page' : undefined}>
              {t(`tab_${k}`)}
            </Link>
          ))}
          <SiteLink href="/sell" className="nl-acct-tab">{t('tab_sell')}</SiteLink>
        </nav>
        <section className="nl-acct-body" aria-labelledby="acct-title">{children}</section>
      </div>
    </div>
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
        <div className="nl-acct-tabs">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} height={36} />)}</div>
        <div><Skeleton width="50%" height={40} /><Skeleton width="70%" height={16} style={{ marginTop: 14 }} /><Skeleton height={120} style={{ marginTop: 18 }} /></div>
      </div>
    </div>
  );
}
