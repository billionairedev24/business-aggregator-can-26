import { useNavigate, useSearch } from '@tanstack/react-router';
import { UnderlineTabs } from '@northline/ui';
import { PlaceFilters, type PlaceFilter } from '../shell/PlaceFilters';
import { SCREEN_PATH } from '../shell/screens';
import { AgeChecks } from './AgeChecks';
import { LicenceQueue } from './LicenceQueue';
import { ListingVetting } from './ListingVetting';
import { useAgeVetT } from './ageMessages';
import { useVettingT } from './messages';

type View = 'listings' | 'licences' | 'age';

/** Listing vetting (S-92) with the age-restricted sales views (2026-10-04): licences in review and the age checks. */
export function VettingScreen() {
  const t = useAgeVetT();
  const tv = useVettingT();
  const search = useSearch({ strict: false }) as PlaceFilter & { view?: View };
  const navigate = useNavigate();
  const view = search.view ?? 'listings';
  const filter = { province: search.province, market: search.market };
  const go = (v: View) => void navigate({ to: SCREEN_PATH.vetting, search: { ...filter, ...(v === 'listings' ? {} : { view: v }) } });
  return (
    <div>
      <UnderlineTabs aria-label={t('tabs')} value={view} onChange={go}
        options={[{ value: 'listings', label: t('tabListings') }, { value: 'licences', label: t('tabLicences') }, { value: 'age', label: t('tabAge') }]} />
      {view === 'listings' ? <ListingVetting /> : (
        <>
          <div className="nl-q-top"><span className="nl-q-kicker">{tv('kicker')}</span><PlaceFilters to={SCREEN_PATH.vetting} filter={filter} /></div>
          {view === 'licences' ? <LicenceQueue filter={filter} /> : <AgeChecks province={filter.province} />}
        </>
      )}
    </div>
  );
}
