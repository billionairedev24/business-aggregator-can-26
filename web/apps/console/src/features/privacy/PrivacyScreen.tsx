import { useNavigate, useSearch } from '@tanstack/react-router';
import { UnderlineTabs } from '@northline/ui';
import { SCREEN_PATH } from '../shell/screens';
import { PrivacyQueue } from './PrivacyQueue';
import { RetentionReport } from './RetentionReport';
import { useRetentionT } from './retentionMessages';

/** The privacy screen: people's requests (S-105) and the retention schedule's report (S-107). */
export function PrivacyScreen() {
  const t = useRetentionT();
  const search = useSearch({ strict: false }) as { view?: 'requests' | 'retention' };
  const navigate = useNavigate();
  const view = search.view ?? 'requests';
  return (
    <div>
      <UnderlineTabs aria-label={t('tabs')} value={view}
        options={[{ value: 'requests', label: t('tabRequests') }, { value: 'retention', label: t('tabRetention') }]}
        onChange={v => void navigate({ to: SCREEN_PATH.privacy, search: v === 'retention' ? { view: 'retention' } : {} })} />
      {view === 'retention' ? <RetentionReport /> : <PrivacyQueue />}
    </div>
  );
}
