import { useSearch } from '@tanstack/react-router';
import { ApplicationsHead } from '../verify/ApplicationsHead';
import type { PlaceFilter } from '../shell/PlaceFilters';
import { AgeChecks } from './AgeChecks';
import { LicenceQueue } from './LicenceQueue';
import { ListingVetting } from './ListingVetting';
import { usePlace } from '../shell/scope';
import '../shell/queues.css';

type View = 'listings' | 'licences' | 'age';

/**
 * Listing vetting (S-92) with the age-restricted sales views (2026-10-04): licences in review and the age checks — the
 * Listings, Licences and Age checks tabs of Applications (redesign § 3.12).
 */
export function VettingScreen() {
  const search = useSearch({ strict: false }) as PlaceFilter & { view?: View };
  const view = search.view ?? 'listings';
  const filter = usePlace();
  return (
    <div className="bo-page">
      <ApplicationsHead tab={view} filter={filter} />
      <div className="bo-stack">
        {view === 'listings' ? <ListingVetting /> : view === 'licences' ? <LicenceQueue filter={filter} /> : <AgeChecks province={filter.province} />}
      </div>
    </div>
  );
}
