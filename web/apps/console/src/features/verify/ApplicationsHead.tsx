import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { BoHeader, UnderlineTabs } from '@northline/ui';
import { meQuery, opens } from '../shell/api';
import { useActiveGrant } from '../shell/ConsoleLayout';
import type { PlaceFilter } from '../shell/PlaceFilters';
import { SCREEN_PATH } from '../shell/screens';
import { useVerifyT } from './messages';
import { useRegistryT } from './registryMessages';
import './applications.css';

export type ApplicationsTab = 'businesses' | 'registry' | 'listings' | 'licences' | 'age';

/**
 * The head of Applications (redesign § 2.3 / § 3.12: verification and listing vetting are one entry): the h1, a sub
 * line (the place is the top bar's scope, S-177) and the queues as tabs — Businesses (`/verification`, S-79) and the vetting views
 * (`/vetting`, S-92: listings, licences, age checks), and S-158's registry checks (`/verification?view=registry`). A tab
 * shows only when the role opens its screen.
 */
export function ApplicationsHead({ tab, filter, sub }: { tab: ApplicationsTab; filter: PlaceFilter; sub?: ReactNode }) {
  const t = useVerifyT();
  const tr = useRegistryT();
  const navigate = useNavigate();
  const me = useQuery(meQuery).data;
  const grant = useActiveGrant(me ?? { userId: '', roles: [] });
  const place = { province: filter.province, market: filter.market };
  const tabs: { value: ApplicationsTab; label: string }[] = [
    ...(opens(grant, 'verify') ? [{ value: 'businesses' as const, label: t('tabBusinesses') }, { value: 'registry' as const, label: tr('tab') }] : []),
    ...(opens(grant, 'vetting') ? [
      { value: 'listings' as const, label: t('tabListings') }, { value: 'licences' as const, label: t('tabLicences') }, { value: 'age' as const, label: t('tabAge') },
    ] : []),
  ];
  const go = (next: ApplicationsTab) => void navigate(next === 'businesses' || next === 'registry'
    ? { to: SCREEN_PATH.verify, search: { ...place, ...(next === 'registry' ? { view: 'registry' } : {}) } as never }
    : { to: SCREEN_PATH.vetting, search: { ...place, ...(next === 'listings' ? {} : { view: next }) } as never });
  return (
    <>
      <BoHeader title={t('title')} sub={sub} />
      {tabs.length > 1 ? <UnderlineTabs aria-label={t('tabs')} value={tab} onChange={go} options={tabs} /> : null}
    </>
  );
}
