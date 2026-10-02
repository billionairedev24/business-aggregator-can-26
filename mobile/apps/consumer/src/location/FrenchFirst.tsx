import { useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';

import { geoApi, isFrenchFirst } from '../api/geo';
import { useI18n } from '../i18n';
import { services } from '../services';
import { useDeliveryLocation } from './DeliveryLocation';

/** Where the language the person picked (You › Language) is kept; none = never picked. */
export const LANGUAGE_KEY = 'nl.app.language';

/**
 * S-116 (Loi 96 readiness): when the delivery location is in a French-first place (region configuration — the
 * market's rule, else its province's), the app switches to French unless the person picked a language. Their pick
 * always wins; nothing here names a place.
 */
export function FrenchFirst() {
  const { locale, setLocale } = useI18n();
  const { location } = useDeliveryLocation();
  const lang = locale === 'fr-CA' ? 'fr' : 'en';
  const regions = useQuery({ queryKey: ['geo', 'regions', locale], queryFn: () => geoApi(services().api).regions(lang), staleTime: 3_600_000, enabled: location.status !== 'locating' });
  const first = location.status !== 'locating' && isFrenchFirst(regions.data, location);
  useEffect(() => {
    if (!first || locale === 'fr-CA') return;
    void services()
      .store.getItem(LANGUAGE_KEY)
      .then((picked) => {
        if (!picked) setLocale('fr-CA');
      })
      .catch(() => undefined);
    // once per place: the person's own pick afterwards is kept
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [first]);
  return null;
}
