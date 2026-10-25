import { router } from 'expo-router';
import type { ReactNode } from 'react';

import { useI18n } from '../i18n';
import { SearchPill, TopBar } from '../ui/screen';
import { useDeliveryLocation } from './DeliveryLocation';

/** Opens the location sheet over the current screen (`/location`, a bottom sheet). */
export const openLocation = () => router.push('/location');

/**
 * The tabs' top (E-15 Plainsight, the web header's phone rows): the brand and `actions`, the one search bar, then
 * where things come from — "Deliver to" an address the person chose (or their default address), "Near" the fallback
 * market — on one line with an ellipsis, opening the location sheet; `below` under it (Home's department strip).
 */
export function PlaceBar({ lead, actions, below, search = true, searchTestID = 'home-search' }: { lead?: ReactNode; actions?: ReactNode; below?: ReactNode; search?: boolean; searchTestID?: string }) {
  const { t } = useI18n();
  const { location } = useDeliveryLocation();
  const place = location.label ?? location.city;
  const exact = location.status === 'saved' || location.status === 'address' || location.status === 'detected';
  return (
    <TopBar
      lead={lead}
      kicker={exact ? t('rd.home.deliverTo') : t('lt.near')}
      place={place ?? t('lt.setPlace')}
      placeLabel={place ? t('shop.home.placeLabel', { place }) : t('shop.home.setAddressLabel')}
      placeHint={t('shop.home.placeHint')}
      onPlace={openLocation}
      actions={actions}
      below={below}
      search={search ? <SearchPill label={t('rd.search.placeholder')} onPress={() => router.push('/search')} testID={searchTestID} /> : undefined}
    />
  );
}
